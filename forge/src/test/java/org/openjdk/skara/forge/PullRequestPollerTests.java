package org.openjdk.skara.forge;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TestHost;
import org.openjdk.skara.test.TestPullRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PullRequestPollerTests {

    @Test
    void onlyOpen(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, false, true, true);

            // Create closed PR that should never be returned
            var prClosed = credentials.createPullRequest(repo, null, null, "Foo");
            prClosed.setState(Issue.State.CLOSED);
            var prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Create a PR and poll for it
            var pr = credentials.createPullRequest(repo, null, null, "Foo");
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());

            // Poll for it again without calling lastBatchHandled(), it should be returned again
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again after calling lastBatchHandled(), it should not be returned
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add a label and poll again.
            pr.addLabel("foo");
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();
        }
    }

    @Test
    void includeClosed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, true, true, true);

            // Create a and an open closed PR, that should both be returned
            var prClosed = credentials.createPullRequest(repo, null, null, "Foo");
            var prOpen = credentials.createPullRequest(repo, null, null, "Foo");
            prClosed.setState(Issue.State.CLOSED);
            var prs = prPoller.updatedPullRequests();
            assertEquals(2, prs.size());

            // Poll for it again without calling lastBatchHandled(), both should be returned again
            prs = prPoller.updatedPullRequests();
            assertEquals(2, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again after calling lastBatchHandled(), none should not be returned
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add a label to the closed PR and poll again.
            prClosed.addLabel("foo");
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(prClosed.id(), prs.get(0).id());
            prPoller.lastBatchHandled();

            // Add a label to the open PR and poll again.
            prOpen.addLabel("foo");
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(prOpen.id(), prs.get(0).id());
            prPoller.lastBatchHandled();
        }
    }

    /**
     * Tests polling with padding needed, with comments and reviews irrelevant.
     * Uses a closed PR to cover that case in our of the queryPadding tests.
     */
    @Test
    void queryPaddingLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setMinTimeStampUpdateInterval(Duration.ofDays(1));
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, true, false, false);

            // Create a closed PR and poll for it
            var pr = credentials.createPullRequest(repo, "master", "master", "Foo");
            pr.setState(Issue.State.CLOSED);
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add a new label but make sure the updatedAt time was not updated. This should trigger an update.
            var prevUpdatedAt = pr.updatedAt();
            pr.addLabel("foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Add comment. This should not trigger an update
            prevUpdatedAt = pr.updatedAt();
            pr.addComment("foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add review. This should not trigger an update
            prevUpdatedAt = pr.updatedAt();
            pr.addReview(Review.Verdict.APPROVED, "foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();
        }
    }

    /**
     * Tests polling with padding needed and creating/modifying comments
     */
    @Test
    void queryPaddingComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setMinTimeStampUpdateInterval(Duration.ofDays(1));
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, true, true, false);

            // Create a PR and poll for it
            var pr = credentials.createPullRequest(repo, "master", "master", "Foo");
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add a new comment but make sure the updatedAt time was not updated. This should trigger an update.
            var prevUpdatedAt = pr.updatedAt();
            pr.addLabel("foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Add comment. This should trigger an update.
            prevUpdatedAt = pr.updatedAt();
            pr.addComment("foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Update comment. This should trigger an update.
            prevUpdatedAt = pr.updatedAt();
            pr.updateComment(pr.comments().get(0).id(), "bar");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Add review. This should not trigger an update.
            prevUpdatedAt = pr.updatedAt();
            pr.addReview(Review.Verdict.APPROVED, "foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();
        }
    }

    /**
     * Tests polling with padding needed and creating/modifying reviews
     */
    @Test
    void queryPaddingReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setMinTimeStampUpdateInterval(Duration.ofDays(1));
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, true, false, true);

            // Create a PR and poll for it
            var pr = credentials.createPullRequest(repo, "master", "master", "Foo");
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add a new comment but make sure the updatedAt time was not updated. This should trigger an update.
            var prevUpdatedAt = pr.updatedAt();
            pr.addLabel("foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Add comment. This should trigger an update
            prevUpdatedAt = pr.updatedAt();
            pr.addComment("foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Add review. This should not trigger an update
            prevUpdatedAt = pr.updatedAt();
            pr.addReview(Review.Verdict.APPROVED, "foo");
            ((TestPullRequest) pr).setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();
        }
    }

    @Test
    void retries(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, false, true, true);

            // Create PR
            var pr1 = credentials.createPullRequest(repo, null, null, "Foo");
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Create another PR and mark the first PR for retry
            var pr2 = credentials.createPullRequest(repo, null, null, "Foo");
            prPoller.retryPullRequest(pr1);
            prs = prPoller.updatedPullRequests();
            assertEquals(2, prs.size());
            prPoller.lastBatchHandled();

            // Poll again, nothing should not be returned
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Just mark a PR for retry
            prPoller.retryPullRequest(pr2);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());

            // Call again without calling .lastBatchHandled, the retry should be included again
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Mark a PR for retry far in the future, it should not be included
            prPoller.retryPullRequest(pr2, Instant.now().plus(Duration.ofDays(1)));
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Update PR and add it as retry, only one copy should be returned
            pr1.addLabel("foo");
            prPoller.retryPullRequest(pr1);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();
        }
    }

    @Test
    void quarantine(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setCopyPullRequests(true);
            var prPoller = new PullRequestPoller(repo, false, false, false);

            // Create PR
            var pr1 = credentials.createPullRequest(repo, null, null, "Foo");
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            var returnedPr1 = prs.get(0);
            prPoller.lastBatchHandled();

            // Mark it for quarantine far in the future, it should not be returned
            prPoller.quarantinePullRequest(returnedPr1, Instant.now().plus(Duration.ofDays(1)));
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Touch it, it should not be returned
            pr1.addLabel("foo");
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();

            // Change the quarantine time to sometime in the past
            prPoller.quarantinePullRequest(returnedPr1, Instant.now().minus(Duration.ofMinutes(1)));
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());

            // Call again without marking as handled
            prPoller.quarantinePullRequest(pr1, Instant.now().minus(Duration.ofMinutes(1)));
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Quarantine to sometime in the past and touch it
            prPoller.quarantinePullRequest(returnedPr1, Instant.now().minus(Duration.ofMinutes(1)));
            pr1.addLabel("bar");
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            // Check that the returned PR object is updated with the label
            assertTrue(prs.get(0).labelNames().contains("bar"));

            // Add PR both for retry and quarantine in the future, quarantine should win
            prPoller.retryPullRequest(pr1, Instant.now().plus(Duration.ofDays(1)));
            prPoller.quarantinePullRequest(pr1, Instant.now().plus(Duration.ofDays(1)));
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();
        }
    }
}
