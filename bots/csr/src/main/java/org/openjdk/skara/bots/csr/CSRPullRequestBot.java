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
import org.openjdk.skara.forge.PullRequestPoller;
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
    private final PullRequestPoller poller;

    CSRPullRequestBot(HostedRepository repo, IssueProject project) {
        this.repo = repo;
        this.project = project;
        this.poller = new PullRequestPoller(repo, false, false, false);
    }

    @Override
    public String toString() {
        return "CSRPullRequestBot@" + repo.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var prs = poller.updatedPullRequests();
        var items = prs.stream()
                .map(pr -> (WorkItem) new PullRequestWorkItem(repo, pr.id(), project,
                        e -> poller.retryPullRequest(pr), pr.updatedAt()))
                .toList();
        poller.lastBatchHandled();
        return items;
    }

    @Override
    public String name() {
        return CSRBotFactory.NAME;
    }
}
