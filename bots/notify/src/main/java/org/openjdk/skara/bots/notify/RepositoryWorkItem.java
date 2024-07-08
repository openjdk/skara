/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify;

import java.time.Duration;
import java.time.ZonedDateTime;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.*;

/**
 * A RepositoryWorkItem acts on changes to a particular repository. Multiple kinds
 * of listeners can be notified about various types of changes. Some listeners can
 * handle being called multiple times with the same update while others cannot. To
 * avoid sending multiple emails or slack messages, we will never call such
 * listeners multiple times with the same update.
 *
 * This is achieved with a combination of declaring a listener idempotent and
 * throwing NonRetriableException. For a listener that is declared idempotent, we
 * will not update the history repository until after a successful notification,
 * which means the bot will retry until successful. For a listener that is not
 * idempotent, we will update the history repo before attempting to notify the
 * listener. If the listener fails without throwing NonRetriableException, we will
 * attempt a rollback of the history repo, which means the bot will likely retry
 * in the future, but it is not guaranteed as the bot could be killed at any time
 * and the state could then be lost.
 */
public class RepositoryWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<UpdatedTag> tagStorageBuilder;
    private final StorageBuilder<UpdatedBranch> branchStorageBuilder;
    private final List<RepositoryListener> listeners;

    private static final int NEW_REPOSITORY_COMMIT_THRESHOLD = 5;

    RepositoryWorkItem(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<UpdatedTag> tagStorageBuilder, StorageBuilder<UpdatedBranch> branchStorageBuilder, List<RepositoryListener> listeners) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.listeners = listeners;
    }

    private void handleNewRef(Repository localRepo, Reference ref, Collection<Reference> candidateRefs,
                              RepositoryListener listener, Path scratchPath) throws NonRetriableException {
        // Figure out the best parent ref
        var candidates = new HashSet<>(candidateRefs);
        candidates.remove(ref);
        if (candidates.size() == 0) {
            log.warning("No parent candidates found for branch '" + ref.name() + "' - ignoring");
            return;
        }

        var bestParent = candidates.stream()
                                   .map(c -> {
                                       try {
                                           return new AbstractMap.SimpleEntry<>(c, localRepo.commitMetadata(c.hash().hex() + ".." + ref.hash()));
                                       } catch (IOException e) {
                                           throw new UncheckedIOException(e);
                                       }
                                   })
                                   .min(Comparator.comparingInt(entry -> entry.getValue().size()))
                                   .orElseThrow();
        if (bestParent.getValue().size() > 1000) {
            throw new RuntimeException("Excessive amount of unique commits on new branch " + ref.name() +
                                               " detected (" + bestParent.getValue().size() + ") - skipping notifications");
        }
        List<Commit> bestParentCommits;
        try {
            bestParentCommits = localRepo.commits(bestParent.getKey().hash().hex() + ".." + ref.hash(), true).asList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var branch = new Branch(ref.name());
        var parent = new Branch(bestParent.getKey().name());
        listener.onNewBranch(repository, localRepo, scratchPath, bestParentCommits, parent, branch);
    }

    private void handleUpdatedRef(Repository localRepo, Reference ref, List<Commit> commits, RepositoryListener listener, Path scratchPath) throws NonRetriableException {
        var branch = new Branch(ref.name());
        listener.onNewCommits(repository, localRepo, scratchPath, commits, branch);
    }

    private List<Throwable> handleRef(Repository localRepo, UpdateHistory history, Reference ref,
                                      Collection<Reference> candidateRefs, Path scratchPath) throws IOException {
        var errors = new ArrayList<Throwable>();
        var branch = new Branch(ref.name());
        for (var listener : listeners) {
            var lastHash = history.branchHash(branch, listener.name());
            if (lastHash.isEmpty()) {
                log.warning("No previous history found for branch '" + branch + "' and listener '" + listener.name() + " - resetting mark");
                if (!listener.idempotent()) {
                    history.setBranchHash(branch, listener.name(), ref.hash());
                }
                try {
                    handleNewRef(localRepo, ref, candidateRefs, listener, scratchPath.resolve(listener.name()));
                } catch (NonRetriableException e) {
                    errors.add(e.cause());
                    continue;
                } catch (RuntimeException e) {
                    // FIXME: Attempt rollback? No current listener that would use it
                    errors.add(e);
                    continue;
                }
                if (listener.idempotent()) {
                    history.setBranchHash(branch, listener.name(), ref.hash());
                }
            } else {
                var commitMetadata = localRepo.commitMetadata(lastHash.get() + ".." + ref.hash());
                if (commitMetadata.size() == 0) {
                    continue;
                }
                if (commitMetadata.size() > 1000) {
                    history.setBranchHash(branch, listener.name(), ref.hash());
                    errors.add(new RuntimeException("Excessive amount of new commits on branch " + branch.name() +
                                                       " detected (" + commitMetadata.size() + ") for listener '" +
                                                       listener.name() + "' - skipping notifications"));
                    continue;
                }

                var commits = localRepo.commits(lastHash.get() + ".." + ref.hash(), true).asList();
                if (!listener.idempotent()) {
                    history.setBranchHash(branch, listener.name(), ref.hash());
                }
                try {
                    handleUpdatedRef(localRepo, ref, commits, listener, scratchPath.resolve(listener.name()));
                    if (log.isLoggable(Level.INFO)) {
                        var now = ZonedDateTime.now();
                        for (Commit commit : commits) {
                            var latency = Duration.between(commit.metadata().committed(), now);
                            log.log(Level.INFO, "Time from committed to notified for " + commit.hash()
                                    + " on branch " + ref + " " + latency, latency);
                        }
                    }
                } catch (NonRetriableException e) {
                    log.log(Level.INFO, "Non retriable exception occurred", e);
                    errors.add(e.cause());
                    continue;
                } catch (RuntimeException e) {
                    // Attempt to roll back
                    if (!listener.idempotent()) {
                        log.log(Level.INFO, "Retriable exception occurred", e);
                        history.setBranchHash(branch, listener.name(), lastHash.get());
                    }
                    errors.add(e);
                    continue;
                }
                if (listener.idempotent()) {
                    history.setBranchHash(branch, listener.name(), ref.hash());
                }
            }
        }
        return errors;
    }

    private Optional<OpenJDKTag> existingPrevious(OpenJDKTag tag, Set<OpenJDKTag> allJdkTags) {
        while (true) {
            var candidate = tag.previous();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            tag = candidate.get();
            if (!allJdkTags.contains(tag)) {
                continue;
            }
            return Optional.of(tag);
        }
    }

    private List<Throwable> handleTags(Repository localRepo, UpdateHistory history, RepositoryListener listener, Path scratchPath) throws IOException {
        var errors = new ArrayList<Throwable>();
        var tags = localRepo.tags();
        var newTags = tags.stream()
                          .filter(tag -> !history.hasTag(tag, listener.name()) || history.shouldRetryTagUpdate(tag, listener.name()))
                          .collect(Collectors.toList());

        if (tags.size() == newTags.size()) {
            if (tags.size() > 0) {
                log.warning("No previous tag history found - ignoring all current tags");
                history.addTags(tags, listener.name());
            }
            return errors;
        }

        if (newTags.size() > 10) {
            history.addTags(newTags, listener.name());
            errors.add(new RuntimeException("Excessive amount of new tags detected (" + newTags.size() +
                                               ") - skipping notifications"));
            return errors;
        }

        // Filter for tags that appear in non pr-branches
        var branches = repository.branches();
        newTags = newTags.stream()
                .filter(tag -> tagInNonPrBranch(tag, branches, localRepo))
                .toList();

        var allJdkTags = tags.stream()
                             .map(OpenJDKTag::create)
                             .filter(Optional::isPresent)
                             .map(Optional::get)
                             .collect(Collectors.toSet());
        var newJdkTags = newTags.stream()
                                .map(OpenJDKTag::create)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .sorted(Comparator.comparingInt(tag -> tag.buildNum().orElse(-1)))
                                .collect(Collectors.toList());
        for (var tag : newJdkTags) {
            var commits = new ArrayList<Commit>();

            // Try to determine which commits are new since the last build
            var previous = existingPrevious(tag, allJdkTags);
            if (previous.isPresent()) {
                commits.addAll(localRepo.commits(previous.get().tag() + ".." + tag.tag()).asList());
            }

            // If none are found, just include the commit that was tagged
            if (commits.isEmpty()) {
                var commit = localRepo.lookup(tag.tag());
                if (commit.isEmpty()) {
                    throw new RuntimeException("Failed to lookup tag '" + tag.toString() + "'");
                } else {
                    commits.add(commit.get());
                }
            }

            Collections.reverse(commits);
            var annotation = localRepo.annotate(tag.tag());

            if (!listener.idempotent()) {
                history.addTags(List.of(tag.tag()), listener.name());
            }
            try {
                listener.onNewOpenJDKTagCommits(repository, localRepo, scratchPath, commits, tag, annotation.orElse(null));
            } catch (NonRetriableException e) {
                errors.add(e.cause());
                continue;
            } catch (RuntimeException e) {
                errors.add(e);
                if (!listener.idempotent()) {
                    history.retryTagUpdate(tag.tag(), listener.name());
                }
                continue;
            }
            if (listener.idempotent()) {
                history.addTags(List.of(tag.tag()), listener.name());
            }
        }

        var newNonJdkTags = newTags.stream()
                                   .filter(tag -> OpenJDKTag.create(tag).isEmpty())
                                   .collect(Collectors.toList());
        for (var tag : newNonJdkTags) {
            var commit = localRepo.lookup(tag);
            if (commit.isEmpty()) {
                throw new RuntimeException("Failed to lookup tag '" + tag.toString() + "'");
            }

            var annotation = localRepo.annotate(tag);

            if (!listener.idempotent()) {
                history.addTags(List.of(tag), listener.name());
            }
            try {
                listener.onNewTagCommit(repository, localRepo, scratchPath, commit.get(), tag, annotation.orElse(null));
            } catch (NonRetriableException e) {
                errors.add(e.cause());
                continue;
            } catch (RuntimeException e) {
                errors.add(e);
                if (!listener.idempotent()) {
                    history.retryTagUpdate(tag, listener.name());
                }
                continue;
            }
            if (listener.idempotent()) {
                history.addTags(List.of(tag), listener.name());
            }
        }

        return errors;
    }

    private boolean tagInNonPrBranch(Tag tag, List<HostedBranch> branches, Repository localRepository) {
        try {
            for (var branch : branches) {
                if (!PreIntegrations.isPreintegrationBranch(branch.name())) {
                    var hash = localRepository.resolve(tag).orElseThrow();
                    if (localRepository.isAncestor(hash, branch.hash())) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof RepositoryWorkItem otherItem)) {
            return true;
        }
        if (!repository.name().equals(otherItem.repository.name())) {
            return true;
        }
        return false;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var historyPath = scratchPath.resolve("notify").resolve("history");
        var repositoryPool = new HostedRepositoryPool(storagePath.resolve("seeds"));
        var notifierScratchPath = scratchPath.resolve("notify").resolve("notifier");

        try {
            var localRepo = repositoryPool.materializeBare(repository, scratchPath.resolve("notify").resolve("repowi").resolve(repository.name()));
            var defaultBranchName = localRepo.defaultBranch().name();
            // All the branches can be candidate branches except pr/X branches
            var candidateRefs = localRepo.remoteBranches(repository.authenticatedUrl().toString())
                    .stream()
                    .filter(ref -> !ref.name().startsWith("pr/"))
                    .toList();
            var knownRefs = candidateRefs
                    .stream()
                    .filter(ref -> branches.matcher(ref.name()).matches())
                    .toList();
            localRepo.fetchAll(repository.authenticatedUrl(), true);

            var history = UpdateHistory.create(tagStorageBuilder, historyPath.resolve("tags"), branchStorageBuilder, historyPath.resolve("branches"));
            var errors = new ArrayList<Throwable>();

            boolean hasBranchHistory = !history.isEmpty();
            for (var ref : knownRefs) {
                if (!hasBranchHistory) {
                    log.warning("No previous history found for any branch - resetting mark for '" + ref.name());
                    if (localRepo.commitCount() <= NEW_REPOSITORY_COMMIT_THRESHOLD) {
                        log.info("This is a new repo, starting notifications from the very first commit");
                        for (var listener : listeners) {
                            log.info("Resetting mark for branch '" + ref.name() + "' for listener '" + listener.name() + "'");
                            // Initialize the mark for the branches with special Git empty tree hash to trigger notifications on all existing commits.
                            history.setBranchHash(new Branch(ref.name()), listener.name(), localRepo.initialHash());
                        }
                    } else {
                        log.info("This is an existing repo with history, starting notifications from commits after " + ref.hash());
                        for (var listener : listeners) {
                            log.info("Resetting mark for branch '" + ref.name() + "' for listener '" + listener.name() + "'");
                            // Initialize the mark for the branches with the current HEAD hash. Notifications will start on future commits.
                            history.setBranchHash(new Branch(ref.name()), listener.name(), ref.hash());
                        }
                    }
                }
                errors.addAll(handleRef(localRepo, history, ref, candidateRefs, scratchPath));
            }

            for (var listener : listeners) {
                errors.addAll(handleTags(localRepo, history, listener, notifierScratchPath.resolve(listener.name())));
            }

            if (!errors.isEmpty()) {
                errors.forEach(error -> log.log(Level.WARNING, error.getMessage(), error));
                throw new RuntimeException("Errors detected when processing repository notifications", errors.get(0));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "RepositoryWorkItem@" + repository.name();
    }

    @Override
    public String botName() {
        return NotifyBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "repository";
    }
}
