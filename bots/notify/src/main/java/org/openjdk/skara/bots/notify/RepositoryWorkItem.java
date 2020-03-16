/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;

public class RepositoryWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<UpdatedTag> tagStorageBuilder;
    private final StorageBuilder<UpdatedBranch> branchStorageBuilder;
    private final List<RepositoryUpdateConsumer> updaters;

    RepositoryWorkItem(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<UpdatedTag> tagStorageBuilder, StorageBuilder<UpdatedBranch> branchStorageBuilder, List<RepositoryUpdateConsumer> updaters) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.updaters = updaters;
    }

    private void handleNewRef(Repository localRepo, Reference ref, Collection<Reference> allRefs, RepositoryUpdateConsumer updater) throws NonRetriableException {
        // Figure out the best parent ref
        var candidates = new HashSet<>(allRefs);
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
        updater.handleNewBranch(repository, localRepo, bestParentCommits, parent, branch);
    }

    private void handleUpdatedRef(Repository localRepo, Reference ref, List<Commit> commits, RepositoryUpdateConsumer updater) throws NonRetriableException {
        var branch = new Branch(ref.name());
        updater.handleCommits(repository, localRepo, commits, branch);
    }

    private List<Throwable> handleRef(Repository localRepo, UpdateHistory history, Reference ref, Collection<Reference> allRefs) throws IOException {
        var errors = new ArrayList<Throwable>();
        var branch = new Branch(ref.name());
        for (var updater : updaters) {
            var lastHash = history.branchHash(branch, updater.name());
            if (lastHash.isEmpty()) {
                log.warning("No previous history found for branch '" + branch + "' and updater '" + updater.name() + " - resetting mark");
                history.setBranchHash(branch, updater.name(), ref.hash());
                try {
                    handleNewRef(localRepo, ref, allRefs, updater);
                } catch (NonRetriableException e) {
                    errors.add(e.cause());
                } catch (RuntimeException e) {
                    // FIXME: Attempt rollback?
                    errors.add(e);
                }
            } else {
                var commitMetadata = localRepo.commitMetadata(lastHash.get() + ".." + ref.hash());
                if (commitMetadata.size() == 0) {
                    continue;
                }
                if (commitMetadata.size() > 1000) {
                    history.setBranchHash(branch, updater.name(), ref.hash());
                    errors.add(new RuntimeException("Excessive amount of new commits on branch " + branch.name() +
                                                       " detected (" + commitMetadata.size() + ") for updater '" +
                                                       updater.name() + "' - skipping notifications"));
                    continue;
                }

                var commits = localRepo.commits(lastHash.get() + ".." + ref.hash(), true).asList();
                history.setBranchHash(branch, updater.name(), ref.hash());
                try {
                    handleUpdatedRef(localRepo, ref, commits, updater);
                } catch (NonRetriableException e) {
                    errors.add(e.cause());
                } catch (RuntimeException e) {
                    // Attempt to roll back
                    history.setBranchHash(branch, updater.name(), lastHash.get());
                    errors.add(e);
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

    private List<Throwable> handleTags(Repository localRepo, UpdateHistory history, RepositoryUpdateConsumer updater) throws IOException {
        var errors = new ArrayList<Throwable>();
        var tags = localRepo.tags();
        var newTags = tags.stream()
                          .filter(tag -> !history.hasTag(tag, updater.name()))
                          .collect(Collectors.toList());

        if (tags.size() == newTags.size()) {
            if (tags.size() > 0) {
                log.warning("No previous tag history found - ignoring all current tags");
                history.addTags(tags, updater.name());
            }
            return errors;
        }

        if (newTags.size() > 10) {
            history.addTags(newTags, updater.name());
            errors.add(new RuntimeException("Excessive amount of new tags detected (" + newTags.size() +
                                               ") - skipping notifications"));
            return errors;
        }

        var allJdkTags = tags.stream()
                             .map(OpenJDKTag::create)
                             .filter(Optional::isPresent)
                             .map(Optional::get)
                             .collect(Collectors.toSet());
        var newJdkTags = newTags.stream()
                                .map(OpenJDKTag::create)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .sorted(Comparator.comparingInt(OpenJDKTag::buildNum))
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

            history.addTags(List.of(tag.tag()), updater.name());
            try {
                updater.handleOpenJDKTagCommits(repository, localRepo, commits, tag, annotation.orElse(null));
            } catch (NonRetriableException e) {
                errors.add(e.cause());
            } catch (RuntimeException e) {
                errors.add(e);
                history.retryTagUpdate(tag.tag(), updater.name());
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

            history.addTags(List.of(tag), updater.name());
            try {
                updater.handleTagCommit(repository, localRepo, commit.get(), tag, annotation.orElse(null));
            } catch (NonRetriableException e) {
                errors.add(e.cause());
            } catch (RuntimeException e) {
                errors.add(e);
                history.retryTagUpdate(tag, updater.name());
            }
        }

        return errors;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof RepositoryWorkItem)) {
            return true;
        }
        RepositoryWorkItem otherItem = (RepositoryWorkItem) other;
        if (!repository.name().equals(otherItem.repository.name())) {
            return true;
        }
        return false;
    }

    @Override
    public void run(Path scratchPath) {
        var historyPath = scratchPath.resolve("notify").resolve("history");
        var repositoryPool = new HostedRepositoryPool(storagePath.resolve("seeds"));

        try {
            var localRepo = repositoryPool.materialize(repository, scratchPath.resolve("notify").resolve("repowi").resolve(repository.name()));
            var knownRefs = localRepo.remoteBranches(repository.url().toString())
                                     .stream()
                                     .filter(ref -> branches.matcher(ref.name()).matches())
                                     .collect(Collectors.toList());
            localRepo.fetchAll();

            var history = UpdateHistory.create(tagStorageBuilder, historyPath.resolve("tags"), branchStorageBuilder, historyPath.resolve("branches"));
            var errors = new ArrayList<Throwable>();

            for (var updater : updaters) {
                errors.addAll(handleTags(localRepo, history, updater));
            }

            boolean hasBranchHistory = !history.isEmpty();
            for (var ref : knownRefs) {
                if (!hasBranchHistory) {
                    log.warning("No previous history found for any branch - resetting mark for '" + ref.name());
                    for (var updater : updaters) {
                        log.info("Resetting mark for branch '" + ref.name() + "' for updater '" + updater.name() + "'");
                        history.setBranchHash(new Branch(ref.name()), updater.name(), ref.hash());
                    }
                } else {
                    errors.addAll(handleRef(localRepo, history, ref, knownRefs));
                }
            }
            if (!errors.isEmpty()) {
                errors.forEach(error -> log.throwing("RepositoryWorkItem", "run", error));
                throw new RuntimeException("Errors detected when processing repository notifications", errors.get(0));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "RepositoryWorkItem@" + repository.name();
    }
}
