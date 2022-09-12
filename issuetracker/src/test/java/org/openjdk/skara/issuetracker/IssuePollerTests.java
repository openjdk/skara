package org.openjdk.skara.issuetracker;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TestHost;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void timeStampPadding(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var testHost = (TestHost) issueProject.issueTracker();
            testHost.setTimeStampQueryPrecision(Duration.ofNanos(2));
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
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();

            // With the extremely short precision, the poller should now be padding
            // the fetch query with the precision duration to avoid fetching issue1
            // again. We can prove that by updating the updatedAt to something after
            // the last updatedAt but before last updatedAt + precision. If the fetch
            // call would return it, then isUpdated should also return true.
            var lastFoundUpdatedAt = issue1.store().lastUpdate();
            issue1.store().setLastUpdate(lastFoundUpdatedAt.plus(Duration.ofNanos(1)));
            issues = issuePoller.updatedIssues();
            assertEquals(0, issues.size());
            issuePoller.lastBatchHandled();

            // Update to something just after the lastUpdate + precision and poll
            // again. Now it should be returned.
            issue1.store().setLastUpdate(lastFoundUpdatedAt.plus(Duration.ofNanos(3)));
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
