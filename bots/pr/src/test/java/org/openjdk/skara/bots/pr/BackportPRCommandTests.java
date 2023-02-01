package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class BackportPRCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var targetRepo = credentials.getHostedRepository("targetRepo");
            var targetRepo2 = credentials.getHostedRepository("targetRepo2");
            var seedFolder = tempFolder.path().resolve("seed");

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addReviewer(bot.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .censusRepo(censusBuilder.build())
                    .seedStorage(seedFolder)
                    .forks(Map.of("targetRepo", targetRepo, "targetRepo2", targetRepo2))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.url(), "master", true);


            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = (TestPullRequest) integrator.pullRequest(pr.id());

            // Enable backport for targetRepo on master
            pr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was successfully enabled.");
            assertTrue(pr.store().labelNames().contains("Backport=targetRepo:master"));

            // Enable backport for targetRepo2 on dev, but dev does not exist
            pr.addComment("/backport targetRepo2 dev");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The target branch `dev` does not exist");
            assertFalse(pr.store().labelNames().contains("Backport=targetRepo2:dev"));

            // Enable backport for targetRepo2 on dev
            localRepo.push(masterHash, targetRepo2.url(), "dev", true);
            pr.addComment("/backport targetRepo2 dev");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo2` on branch `dev` was successfully enabled.");
            assertTrue(pr.store().labelNames().contains("Backport=targetRepo2:dev"));

            // disable backport for targetRepo on master
            reviewerPr.addComment("/backport disable targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was successfully disabled.");
            assertFalse(pr.store().labelNames().contains("Backport=targetRepo:master"));

            // disable backport for targetRepo again
            reviewerPr.addComment("/backport disable targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was not enabled, so you can not disable it.");
            assertFalse(pr.store().labelNames().contains("Backport=targetRepo:master"));

            // Enable backport for targetRepo on master as reviewer
            reviewerPr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was successfully enabled.");
            assertTrue(pr.store().labelNames().contains("Backport=targetRepo:master"));

            // Approve this PR
            reviewerPr.addReview(Review.Verdict.APPROVED, "");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("ready"));

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "was successfully created on the branch");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Could **not** automatically backport");

            // Resolve conflict
            localRepo.push(masterHash, targetRepo.url(), "master", true);
            // Use /backport after the pr is integrated
            reviewerPr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "was successfully created on the branch");
        }
    }

    @Test
    void testBackportCommandWhenPrIsClosed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var targetRepo = credentials.getHostedRepository("targetRepo");
            var targetRepo2 = credentials.getHostedRepository("targetRepo2");
            var seedFolder = tempFolder.path().resolve("seed");

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addReviewer(bot.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .censusRepo(censusBuilder.build())
                    .seedStorage(seedFolder)
                    .forks(Map.of("targetRepo", targetRepo, "targetRepo2", targetRepo2))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            //close the pr
            pr.store().setState(Issue.State.CLOSED);
            pr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "`/backport` command can not be used in closed but not integrated PR");
        }
    }
}
