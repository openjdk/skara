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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.HostedRepository;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class JNotifyBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<Tag> tagStorageBuilder;
    private final StorageBuilder<ResolvedBranch> branchStorageBuilder;
    private final List<UpdateConsumer> updaters;

    JNotifyBot(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<Tag> tagStorageBuilder, StorageBuilder<ResolvedBranch> branchStorageBuilder, List<UpdateConsumer> updaters) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.updaters = updaters;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof JNotifyBot)) {
            return true;
        }
        JNotifyBot otherItem = (JNotifyBot) other;
        if (!repository.getName().equals(otherItem.repository.getName())) {
            return true;
        }
        return false;
    }

    private void handleNewRef(Repository localRepo, Reference ref, Collection<Reference> allRefs) {
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
                                           return new AbstractMap.SimpleEntry<>(c, localRepo.commits(c.hash().hex() + ".." + ref.hash(), true).asList());
                                       } catch (IOException e) {
                                           throw new UncheckedIOException(e);
                                       }
                                   })
                                   .min(Comparator.comparingInt(entry -> entry.getValue().size()))
                                   .orElseThrow();
        for (var updater : updaters) {
            var branch = new Branch(ref.name());
            var parent = new Branch(bestParent.getKey().name());
            updater.handleNewBranch(repository, bestParent.getValue(), parent, branch);
        }
    }

    private void handleUpdatedRef(Repository localRepo, Reference ref, List<Commit> commits) {
        for (var updater : updaters) {
            var branch = new Branch(ref.name());
            updater.handleCommits(repository, commits, branch);
        }
    }

    private void handleRef(Repository localRepo, UpdateHistory history, Reference ref, Collection<Reference> allRefs) throws IOException {
        var branch = new Branch(ref.name());
        var lastHash = history.branchHash(branch);
        if (lastHash.isEmpty()) {
            log.warning("No previous history found for branch '" + branch + "' - resetting mark");
            history.setBranchHash(branch, ref.hash());
            handleNewRef(localRepo, ref, allRefs);
        } else {
            var commits = localRepo.commits(lastHash.get() + ".." + ref.hash()).asList();
            if (commits.size() == 0) {
                return;
            }
            history.setBranchHash(branch, ref.hash());
            Collections.reverse(commits);
            handleUpdatedRef(localRepo, ref, commits);
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
            // Update the history first - if there is a problem here we don't want to send out multiple updates
            history.addTags(List.of(tag.tag()));

            var commits = new ArrayList<Commit>();
            var previous = existingPrevious(tag, allJdkTags);
            if (previous.isEmpty()) {
                var commit = localRepo.lookup(tag.tag());
                if (commit.isEmpty()) {
                    throw new RuntimeException("Failed to lookup tag '" + tag.toString() + "'");
                } else {
                    commits.add(commit.get());
                    log.warning("No previous tag found for '" + tag.tag() + "'");
                }
            } else {
                commits.addAll(localRepo.commits(previous.get().tag() + ".." + tag.tag()).asList());
            }

            Collections.reverse(commits);
            for (var updater : updaters) {
                updater.handleTagCommits(repository, commits, tag);
            }
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
    public void run(Path scratchPath) {
        var sanitizedUrl = URLEncoder.encode(repository.getWebUrl().toString() + "v2", StandardCharsets.UTF_8);
        var path = storagePath.resolve(sanitizedUrl);
        var historyPath = scratchPath.resolve("notify").resolve("history");

        try {
            var localRepo = fetchAll(path, repository.getUrl());
            var history = UpdateHistory.create(tagStorageBuilder, historyPath.resolve("tags"), branchStorageBuilder, historyPath.resolve("branches"));
            handleTags(localRepo, history);

            var knownRefs = localRepo.remoteBranches("origin")
                                     .stream()
                                     .filter(ref -> branches.matcher(ref.name()).matches())
                                     .collect(Collectors.toList());
            for (var ref : knownRefs) {
                handleRef(localRepo, history, ref, knownRefs);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "JNotifyBot@" + repository.getName();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
