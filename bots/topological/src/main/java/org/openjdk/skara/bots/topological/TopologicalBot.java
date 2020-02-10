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
package org.openjdk.skara.bots.topological;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bot that automatically merges any changes from a dependency branch into a target branch
 */
class TopologicalBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");
    private final Path storage;
    private final HostedRepository hostedRepo;
    private final List<Branch> branches;
    private final String depsFileName;

    TopologicalBot(Path storage, HostedRepository repo, List<Branch> branches, String depsFileName) {
        this.storage = storage;
        this.hostedRepo = repo;
        this.branches = branches;
        this.depsFileName = depsFileName;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof TopologicalBot)) {
            return true;
        }
        var otherBot = (TopologicalBot) other;
        return !hostedRepo.name().equals(otherBot.hostedRepo.name());
    }

    @Override
    public void run(Path scratchPath) {
        log.info("Starting topobot run");
        try {
            var sanitizedUrl = URLEncoder.encode(hostedRepo.webUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);
            Repository repo;
            if (!Files.exists(dir)) {
                log.info("Cloning " + hostedRepo.name());
                Files.createDirectories(dir);
                repo = Repository.clone(hostedRepo.url(), dir);
            } else {
                log.info("Found existing scratch directory for " + hostedRepo.name());
                repo = Repository.get(dir)
                        .orElseThrow(() -> new RuntimeException("Repository in " + dir + " has vanished"));
            }

            repo.fetchAll();
            var depsFile = repo.root().resolve(depsFileName);

            var orderedBranches = orderedBranches(repo, depsFile);
            log.info("Merge order " + orderedBranches);
            for (var branch : orderedBranches) {
                log.info("Processing branch " + branch + "...");
                repo.checkout(branch);
                var parents = dependencies(repo, repo.head(), depsFile).collect(Collectors.toSet());
                List<String> failedMerges = new ArrayList<>();
                boolean progress;
                boolean failed;
                do {
                    // We need to attempt merge parents in any order that works. Keep merging
                    // and pushing, until no further progress can be made.
                    progress = false;
                    failed = false;
                    for (var parentsIt = parents.iterator(); parentsIt.hasNext();) {
                        var parent = parentsIt.next();
                        try {
                            mergeIfAhead(repo, branch, parent);
                            progress = true;
                            parentsIt.remove(); // avoid doing pointless merges
                        } catch(IOException e) {
                            log.severe("Merge with " + parent + " failed. Reverting...");
                            repo.abortMerge();
                            failedMerges.add(branch + " <- " + parent);
                            failed = true;
                        }
                    }
                } while(progress && failed);

                if (!failedMerges.isEmpty()) {
                    throw new IOException("There were failed merges:\n" + failedMerges);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("Ending topobot run");
    }

    private static Stream<Branch> dependencies(Repository repo, Hash hash, Path depsFile) throws IOException {
        return repo.lines(depsFile, hash).map(l -> {
            var lines = l.stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (lines.size() > 1) {
                throw new IllegalStateException("Multiple non-empty lines in " + depsFile.toString() + ": "
                        + String.join("\n", lines));
            }
            return Stream.of(lines.get(0).split(" ")).map(Branch::new);
        })
        .orElse(Stream.of(repo.defaultBranch()));
    }

    private List<Branch> orderedBranches(Repository repo, Path depsFile) throws IOException {
        List<Edge> deps = new ArrayList<>();
        for (var branch : branches) {
            dependencies(repo, repo.resolve("origin/" + branch.name()).orElseThrow(), depsFile)
                    .forEach(dep -> deps.add(new Edge(dep, branch)));
        }
        var defaultBranch = repo.defaultBranch();
        return TopologicalSort.sort(deps).stream()
            .filter(branch -> !branch.equals(defaultBranch))
            .collect(Collectors.toList());
    }

    private void mergeIfAhead(Repository repo, Branch branch, Branch parent) throws IOException {
        var fromHash = repo.resolve(parent.name()).orElseThrow();
        var oldHead = repo.head();
        if (!repo.contains(branch, fromHash)) {
            var isFastForward = repo.isAncestor(oldHead, fromHash);
            repo.merge(fromHash);
            if (!isFastForward) {
                log.info("Merged " + parent + " into " + branch);
                repo.commit("Automatic merge with " + parent, "duke", "duke@openjdk.org");
            } else {
                log.info("Fast forwarded " + branch + " to " + parent);
            }
            try (var commits = repo.commits("origin/" + branch.name() + ".." + branch.name()).stream()) {
                log.info("merge with " + parent + " succeeded. The following commits will be pushed:\n"
                        + commits
                            .map(Commit::toString)
                            .collect(Collectors.joining("\n", "\n", "\n")));
            }
            try {
                repo.push(repo.head(), hostedRepo.url(), branch.name());
            } catch (IOException e) {
                log.severe("Pushing failed! Aborting...");
                repo.reset(oldHead, true);
                throw e;
            }
        }
    }

    @Override
    public String toString() {
        return "TopologicalBot@" + hostedRepo.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
