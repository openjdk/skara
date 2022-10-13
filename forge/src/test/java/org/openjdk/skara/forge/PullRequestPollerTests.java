package org.openjdk.skara.forge;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TestHost;

import static org.junit.jupiter.api.Assertions.*;

public class PullRequestPollerTests {

    @Test
    void onlyOpen(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var prPoller = new PullRequestPoller(repo, false);

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
            var prPoller = new PullRequestPoller(repo, true);

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
            var prPoller = new PullRequestPoller(repo, true);

            // Create a closed PR and poll for it
            var pr = credentials.createPullRequest(repo, "master", "master", "Foo");
            pr.setState(Issue.State.CLOSED);
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            assertFalse(prPoller.getCurrentQueryResult().pullRequests().isEmpty());
            prPoller.lastBatchHandled();

            // Add a new label but make sure the updatedAt time was not updated. This should trigger an update.
            var prevUpdatedAt = pr.updatedAt();
            pr.addLabel("foo");
            pr.store().setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
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
            var prPoller = new PullRequestPoller(repo, true);

            // Create a PR and poll for it
            var pr = credentials.createPullRequest(repo, "master", "master", "Foo");
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Poll for it again
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            assertFalse(prPoller.getCurrentQueryResult().pullRequests().isEmpty());
            prPoller.lastBatchHandled();

            // Add a new comment but make sure the updatedAt time was not updated. This should trigger an update.
            var prevUpdatedAt = pr.updatedAt();
            pr.addLabel("foo");
            pr.store().setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Add comment while keeping updatedAt unchanged. This should trigger an update.
            prevUpdatedAt = pr.updatedAt();
            pr.addComment("foo");
            pr.store().setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Update comment while keeping updatedAt unchanged. This should trigger an update.
            prevUpdatedAt = pr.updatedAt();
            pr.updateComment(pr.comments().get(0).id(), "bar");
            pr.store().setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Add review while keeping updatedAt unchanged. This should trigger an update.
            prevUpdatedAt = pr.updatedAt();
            pr.addReview(Review.Verdict.APPROVED, "foo");
            pr.store().setLastUpdate(prevUpdatedAt);
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();
        }
    }

    @Test
    void retries(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var prPoller = new PullRequestPoller(repo, false);

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
            assertEquals(pr2.id(), prs.get(0).id());

            // Call again without calling .lastBatchHandled, the retry should be included again
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(pr2.id(), prs.get(0).id());
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
            var prPoller = new PullRequestPoller(repo, false);

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

    @Test
    void positivePadding(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var forge = repo.forge();
            ((TestHost) forge).setMinTimeStampUpdateInterval(Duration.ofNanos(1));
            ((TestHost) forge).setTimeStampQueryPrecision(Duration.ofNanos(1));
            ZonedDateTime base = ZonedDateTime.now();
            var prPoller = new PullRequestPoller(repo, false);

            // Create a PR with updatedAt set to 'base', and poll it so lastUpdatedAt is now 'base'
            var pr1 = credentials.createPullRequest(repo, null, null, "Foo");
            pr1.store().setLastUpdate(base);
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            prPoller.lastBatchHandled();

            // Create two more PRs, with updatedAt just before and just after 'base'
            var pr2 = credentials.createPullRequest(repo, null, null, "Foo");
            pr2.store().setLastUpdate(base.minus(Duration.ofNanos(2)));
            var pr3 = credentials.createPullRequest(repo, null, null, "Foo");
            pr3.store().setLastUpdate(base.plus(Duration.ofNanos(2)));
            // The negative padding is not big enough to include pr2 and pr1 has already been returned
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(pr3.id(), prs.get(0).id());
            assertTrue(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr1.id()));
            prPoller.lastBatchHandled();

            // Sleep a minimal amount and query again to trigger positive padding
            Thread.sleep(1);
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            // The query should still return pr3
            assertTrue(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr3.id()));

            // The same should happen again until we call lastBatchHandled()
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            // The query should still return pr3
            assertTrue(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr3.id()));
            prPoller.lastBatchHandled();

            // Now even the query should not include p3, but we should get the new pr4
            var pr4 = credentials.createPullRequest(repo, null, null, "Foo");
            pr4.store().setLastUpdate(base.plus(Duration.ofNanos(4)));
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(pr4.id(), prs.get(0).id());
            assertFalse(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr3.id()));

            // The same should happen again until we call lastBatchHandled()
            prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(pr4.id(), prs.get(0).id());
            assertFalse(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr3.id()));
            prPoller.lastBatchHandled();

            // Since we got a result, positive padding should be disabled again.
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            assertEquals(1, prPoller.getCurrentQueryResult().pullRequests().size());
            assertTrue(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr4.id()));

            // The same should happen again until we call lastBatchHandled()
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            assertEquals(1, prPoller.getCurrentQueryResult().pullRequests().size());
            assertTrue(prPoller.getCurrentQueryResult().pullRequests().containsKey(pr4.id()));
            prPoller.lastBatchHandled();
        }
    }

    /**
     * Tests that an old open PR will not cause subsequent calls to return a younger
     * but still too old closed PR.
     */
    @Test
    void noResurrectClosed(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var prPoller = new PullRequestPoller(repo, true);

            var pr1 = credentials.createPullRequest(repo, null, null, "Foo");
            pr1.setState(Issue.State.CLOSED);
            pr1.store().setLastUpdate(ZonedDateTime.now().minus(Duration.ofDays(10)));

            var pr2 = credentials.createPullRequest(repo, null, null, "Foo2");
            pr2.store().setLastUpdate(ZonedDateTime.now().minus(Duration.ofDays(20)));

            // First run should find the open PR but not the closed one, as it's older than 7 days
            var prs = prPoller.updatedPullRequests();
            assertEquals(1, prs.size());
            assertEquals(pr2.id(), prs.get(0).id());
            prPoller.lastBatchHandled();

            // Second call should not find any PR
            prs = prPoller.updatedPullRequests();
            assertEquals(0, prs.size());
            prPoller.lastBatchHandled();
        }
    }
}
