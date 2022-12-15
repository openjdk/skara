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

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TestHost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IssuePollerTests {

    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var issuePoller = new IssuePoller(issueProject, Duration.ZERO);

            // Poll with no Issues in the project
            var issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());

            // Poll again without marking as handled
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();

            // Create issue and poll for it
            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());

            // Poll again without marking as handled
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();

            // Poll again
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();

            // Touch issue and poll again
            issue1.setBody("foo");
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());

            // Poll again without marking as handled
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();

            // Poll again
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();
        }
    }

    @Test
    void startUpPadding(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var issuePoller = new IssuePoller(issueProject, Duration.ofDays(2));

            // Create two issues, one with updatedAt before and one after the startup
            // padding limit.
            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.store().setLastUpdate(ZonedDateTime.now().minus(Duration.ofDays(1)));
            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.store().setLastUpdate(ZonedDateTime.now().minus(Duration.ofDays(3)));

            // First poll should find issue1 but not issue2.
            var issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            assertEquals(issue1.id(), issues.get(0).id());
        }
    }

    @Test
    void timeStampPadding(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var testHost = (TestHost) issueProject.issueTracker();
            var issuePoller = new IssuePoller(issueProject, Duration.ZERO);

            // Create issue and poll for it
            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            var issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();

            // Poll again
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();

            // Touch issue and poll again
            issue1.setBody("foo");
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();

            // Poll again
            // Sleep to make it more likely that this and the previous calls to
            // updatedIssues are far enough apart to trigger padding.
            Thread.sleep(1);
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            // The query should still return the issue
            assertEquals(1, issuePoller.getCurrentQueryResult().issues().size());

            // The same should happen again until we call lastBatchHandled()
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            assertEquals(1, issuePoller.getCurrentQueryResult().issues().size());
            issuePoller.lastBatchHandled();

            // With padding triggered, no issues should be returned even at the query
            // level.
            var lastFoundUpdatedAt = issue1.store().lastUpdate();
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            assertTrue(issuePoller.getCurrentQueryResult().issues().isEmpty(),
                    "Nothing should have been returned by the query but contained: "
                            + issuePoller.getCurrentQueryResult().issues());

            // The same should happen again until we call lastBatchHandled()
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            assertTrue(issuePoller.getCurrentQueryResult().issues().isEmpty(),
                    "Nothing should have been returned by the query but contained: "
                            + issuePoller.getCurrentQueryResult().issues());
            issuePoller.lastBatchHandled();

            // Update to something just after the lastUpdate + precision and poll
            // again. Now it should be returned.
            issue1.store().setLastUpdate(lastFoundUpdatedAt.plus(Duration.ofNanos(3)));
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());

            // The same should happen again until we call lastBatchHandled()
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();
        }
    }

    @Test
    void retries(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var issuePoller = new IssuePoller(issueProject, Duration.ZERO);

            // Create issue
            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            var issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();

            // Create another PR and mark the first PR for retry
            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issuePoller.retryIssue(issue1);
            issues = issuePoller.updatedIssues();
            assertEquals(2, issues.size());
            issuePoller.lastBatchHandled();

            // Poll again, nothing should not be returned
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();

            // Just mark a PR for retry
            issuePoller.retryIssue(issue2);
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());

            // Call again without calling .lastBatchHandled, the retry should be included again
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();

            // Update PR and add it as retry, only one copy should be returned
            issue1.addLabel("foo");
            issuePoller.retryIssue(issue1);
            issues = issuePoller.updatedIssues();
            assertEquals(1, issues.size());
            issuePoller.lastBatchHandled();
        }
    }
}
