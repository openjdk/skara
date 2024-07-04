/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.jcheck.ReviewersConfiguration.BYLAWS_URL;

public class ReviewersTests {
    private static final String REVIEWERS_COMMENT_TEMPLATE = "The total number of required reviews for this PR " +
            "(including the jcheck configuration and the last /reviewers command) is now set to %d (with at least %s).";
    private static final String ZERO_REVIEWER_COMMENT = "The total number of required reviews for this PR " +
            "(including the jcheck configuration and the last /reviewers command) is now set to 0.";

    private static final String REVIEW_PROGRESS_TEMPLATE = "Change must be properly reviewed (%d review%s required, with at least %s)";
    private static final String ZERO_REVIEW_PROGRESS = "Change must be properly reviewed (no review required)";

    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = (TestPullRequest)integrator.pullRequest(pr.id());

            // No arguments
            reviewerPr.addComment("/reviewers");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(reviewerPr,"is the number of required reviewers");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Invalid syntax
            reviewerPr.addComment("/reviewers two");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(reviewerPr,"is the number of required reviewers");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Too many
            reviewerPr.addComment("/reviewers 7001");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Cannot increase the required number of reviewers above 10 (requested: 7001)");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Too few
            reviewerPr.addComment("/reviewers -3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Cannot decrease the required number of reviewers below 0 (requested: -3)");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Unknown role
            reviewerPr.addComment("/reviewers 2 penguins");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Unknown role `penguins` specified");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Set the number
            reviewerPr.addComment("/reviewers 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 0, 1, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));

            // Set 2 of role committers
            reviewerPr.addComment("/reviewers 2 committer");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 1, 0, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 1, 0, 0)));

            // Set 2 of role reviewers
            reviewerPr.addComment("/reviewers 2 reviewer");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 2, 0, 0, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 2, 0, 0, 0)));

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The PR should not yet be considered as ready for review
            var updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.labelNames().contains("ready"));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 2, 0, 0, 0)));

            // Now reduce the number of required reviewers
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The PR should now be considered as ready for review
            updatedPr = author.pullRequest(pr.id());
            assertTrue(updatedPr.labelNames().contains("ready"));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Now request that the lead reviews
            reviewerPr.addComment("/reviewers 1 lead");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(1, 0, 0, 0, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(1, 0, 0, 0, 0)));

            // The PR should no longer be considered as ready for review
            updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.labelNames().contains("ready"));

            // Drop the extra requirement that it should be the lead
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 0, 0, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // The PR should now be considered as ready for review yet again
            updatedPr = author.pullRequest(pr.id());
            assertTrue(updatedPr.labelNames().contains("ready"));
        }
    }

    @Test
    void noIntegration(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = (TestPullRequest) integrator.pullRequest(pr.id());

            // Set the number
            reviewerPr.addComment("/reviewers 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 0, 1, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // It should not be possible to integrate yet
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"pull request has not yet been marked as ready for integration");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));

            // Relax the requirement
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // It should now work fine
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Pushed as commit");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));
        }
    }

    @Test
    void noSponsoring(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = (TestPullRequest)integrator.pullRequest(pr.id());

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Flag it as ready for integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"now ready to be sponsored");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Set the number
            reviewerPr.addComment("/reviewers 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 0, 1, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));

            // It should not be possible to sponsor
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"PR has not yet been marked as ready for integration");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));

            // Relax the requirement
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // It should now work fine
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Pushed as commit");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));
        }
    }

    @Test
    void prAuthorShouldBeAllowedToExecute(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var authorPR = (TestPullRequest)author.pullRequest(pr.id());

            // The author deems that two reviewers are required
            authorPR.addComment("/reviewers 2");

            // The bot should reply with a success message
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, getReviewersExpectedComment(0, 1, 0, 1, 0));
            assertTrue(authorPR.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));
        }
    }

    @Test
    void prAuthorShouldNotBeAllowedToDecrease(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var authorPR = (TestPullRequest)author.pullRequest(pr.id());

            // The author deems that two reviewers are required
            authorPR.addComment("/reviewers 2");

            // The bot should reply with a success message
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, getReviewersExpectedComment(0, 1, 0, 1, 0));
            assertTrue(authorPR.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));
            // The author should not be allowed to decrease even its own /reviewers command
            authorPR.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, "Only [Reviewers](https://openjdk.org/bylaws#reviewer) "
                    + "are allowed to decrease the number of required reviewers.");
            assertTrue(authorPR.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));

            // Reviewer should be allowed to decrease
            var reviewerPr = (TestPullRequest)integrator.pullRequest(pr.id());
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 0, 0, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // The author should not be allowed to lower the role of the reviewers
            authorPR.addComment("/reviewers 1 contributors");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, "Only [Reviewers](https://openjdk.org/bylaws#reviewer) "
                    + "are allowed to lower the role for additional reviewers.");
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));

            // Reviewer should be allowed to lower the role of the reviewers
            reviewerPr.addComment("/reviewers 1 contributors");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, getReviewersExpectedComment(0, 1, 0, 0, 0));
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 0, 0)));
        }
    }

    @Test
    void commandInPRBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request", List.of("/reviewers 2"));

            TestBotRunner.runPeriodicItems(prBot);

            var authorPR = (TestPullRequest)author.pullRequest(pr.id());
            assertLastCommentContains(authorPR, getReviewersExpectedComment(0, 1, 0, 1, 0));
            assertTrue(authorPR.store().body().contains(getReviewersExpectedProgress(0, 1, 0, 1, 0)));
        }
    }

    @Test
    void complexCombinedConfigAndCommand(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Change the jcheck configuration
            var confPath = localRepo.root().resolve(".jcheck/conf");
            var defaultConf = Files.readString(confPath);
            var newConf = defaultConf.replace("reviewers=1", """
                                                    lead=1
                                                    reviewers=1
                                                    committers=1
                                                    authors=1
                                                    contributors=1
                                                    ignore=duke
                                                    """);
            Files.writeString(confPath, newConf);
            localRepo.add(confPath);
            var confHash = localRepo.commit("Change conf", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = (TestPullRequest)integrator.pullRequest(pr.id());

            // test role contributor
            for (int i = 1; i <= 10; i++) {
                var contributorNum = (i < 6) ? 1 : i - 4;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " contributor",
                        getReviewersExpectedComment(1, 1, 1, 1, contributorNum),
                        getReviewersExpectedProgress(1, 1, 1, 1, contributorNum));
            }

            // test role author
            for (int i = 1; i <= 10; i++) {
                var contributorNum = (i < 5) ? 1 : 0;
                var authorNum = (i < 5) ? 1 : i - 3;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " author",
                        getReviewersExpectedComment(1, 1, 1, authorNum, contributorNum),
                        getReviewersExpectedProgress(1, 1, 1, authorNum, contributorNum));
            }

            // test role committer
            for (int i = 1; i <= 10; i++) {
                var contributorNum = (i < 4) ? 1 : 0;
                var authorNum = (i < 5) ? 1 : 0;
                var committerNum = (i < 4) ? 1 : i - 2;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " committer",
                        getReviewersExpectedComment(1, 1, committerNum, authorNum, contributorNum),
                        getReviewersExpectedProgress(1, 1, committerNum, authorNum, contributorNum));
            }

            // test role reviewer
            for (int i = 1; i <= 10; i++) {
                var contributorNum = (i < 3) ? 1 : 0;
                var authorNum = (i < 4) ? 1 : 0;
                var committerNum = (i < 5) ? 1 : 0;
                var reviewerNum = (i < 3) ? 1 : i - 1;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " reviewer",
                        getReviewersExpectedComment(1, reviewerNum, committerNum, authorNum, contributorNum),
                        getReviewersExpectedProgress(1, reviewerNum, committerNum, authorNum, contributorNum));
            }

            // test role lead
            verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers 1 lead",
                    getReviewersExpectedComment(1, 1, 1, 1, 1),
                    getReviewersExpectedProgress(1, 1, 1, 1, 1));
        }
    }

    private void verifyReviewersCommentAndProgress(TestPullRequest reviewerPr, PullRequestBot prBot, String command, String expectedComment, String expectedProgress) throws IOException {
        reviewerPr.addComment(command);
        TestBotRunner.runPeriodicItems(prBot);
        assertLastCommentContains(reviewerPr, expectedComment);
        assertTrue(reviewerPr.store().body().contains(expectedProgress));
    }

    private String getReviewersExpectedComment(int leadNum, int reviewerNum, int committerNum, int authorNum, int contributorNum) {
        return constructFromTemplate(REVIEWERS_COMMENT_TEMPLATE, ZERO_REVIEWER_COMMENT, leadNum, reviewerNum, committerNum, authorNum, contributorNum);
    }

    private String getReviewersExpectedProgress(int leadNum, int reviewerNum, int committerNum, int authorNum, int contributorNum) {
        return constructFromTemplate(REVIEW_PROGRESS_TEMPLATE, ZERO_REVIEW_PROGRESS, leadNum, reviewerNum, committerNum, authorNum, contributorNum);
    }

    private String constructFromTemplate(String template, String zeroTemplate, int leadNum, int reviewerNum, int committerNum, int authorNum, int contributorNum) {
        var totalNum = leadNum + reviewerNum + committerNum + authorNum + contributorNum;
        if (totalNum == 0) {
            return zeroTemplate;
        }
        var requireList = new ArrayList<String>();
        var reviewRequirementMap = new LinkedHashMap<String, Integer>();
        reviewRequirementMap.put("[Lead%s](%s#project-lead)", leadNum);
        reviewRequirementMap.put("[Reviewer%s](%s#reviewer)", reviewerNum);
        reviewRequirementMap.put("[Committer%s](%s#committer)", committerNum);
        reviewRequirementMap.put("[Author%s](%s#author)", authorNum);
        reviewRequirementMap.put("[Contributor%s](%s#contributor)", contributorNum);
        for (var reviewRequirement : reviewRequirementMap.entrySet()) {
            var requirementNum = reviewRequirement.getValue();
            if (requirementNum > 0) {
                requireList.add(requirementNum + " " + String.format(reviewRequirement.getKey(), requirementNum > 1 ? "s" : "", BYLAWS_URL));
            }
        }
        if (template.equals(REVIEW_PROGRESS_TEMPLATE)) {
            return String.format(template, totalNum, totalNum > 1 ? "s" : "", String.join(", ", requireList));
        } else {
            return String.format(template, totalNum, String.join(", ", requireList));
        }
    }

    @Test
    void testZeroReviewer(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Change the jcheck configuration
            var confPath = localRepo.root().resolve(".jcheck/conf");
            var defaultConf = Files.readString(confPath);
            var newConf = defaultConf.replace("reviewers=1", """
                                                    lead=0
                                                    reviewers=0
                                                    committers=0
                                                    authors=0
                                                    contributors=0
                                                    ignore=duke
                                                    """);
            Files.writeString(confPath, newConf);
            localRepo.add(confPath);
            var confHash = localRepo.commit("Change conf", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request", List.of(""));
            var reviewerPr = (TestPullRequest)reviewer.pullRequest(pr.id());

            TestBotRunner.runPeriodicItems(prBot);
            var authorPR = author.pullRequest(pr.id());
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 0, 0, 0, 0)));

            authorPR.addComment("/reviewers 2 reviewer");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 2, 0, 0, 0)));

            reviewerPr.addComment("/reviewers 0");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.store().body().contains(getReviewersExpectedProgress(0, 0, 0, 0, 0)));
        }
    }

    @Test
    void testReviewCommentsAfterApprovedReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = integrator.pullRequest(pr.id());

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            // The pr should contain 'Ready' label
            assertTrue(pr.store().labelNames().contains("ready"));

            // Add a review comment
            reviewerPr.addReview(Review.Verdict.NONE, "Just a comment1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            // The pr should still contain 'Ready' label
            assertTrue(pr.store().labelNames().contains("ready"));

            // Add a review comment
            reviewerPr.addReview(Review.Verdict.NONE, "Just a comment2");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            // The pr should still contain 'Ready' label
            assertTrue(pr.store().labelNames().contains("ready"));

            // Disapprove this pr
            reviewerPr.addReview(Review.Verdict.DISAPPROVED, "Disapproved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            // The pr should not contain 'Ready' label
            assertFalse(pr.store().labelNames().contains("ready"));

            // Add a review comment
            reviewerPr.addReview(Review.Verdict.NONE, "Just a comment3");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            // The pr should still not contain 'Ready' label
            assertFalse(pr.store().labelNames().contains("ready"));
        }
    }
}
