/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProjectPoller;
import org.openjdk.skara.issuetracker.IssueProject;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.openjdk.skara.issuetracker.IssueTrackerIssue;

class IssueBot implements Bot {
    private final IssueProject issueProject;
    private final List<HostedRepository> repositories;
    private final IssueProjectPoller poller;

    private final Map<String, PullRequestBot> pullRequestBotMap;
    private final Map<String, List<PRRecord>> issuePRMap;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    IssueBot(IssueProject issueProject, List<HostedRepository> repositories, Map<String, PullRequestBot> pullRequestBotMap,
                    Map<String, List<PRRecord>> issuePRMap) {
        this.issueProject = issueProject;
        this.repositories = repositories;
        this.pullRequestBotMap = pullRequestBotMap;
        this.issuePRMap = issuePRMap;
        // The PullRequestBot will initially evaluate all active PRs so there
        // is no need to look at any issues older than the start time of the bot
        // here. A padding of 10 minutes for the initial query should cover any
        // potential time difference between local and remote, as well as timing
        // issues between the first run of each bot, without the risk of
        // returning excessive amounts of Issues in the first run.
        this.poller = new IssueProjectPoller(issueProject, Duration.ofMinutes(10)) {
            // Query for non-CSR and non-JEP issues in this poller.
            @Override
            protected List<IssueTrackerIssue> queryIssues(IssueProject issueProject, ZonedDateTime updatedAfter) {
                return issueProject.issues(updatedAfter).stream()
                        .filter(issue -> {
                            var issueType = issue.properties().get("issuetype");
                            return issueType != null && !"CSR".equals(issueType.asString());
                        })
                        .toList();
            }
        };
    }

    @Override
    public String toString() {
        return "IssueBot@" + issueProject.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var issues = poller.updatedIssues();
        log.info("Found " + issues.size() + " updated issues(exclude CSR issues)");
        var items = new LinkedList<WorkItem>();
        for (var issue : issues) {
            var prRecords = issuePRMap.get(issue.id());
            if (prRecords == null) {
                continue;
            }
            prRecords.stream()
                    .flatMap(record -> repositories.stream()
                            .filter(r -> r.name().equals(record.repoName()))
                            .map(r -> r.pullRequest(record.prId()))
                    )
                    .filter(Issue::isOpen)
                    // This will mix time stamps from the IssueTracker and the Forge hosting PRs, but it's the
                    // best we can do.
                    .map(pr -> CheckWorkItem.fromIssueBot(pullRequestBotMap.get(pr.repository().name()), pr.id(),
                            e -> poller.retryIssue(issue), issue.updatedAt()))
                    .forEach(items::add);
        }
        poller.lastBatchHandled();
        return items;
    }

    @Override
    public String name() {
        return PullRequestBotFactory.NAME + "-issue";
    }

    List<HostedRepository> repositories() {
        return repositories;
    }

    Map<String, List<PRRecord>> issuePRMap() {
        return issuePRMap;
    }
}
