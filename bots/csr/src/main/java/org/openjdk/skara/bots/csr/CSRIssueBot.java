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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;

/**
 * The CSRIssueBot polls an IssueProject for updated issues of CSR type. When
 * found, IssueWorkItems are created to figure out if any PR needs to be
 * re-evaluated.
 */
public class CSRIssueBot implements Bot {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.csr");

    private final IssueProject issueProject;
    private final List<HostedRepository> repositories;
    // Keeps track of updatedAt timestamps from the previous call to getPeriodicItems,
    // so we can avoid re-evaluating issues that are returned again without any actual
    // update.
    private Map<String, ZonedDateTime> issueUpdatedAt = Map.of();
    // The last found updatedAt from any issue.
    private ZonedDateTime lastUpdatedAt;

    private final List<Issue> retryIssues = new ArrayList<>();

    public CSRIssueBot(IssueProject issueProject, List<HostedRepository> repositories) {
        this.issueProject = issueProject;
        this.repositories = repositories;
    }

    @Override
    public String toString() {
        return "CSRIssueBot@" + issueProject.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        // In the very first round, we just find the last updated issue to
        // initialize lastUpdatedAt. There is no need for reacting to any CSR
        // issue update before that, as the CSRPullRequestBot will go through
        // every open PR at startup anyway.
        if (lastUpdatedAt == null) {
            var lastUpdatedIssue = issueProject.lastUpdatedIssue();
            if (lastUpdatedIssue.isPresent()) {
                Issue issue = lastUpdatedIssue.get();
                lastUpdatedAt = issue.updatedAt();
                issueUpdatedAt = Map.of(issue.id(), issue.updatedAt());
                log.fine("Setting lastUpdatedAt from last updated issue " + issue.id() + " updated at " + lastUpdatedAt);
            } else {
                // If no previous issue was found, initiate lastUpdatedAt to something far
                // enough back so that we are guaranteed to find any new CSR issues going
                // forward.
                lastUpdatedAt = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault());
                log.warning("No CSR issue found, setting lastUpdatedAt to " + lastUpdatedAt);
            }
            return List.of();
        }

        var issues = issueProject.csrIssues(lastUpdatedAt);
        if (!issues.isEmpty()) {
            lastUpdatedAt = issues.stream()
                    .map(Issue::updatedAt)
                    .max(Comparator.naturalOrder())
                    .orElseThrow(() -> new RuntimeException("No updatedAt field found in any Issue"));
        }
        var newIssuesUpdatedAt = issues.stream()
                .collect(Collectors.toMap(Issue::id, Issue::updatedAt));

        var filtered = issues.stream()
                .filter(i -> !issueUpdatedAt.containsKey(i.id()) || i.updatedAt().isAfter(issueUpdatedAt.get(i.id())))
                .toList();

        var withRetries = addRetries(filtered);

        var workItems = withRetries.stream()
                .map(i -> (WorkItem) new IssueWorkItem(this, i, e -> this.retryIssue(i)))
                .toList();

        issueUpdatedAt = newIssuesUpdatedAt;

        return workItems;
    }

    private synchronized void retryIssue(Issue issue) {
        retryIssues.add(issue);
    }

    private synchronized List<Issue> addRetries(List<Issue> issues) {
        if (retryIssues.isEmpty()) {
            return issues;
        } else {
            var retries = retryIssues.stream()
                    .filter(retryIssue -> issues.stream().noneMatch(i -> retryIssue.id().equals(i.id())))
                    .toList();
            retryIssues.clear();
            if (retries.isEmpty()) {
                return issues;
            } else {
                return Stream.concat(issues.stream(), retries.stream()).toList();
            }
        }
    }

    @Override
    public String name() {
        return CSRBotFactory.NAME;
    }

    List<HostedRepository> repositories() {
        return repositories;
    }
}
