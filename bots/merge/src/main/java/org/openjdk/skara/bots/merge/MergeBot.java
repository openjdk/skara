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
import org.openjdk.skara.host.*;
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
    private final HostedRepository from;
    private final Branch fromBranch;
    private final HostedRepository to;
    private final Branch toBranch;

    MergeBot(Path storage, HostedRepository from, Branch fromBranch,
              HostedRepository to, Branch toBranch) {
        this.storage = storage;
        this.from = from;
        this.fromBranch = fromBranch;
        this.to = to;
        this.toBranch = toBranch;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof MergeBot)) {
            return true;
        }
        var otherBot = (MergeBot) other;
        return !to.name().equals(otherBot.to.name());
    }

    @Override
    public void run(Path scratchPath) {
        try {
            var sanitizedUrl =
                URLEncoder.encode(to.webUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);
            Repository repo = null;
            if (!Files.exists(dir)) {
                log.info("Cloning " + to.name());
                Files.createDirectories(dir);
                repo = Repository.clone(to.url(), dir);
            } else {
                log.info("Found existing scratch directory for " + to.name());
                repo = Repository.get(dir).orElseThrow(() -> {
                        return new RuntimeException("Repository in " + dir + " has vanished");
                });
            }

            repo.fetchAll();
            var originToBranch = new Branch("origin/" + toBranch.name());

            // Check if pull request already created
            var title = "Cannot automatically merge " + from.name() + ":" + fromBranch.name();
            var marker = "<!-- MERGE CONFLICTS -->";
            for (var pr : to.pullRequests()) {
                if (pr.title().equals(title) &&
                    pr.body().startsWith(marker) &&
                    to.host().currentUser().equals(pr.author())) {
                    var lines = pr.body().split("\n");
                    var head = new Hash(lines[1].substring(5, 45));
                    if (repo.contains(originToBranch, head)) {
                        log.info("Closing resolved merge conflict PR " + pr.id());
                        pr.addComment("Merge conflicts have been resolved, closing this PR");
                        pr.setState(PullRequest.State.CLOSED);
                    } else {
                        log.info("Outstanding unresolved merge already present");
                        return;
                    }
                }
            }

            log.info("Fetching " + from.name() + ":" + fromBranch.name());
            var fetchHead = repo.fetch(from.url(), fromBranch.name());
            var head = repo.resolve(toBranch.name()).orElseThrow(() ->
                    new IOException("Could not resolve branch " + toBranch.name())
            );
            if (repo.contains(originToBranch, fetchHead)) {
                log.info("Nothing to merge");
                return;
            }

            var isAncestor = repo.isAncestor(head, fetchHead);

            log.info("Trying to merge into " + toBranch.name());
            repo.checkout(toBranch, false);
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
                repo.push(toBranch, to.url().toString(), false);
            } else {
                log.info("Got error: " + error.getMessage());
                log.info("Aborting unsuccesful merge");
                repo.abortMerge();

                log.info("Creating pull request to alert");
                var mergeBase = repo.mergeBase(fetchHead, head);
                var commits = repo.commits(mergeBase.hex() + ".." + fetchHead.hex(), true).asList();

                var message = new ArrayList<String>();
                message.add(marker);
                message.add("<!-- " + fetchHead.hex() + " -->");
                message.add("The following commits from `" + from.name() + ":" + fromBranch.name() +
                            "` could *not* be automatically merged into `" + toBranch.name() + "`:");
                message.add("");
                for (var commit : commits) {
                    message.add("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
                }
                message.add("");
                message.add("To manually resolve these merge conflicts, please create a personal fork of " +
                            to.webUrl() + " and execute the following commands:");
                message.add("");
                message.add("```bash");
                message.add("$ git checkout " + toBranch.name());
                message.add("$ git pull " + from.webUrl() + " " + fromBranch.name());
                message.add("```");
                message.add("");
                message.add("When you have resolved the conflicts resulting from the above commands, run:");
                message.add("");
                message.add("```bash");
                message.add("$ git add paths/to/files/with/conflicts");
                message.add("$ git commit -m 'Merge'");
                message.add("```");
                message.add("");
                message.add("Push the resulting merge conflict to your personal fork and " +
                            "create a pull request towards this repository. This pull request " +
                            "will be closed automatically once the pull request with the resolved " +
                            "conflicts has been integrated.");
                var pr = from.createPullRequest(to,
                                                toBranch.name(),
                                                fromBranch.name(),
                                                title,
                                                message);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "MergeBot@(" + from.name() + ":" + fromBranch.name() + "-> "
                            + to.name() + ":" + toBranch.name() + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
