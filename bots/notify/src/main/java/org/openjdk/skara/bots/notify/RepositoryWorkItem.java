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
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RepositoryWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<Tag> tagStorageBuilder;
    private final StorageBuilder<ResolvedBranch> branchStorageBuilder;
    private final List<RepositoryUpdateConsumer> updaters;

    RepositoryWorkItem(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<Tag> tagStorageBuilder, StorageBuilder<ResolvedBranch> branchStorageBuilder, List<RepositoryUpdateConsumer> updaters) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.updaters = updaters;
    }

    private void handleNewRef(Repository localRepo, Reference ref, Collection<Reference> allRefs, boolean runOnlyIdempotent) {
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
        try {
            var bestParentCommits = localRepo.commits(bestParent.getKey().hash().hex() + ".." + ref.hash(), true);
            for (var updater : updaters) {
                if (updater.isIdempotent() != runOnlyIdempotent) {
                    continue;
                }
                var branch = new Branch(ref.name());
                var parent = new Branch(bestParent.getKey().name());
                updater.handleNewBranch(repository, localRepo, bestParentCommits.asList(), parent, branch);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleUpdatedRef(Repository localRepo, Reference ref, List<Commit> commits, boolean runOnlyIdempotent) {
        for (var updater : updaters) {
            if (updater.isIdempotent() != runOnlyIdempotent) {
                continue;
            }
            var branch = new Branch(ref.name());
            updater.handleCommits(repository, localRepo, commits, branch);
        }
    }

    private void handleRef(Repository localRepo, UpdateHistory history, Reference ref, Collection<Reference> allRefs) throws IOException {
        var branch = new Branch(ref.name());
        var lastHash = history.branchHash(branch);
        if (lastHash.isEmpty()) {
            log.warning("No previous history found for branch '" + branch + "' - resetting mark");
            handleNewRef(localRepo, ref, allRefs, true);
            history.setBranchHash(branch, ref.hash());
            handleNewRef(localRepo, ref, allRefs, false);
        } else {
            var commitMetadata = localRepo.commitMetadata(lastHash.get() + ".." + ref.hash());
            if (commitMetadata.size() == 0) {
                return;
            }
            if (commitMetadata.size() > 1000) {
                history.setBranchHash(branch, ref.hash());
                throw new RuntimeException("Excessive amount of new commits on branch " + branch.name() +
                                                   " detected (" + commitMetadata.size() + ") - skipping notifications");
            }

            var commits = localRepo.commits(lastHash.get() + ".." + ref.hash(), true).asList();
            handleUpdatedRef(localRepo, ref, commits, true);
            history.setBranchHash(branch, ref.hash());
            handleUpdatedRef(localRepo, ref, commits, false);
        }
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

    private void handleTags(Repository localRepo, UpdateHistory history) throws IOException {
        var tags = localRepo.tags();
        var newTags = tags.stream()
                          .filter(tag -> !history.hasTag(tag))
                          .collect(Collectors.toList());

        if (tags.size() == newTags.size()) {
            if (tags.size() > 0) {
                log.warning("No previous tag history found - ignoring all current tags");
                history.addTags(tags);
            }
            return;
        }

        if (newTags.size() > 10) {
            history.addTags(newTags);
            throw new RuntimeException("Excessive amount of new tags detected (" + newTags.size() +
                                               ") - skipping notifications");
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

            // Run all notifiers that can be safely re-run
            updaters.stream()
                    .filter(RepositoryUpdateConsumer::isIdempotent)
                    .forEach(updater -> updater.handleOpenJDKTagCommits(repository, localRepo, commits, tag, annotation.orElse(null)));

            // Now update the history
            history.addTags(List.of(tag.tag()));

            // Finally run all one-shot notifiers
            updaters.stream()
                    .filter(updater -> !updater.isIdempotent())
                    .forEach(updater -> updater.handleOpenJDKTagCommits(repository, localRepo, commits, tag, annotation.orElse(null)));
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

            // Run all notifiers that can be safely re-run
            updaters.stream()
                    .filter(RepositoryUpdateConsumer::isIdempotent)
                    .forEach(updater -> updater.handleTagCommit(repository, localRepo, commit.get(), tag, annotation.orElse(null)));

            // Now update the history
            history.addTags(List.of(tag));

            // Finally run all one-shot notifiers
            updaters.stream()
                    .filter(updater -> !updater.isIdempotent())
                    .forEach(updater -> updater.handleTagCommit(repository, localRepo, commit.get(), tag, annotation.orElse(null)));
        }
    }

    private Repository fetchAll(Path dir, URI remote) throws IOException {
        Repository repo = null;
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            repo = Repository.clone(remote, dir);
        } else {
            repo = Repository.get(dir).orElseThrow(() -> new RuntimeException("Repository in " + dir + " has vanished"));
        }
        repo.fetchAll();
        return repo;
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
            handleTags(localRepo, history);

            boolean hasBranchHistory = knownRefs.stream()
                                                .map(ref -> history.branchHash(new Branch(ref.name())))
                                                .anyMatch(Optional::isPresent);
            for (var ref : knownRefs) {
                if (!hasBranchHistory) {
                    log.warning("No previous history found for any branch - resetting mark for '" + ref.name() + "'");
                    history.setBranchHash(new Branch(ref.name()), ref.hash());
                } else {
                    handleRef(localRepo, history, ref, knownRefs);
                }
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
