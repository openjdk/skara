/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssuePoller;
import org.openjdk.skara.issuetracker.IssueProject;

/**
 * The CSRIssueBot polls an IssueProject for updated issues of CSR type. When
 * found, IssueWorkItems are created to figure out if any PR needs to be
 * re-evaluated.
 */
public class CSRIssueBot implements Bot {
    private final IssueProject issueProject;
    private final List<HostedRepository> repositories;
    private final IssuePoller poller;

    public CSRIssueBot(IssueProject issueProject, List<HostedRepository> repositories) {
        this.issueProject = issueProject;
        this.repositories = repositories;
        // The CSRPullRequestBot will initially evaluate all active PRs so there
        // is no need to look at any issues older than the start time of the bot
        // here. A padding of 10 minutes for the initial query should cover any
        // potential time difference between local and remote, as well as timing
        // issues between the first run of each bot, without the risk of
        // returning excessive amounts of Issues in the first run.
        this.poller = new IssuePoller(issueProject, Duration.ofMinutes(10)) {
            // Only query for CSR issues in this poller.
            @Override
            protected List<Issue> queryIssues(IssueProject issueProject, ZonedDateTime updatedAfter) {
                return issueProject.csrIssues(updatedAfter);
            }
        };
    }

    @Override
    public String toString() {
        return "CSRIssueBot@" + issueProject.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var issues = poller.updatedIssues();
        var items = issues.stream()
                .map(i -> (WorkItem) new IssueWorkItem(this, i, e -> poller.retryIssue(i)))
                .toList();
        poller.lastBatchHandled();
        return items;
    }

    @Override
    public String name() {
        return CSRBotFactory.NAME;
    }

    List<HostedRepository> repositories() {
        return repositories;
    }
}
