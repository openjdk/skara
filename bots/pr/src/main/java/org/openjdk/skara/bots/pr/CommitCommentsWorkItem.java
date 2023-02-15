/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.time.Duration;
import java.time.ZonedDateTime;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class CommitCommentsWorkItem implements WorkItem {
    private final PullRequestBot bot;
    private final HostedRepository repo;
    private final Set<Integer> excludeCommitCommentsFrom;

    private static final ConcurrentHashMap<String, Boolean> processed = new ConcurrentHashMap<>();
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    CommitCommentsWorkItem(PullRequestBot bot, HostedRepository repo, Set<Integer> excludeCommitCommentsFrom) {
        this.bot = bot;
        this.repo = repo;
        this.excludeCommitCommentsFrom = excludeCommitCommentsFrom;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CommitCommentsWorkItem otherItem)) {
            return true;
        }
        if (!repo.isSame(otherItem.repo)) {
            return true;
        }
        return false;
    }

    private boolean isAncestor(ReadOnlyRepository repo, Hash ancestor, Hash descendant) {
        try {
            return repo.isAncestor(ancestor, descendant);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        log.info("Looking for recent commit comments for repository " + repo.name());

        try {
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            // We are only reading data from this local repo, so no need to make a clone,
            // just use the seed repo directly.
            var seedRepo = hostedRepositoryPool.seedRepository(bot.repo(), false);
            var remoteBranches = bot.repo().branches()
                                           .stream()
                                           .filter(b -> !b.name().startsWith("pr/"))
                                           .toList();
            var localBranches = remoteBranches.stream().map(b -> new Branch(b.name())).collect(Collectors.toList());
            var commitComments = repo.recentCommitComments(seedRepo, excludeCommitCommentsFrom,
                    localBranches, ZonedDateTime.now().minus(Duration.ofDays(4)));
            return commitComments.stream()
                                 .filter(cc -> !processed.containsKey(cc.id()))
                                 .filter(cc -> remoteBranches.stream()
                                                             .anyMatch(b -> isAncestor(seedRepo, cc.commit(), b.hash())))
                                 .map(cc -> {
                                     processed.put(cc.id(), true);
                                     return new CommitCommandWorkItem(bot, cc, e -> processed.remove(cc.id()));
                                 })
                                 .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "CommitCommentsWorkItem@" + repo.name();
    }

    @Override
    public String botName() {
        return PullRequestBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "commit-comments";
    }
}
