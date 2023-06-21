/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IssueProjectPoller {

    private static final Logger log = Logger.getLogger(IssueProjectPoller.class.getName());

    private final IssueProject issueProject;
    private final Duration timeStampQueryPrecision;
    private final ZonedDateTime initialUpdatedAt;
    private final Map<String, IssueTrackerIssue> retryMap = new HashMap<>();

    record QueryResult(Map<String, IssueTrackerIssue> issues, ZonedDateTime maxUpdatedAt,
                       Instant afterQuery, List<IssueTrackerIssue> result,
                       /*
                        * When enough time has passed since the last time we actually returned
                        * results, it's possible to pad the updatedAt query parameter to avoid
                        * receiving the same issues over and over, only to then filter them out.
                        */
                       boolean paddingPossible) {}
    private QueryResult current;
    private QueryResult prev;

    /**
     * @param issueProject The IssueProject to poll from
     * @param startUpPadding The amount of historic time to include in the
     *                       very first query
     */
    public IssueProjectPoller(IssueProject issueProject, Duration startUpPadding) {
        this.issueProject = issueProject;
        this.timeStampQueryPrecision = issueProject.issueTracker().timeStampQueryPrecision();
        this.initialUpdatedAt = ZonedDateTime.now().minus(startUpPadding);
    }

    public List<IssueTrackerIssue> updatedIssues() {
        var beforeQuery = Instant.now();
        List<IssueTrackerIssue> issues = queryIssues();
        var afterQuery = Instant.now();

        // Convert the query result into a map
        var issuesMap = issues.stream().collect(Collectors.toMap(Issue::id, i -> i));

        // Find the max updatedAt value in the result set. Fall back on the previous
        // value (happens if no results were returned), or the initialUpdatedAt (if
        // no results have been found at all so far).
        var maxUpdatedAt = issues.stream()
                .map(Issue::updatedAt)
                .max(Comparator.naturalOrder())
                .orElseGet(() -> prev != null ? prev.maxUpdatedAt : initialUpdatedAt);

        // Filter the results
        var filtered = issues.stream()
                .filter(this::isUpdated)
                .toList();

        // If nothing was left after filtering, update the paddingPossible state if enough time
        // has passed since last we found something.
        boolean paddingPossible = false;
        if (filtered.isEmpty()) {
            if (prev != null) {
                // The afterQuery value that we save should be the time when we last
                // found something after filtering.
                afterQuery = prev.afterQuery;
                if (prev.afterQuery.isBefore(beforeQuery.minus(timeStampQueryPrecision))) {
                    paddingPossible = true;
                }
            }
        }

        var withRetries = addRetries(filtered);

        // Save the state of the current query results
        current = new QueryResult(issuesMap, maxUpdatedAt, afterQuery, withRetries, paddingPossible);

        log.info("Found " + withRetries.size() + " updated issues for " + issueProject.name());
        return withRetries;
    }

    /**
     * After calling updatedIssues(), this method must be called to acknowledge
     * that all the issues returned have been handled. If not, the previous results will be
     * included in the next call to updatedIssues() again.
     */
    public synchronized void lastBatchHandled() {
        if (current != null) {
            prev = current;
            current = null;
            // Remove any returned PRs from the retry/quarantine sets
            prev.result.forEach(pr -> retryMap.remove(pr.id()));
        }
    }

    public synchronized void retryIssue(IssueTrackerIssue issue) {
        retryMap.put(issue.id(), issue);
    }

    private List<IssueTrackerIssue> queryIssues() {
        ZonedDateTime queryAfter;
        if (prev == null || prev.maxUpdatedAt == null) {
            queryAfter = initialUpdatedAt;
        } else if (prev.paddingPossible) {
            // If we haven't found any actual results for long enough,
            // we can pad on the query precision to avoid fetching the
            // last returned issue over and over.
            queryAfter = prev.maxUpdatedAt.plus(timeStampQueryPrecision);
        } else {
            queryAfter = prev.maxUpdatedAt;
        }
        log.fine("Fetching issues updated after " + queryAfter);
        return queryIssues(issueProject, queryAfter);
    }

    /**
     * Subclasses can override this method to query for specific kinds of issues.
     * @param issueProject IssueProject to run query on
     * @param updatedAfter Timestamp for updatedAt query
     */
    protected List<IssueTrackerIssue> queryIssues(IssueProject issueProject, ZonedDateTime updatedAfter) {
        return issueProject.issues(updatedAfter);
    }

    /**
     * Evaluates if an issue has been updated since the previous query result.
     */
    private boolean isUpdated(IssueTrackerIssue issue) {
        if (prev == null) {
            return true;
        }
        var issuePrev = prev.issues.get(issue.id());
        if (issuePrev == null || issue.updatedAt().isAfter(issuePrev.updatedAt())) {
            return true;
        }
        if (!issuePrev.equals(issue)) {
            return true;
        }
        return false;
    }

    /**
     * Returns a list of all prs with retries added.
     */
    private synchronized List<IssueTrackerIssue> addRetries(List<IssueTrackerIssue> issues) {
        if (retryMap.isEmpty()) {
            return issues;
        } else {
            // Find the retries not already present in the issues list
            var retries = retryMap.values().stream()
                    .filter(retryIssue -> issues.stream().noneMatch(issue -> issue.id().equals(retryIssue.id())))
                    .toList();
            if (retries.isEmpty()) {
                return issues;
            } else {
                return Stream.concat(issues.stream(), retries.stream()).toList();
            }
        }
    }

    // Expose the query results to tests
    QueryResult getCurrentQueryResult() {
        return current;
    }
}
