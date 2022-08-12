/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.csr;

import java.time.ZonedDateTime;
import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.IssueProject;

import java.util.*;
import java.util.logging.Logger;

/**
 * The CSRPullRequestBot polls all PRs for a specific repository for updates.
 * When found, PullRequestWorkItems are created to re-evaluate CSR state for
 * the PR.
 */
class CSRPullRequestBot implements Bot {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.csr");
    private final HostedRepository repo;
    private final IssueProject project;
    // Keeps track of updatedAt timestamps from the previous call to getPeriodicItems,
    // so we can avoid re-evaluating PRs that are returned again without any actual
    // update. This is needed because timestamp based searches aren't exact enough
    // to avoid sometimes receiving the same items multiple times.
    private Map<String, ZonedDateTime> prsUpdatedAt = new HashMap<>();
    // The last found updateAt in any returned PR. Used for limiting results on the
    // next call to the hosted repo. Should only contain timestamps originating
    // from the remote repo to avoid problems with mismatched clocks.
    private ZonedDateTime lastUpdatedAt;

    CSRPullRequestBot(HostedRepository repo, IssueProject project) {
        this.repo = repo;
        this.project = project;
    }

    @Override
    public String toString() {
        return "CSRPullRequestBot@" + repo.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var items = new ArrayList<WorkItem>();
        log.info("Fetching all open pull requests for " + repo.name());
        Map<String, ZonedDateTime> newPrsUpdatedAt = new HashMap<>();
        // On the first run we have to re-evaluate all open PRs, after that, only
        // looking at PRs that have been updated should be enough.
        var prs = lastUpdatedAt != null ? repo.openPullRequestsAfter(lastUpdatedAt) : repo.pullRequests();
        for (PullRequest pr : prs) {
            newPrsUpdatedAt.put(pr.id(), pr.updatedAt());
            // Update lastUpdatedAt with the last found updatedAt for the next call
            if (lastUpdatedAt == null || pr.updatedAt().isAfter(lastUpdatedAt)) {
                lastUpdatedAt = pr.updatedAt();
            }
            var lastUpdate = prsUpdatedAt.get(pr.id());
            if (lastUpdate != null) {
                if (!pr.updatedAt().isAfter(lastUpdate)) {
                    continue;
                }
            }
            var pullRequestWorkItem = new PullRequestWorkItem(repo, pr.id(), project);
            log.fine("Scheduling: " + pullRequestWorkItem);
            items.add(pullRequestWorkItem);
        }
        prsUpdatedAt = newPrsUpdatedAt;
        return items;
    }

    @Override
    public String name() {
        return CSRBotFactory.NAME;
    }
}
