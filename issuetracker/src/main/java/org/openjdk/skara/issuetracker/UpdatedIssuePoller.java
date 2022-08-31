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
package org.openjdk.skara.issuetracker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A poller to get the updated issues. The user can provide the corresponding functions
 * to get all the updated issues or all the updated CSR issues.
 */
public class UpdatedIssuePoller {
    private final IssueProject issueProject;
    // Keeps track of updatedAt timestamps from the previous call to getUpdatedIssues,
    // so we can avoid re-evaluating issues that are returned again without any actual update.
    private Map<String, ZonedDateTime> issueUpdatedAt = new HashMap<>();
    // The last found updatedAt from any issue.
    private ZonedDateTime lastUpdatedAt;

    public UpdatedIssuePoller(IssueProject issueProject) {
        this.issueProject = issueProject;
    }

    /**
     * A method to get the updated issues. The concrete operation is provided by the user.
     * If you want to get all the updated issues, you can use `getUpdatedIssues(IssueProject::issues)`.
     * If you want to get the updated CSR issues, you can use `getUpdatedIssues(IssueProject::csrIssues)`.
     */
    public List<Issue> getUpdatedIssues(BiFunction<IssueProject, ZonedDateTime, List<Issue>> updatedIssueGetter) {
        var issueList = new ArrayList<Issue>();
        // In the first round, we just find the last updated issue to initialize lastUpdatedAt.
        // There is no need for reacting to any issue update before that,
        // as the UpdatedPullRequestPoller will go through every open PR at startup anyway.
        if (lastUpdatedAt == null) {
            var lastUpdatedIssue = issueProject.lastUpdatedIssue();
            if (lastUpdatedIssue.isPresent()) {
                Issue issue = lastUpdatedIssue.get();
                lastUpdatedAt = issue.updatedAt();
                issueUpdatedAt.put(issue.id(), issue.updatedAt());
            } else {
                // If no previous issue was found, initiate lastUpdatedAt to something far
                // enough back so that we are guaranteed to find any new issues going forward.
                lastUpdatedAt = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault());
            }
            return issueList;
        }

        var newIssuesUpdatedAt = new HashMap<String, ZonedDateTime>();
        var issues = updatedIssueGetter.apply(issueProject, lastUpdatedAt);;
        for (var issue : issues) {
            newIssuesUpdatedAt.put(issue.id(), issue.updatedAt());
            // Update the lastUpdatedAt value with the highest found value for next call
            if (issue.updatedAt().isAfter(lastUpdatedAt)) {
                lastUpdatedAt = issue.updatedAt();
            }
            var lastUpdate = issueUpdatedAt.get(issue.id());
            if (lastUpdate != null) {
                if (!issue.updatedAt().isAfter(lastUpdate)) {
                    continue;
                }
            }
            issueList.add(issue);
        }
        issueUpdatedAt = newIssuesUpdatedAt;
        return issueList;
    }
}
