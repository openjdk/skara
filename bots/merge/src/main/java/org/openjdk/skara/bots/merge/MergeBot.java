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
package org.openjdk.skara.bots.merge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Logger;

class MergeBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final Path storage;

    private final HostedRepository target;
    private final HostedRepository fork;
    private final List<Spec> specs;

    MergeBot(Path storage, HostedRepository target, HostedRepository fork,
             List<Spec> specs) {
        this.storage = storage;
        this.target = target;
        this.fork = fork;
        this.specs = specs;
    }

    final static class Spec {
        private final HostedRepository fromRepo;
        private final Branch fromBranch;
        private final Branch toBranch;

        Spec(HostedRepository fromRepo, Branch fromBranch, Branch toBranch) {
            this.fromRepo = fromRepo;
            this.fromBranch = fromBranch;
            this.toBranch = toBranch;
        }

        HostedRepository fromRepo() {
            return fromRepo;
        }

        Branch fromBranch() {
            return fromBranch;
        }

        Branch toBranch() {
            return toBranch;
        }
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof MergeBot)) {
            return true;
        }
        var otherBot = (MergeBot) other;
        return !target.name().equals(otherBot.target.name());
    }

    @Override
    public void run(Path scratchPath) {
        try {
            var sanitizedUrl =
                URLEncoder.encode(target.webUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);

            Repository repo = null;
            if (!Files.exists(dir)) {
                log.info("Cloning " + fork.name());
                Files.createDirectories(dir);
                repo = Repository.clone(fork.url(), dir);
            } else {
                log.info("Found existing scratch directory for " + fork.name());
                repo = Repository.get(dir).orElseThrow(() -> {
                        return new RuntimeException("Repository in " + dir + " has vanished");
                });
            }

            // Sync personal fork
            var remoteBranches = repo.remoteBranches(target.url().toString());
            for (var branch : remoteBranches) {
                var fetchHead = repo.fetch(target.url(), branch.hash().hex());
                repo.push(fetchHead, fork.url(), branch.name());
            }

            var prs = target.pullRequests();
            var currentUser = target.forge().currentUser();

            for (var spec : specs) {
                var toBranch = spec.toBranch();
                var fromRepo = spec.fromRepo();
                var fromBranch = spec.fromBranch();

                log.info("Trying to merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name());

                // Checkout the branch to merge into
                repo.pull(fork.url().toString(), toBranch.name());
                repo.checkout(toBranch, false);

                // Check if merge conflict pull request is present
                var isMergeConflictPRPresent = false;
                var title = "Cannot automatically merge " + fromRepo.name() + ":" + fromBranch.name() + " to " + toBranch.name();
                var marker = "<!-- MERGE CONFLICTS -->";
                for (var pr : prs) {
                    if (pr.title().equals(title) &&
                        pr.body().startsWith(marker) &&
                        currentUser.equals(pr.author())) {
                        var lines = pr.body().split("\n");
                        var head = new Hash(lines[1].substring(5, 45));
                        if (repo.contains(toBranch, head)) {
                            log.info("Closing resolved merge conflict PR " + pr.id());
                            pr.addComment("Merge conflicts have been resolved, closing this PR");
                            pr.setState(PullRequest.State.CLOSED);
                        } else {
                            log.info("Outstanding unresolved merge already present");
                            isMergeConflictPRPresent = true;
                        }
                        break;
                    }
                }

                if (isMergeConflictPRPresent) {
                    continue;
                }

                log.info("Fetching " + fromRepo.name() + ":" + fromBranch.name());
                var fetchHead = repo.fetch(fromRepo.url(), fromBranch.name());
                var head = repo.resolve(toBranch.name()).orElseThrow(() ->
                        new IOException("Could not resolve branch " + toBranch.name())
                );
                if (repo.contains(toBranch, fetchHead)) {
                    log.info("Nothing to merge");
                    continue;
                }

                var isAncestor = repo.isAncestor(head, fetchHead);

                log.info("Trying to merge into " + toBranch.name());
                IOException error = null;
                try {
                    repo.merge(fetchHead);
                } catch (IOException e) {
                    error = e;
                }

                if (error == null) {
                    log.info("Pushing successful merge");
                    if (!isAncestor) {
                        repo.commit("Merge", "duke", "duke@openjdk.org");
                    }
                    repo.push(toBranch, target.url().toString(), false);
                } else {
                    log.info("Got error: " + error.getMessage());
                    log.info("Aborting unsuccesful merge");
                    repo.abortMerge();

                    var fromRepoName = Path.of(fromRepo.webUrl().getPath()).getFileName();
                    var branchDesc = fromRepoName + "/" + fromBranch.name() + "->" + toBranch.name();
                    repo.push(fetchHead, fork.url(), branchDesc, true);

                    log.info("Creating pull request to alert");
                    var mergeBase = repo.mergeBase(fetchHead, head);
                    var commits = repo.commits(mergeBase.hex() + ".." + fetchHead.hex(), true).asList();

                    var message = new ArrayList<String>();
                    message.add(marker);
                    message.add("<!-- " + fetchHead.hex() + " -->");
                    message.add("The following commits from `" + fromRepo.name() + ":" + fromBranch.name() +
                                "` could *not* be automatically merged into `" + toBranch.name() + "`:");
                    message.add("");
                    for (var commit : commits) {
                        message.add("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
                    }
                    message.add("");
                    message.add("To manually resolve these merge conflicts, please create a personal fork of " +
                                target.webUrl() + " and execute the following commands:");
                    message.add("");
                    message.add("```bash");
                    message.add("$ git checkout " + toBranch.name());
                    message.add("$ git pull " + fromRepo.webUrl() + " " + fromBranch.name());
                    message.add("```");
                    message.add("");
                    message.add("When you have resolved the conflicts resulting from the above commands, run:");
                    message.add("");
                    message.add("```bash");
                    message.add("$ git add paths/to/files/with/conflicts");
                    message.add("$ git commit -m 'Merge'");
                    message.add("```");
                    message.add("");
                    message.add("Push the resolved merge conflict to your personal fork and " +
                                "create a pull request towards this repository.");
                    message.add("");
                    message.add("This pull request will be closed automatically by a bot once " +
                                "the merge conflicts have been resolved.");
                    fork.createPullRequest(target,
                                           toBranch.name(),
                                           branchDesc,
                                           title,
                                           message);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "MergeBot@(" + target.name() + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
