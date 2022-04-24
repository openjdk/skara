/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class ReviewersTests {
    private static final String reviewersCommandFinallyOutput = "The total number of required reviews for this PR " +
            "(including the jcheck configuration and the last /reviewers command) is now set to ";

    private static final String reviewProgressTemplate = "Change must be properly reviewed (%d reviews required, with at least %s)";
    private static final String zeroReviewProgress = "Change must be properly reviewed (no reviews required)";

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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = integrator.pullRequest(pr.id());

            // No arguments
            reviewerPr.addComment("/reviewers");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(reviewerPr,"is the number of required reviewers");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Invalid syntax
            reviewerPr.addComment("/reviewers two");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(reviewerPr,"is the number of required reviewers");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Too many
            reviewerPr.addComment("/reviewers 7001");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Cannot increase the required number of reviewers above 10 (requested: 7001)");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Too few
            reviewerPr.addComment("/reviewers -3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Cannot decrease the required number of reviewers below 0 (requested: -3)");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Unknown role
            reviewerPr.addComment("/reviewers 2 penguins");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Unknown role `penguins` specified");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Set the number
            reviewerPr.addComment("/reviewers 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role authors).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));

            // Set 2 of role committers
            reviewerPr.addComment("/reviewers 2 committer");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role committers).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 committer")));

            // Set 2 of role reviewers
            reviewerPr.addComment("/reviewers 2 reviewer");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "2 (with 2 of role reviewers).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "2 reviewers")));

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The PR should not yet be considered as ready for review
            var updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.labelNames().contains("ready"));
            assertTrue(updatedPr.body().contains(String.format(reviewProgressTemplate, 2, "2 reviewers")));

            // Now reduce the number of required reviewers
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The PR should now be considered as ready for review
            updatedPr = author.pullRequest(pr.id());
            assertTrue(updatedPr.labelNames().contains("ready"));
            assertTrue(updatedPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Now request that the lead reviews
            reviewerPr.addComment("/reviewers 1 lead");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "1 (with 1 of role lead).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 lead")));

            // The PR should no longer be considered as ready for review
            updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.labelNames().contains("ready"));

            // Drop the extra requirement that it should be the lead
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "1 (with 1 of role reviewers).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = integrator.pullRequest(pr.id());

            // Set the number
            reviewerPr.addComment("/reviewers 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role authors).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // It should not be possible to integrate yet
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"pull request has not yet been marked as ready for integration");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));

            // Relax the requirement
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // It should now work fine
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Pushed as commit");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = integrator.pullRequest(pr.id());

            // Approve it as another user
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Flag it as ready for integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"now ready to be sponsored");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // Set the number
            reviewerPr.addComment("/reviewers 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role authors).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));

            // It should not be possible to sponsor
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"PR has not yet been marked as ready for integration");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));

            // Relax the requirement
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));

            // It should now work fine
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr,"Pushed as commit");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var authorPR = author.pullRequest(pr.id());

            // The author deems that two reviewers are required
            authorPR.addComment("/reviewers 2");

            // The bot should reply with a success message
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role authors).");
            assertTrue(authorPR.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var authorPR = author.pullRequest(pr.id());

            // The author deems that two reviewers are required
            authorPR.addComment("/reviewers 2");

            // The bot should reply with a success message
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role authors).");
            assertTrue(authorPR.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));
            // The author should not be allowed to decrease even its own /reviewers command
            authorPR.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(authorPR, "Cannot decrease the number of required reviewers");
            assertTrue(authorPR.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));

            // Reviewer should be allowed to decrease
            var reviewerPr = integrator.pullRequest(pr.id());
            reviewerPr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(reviewerPr, reviewersCommandFinallyOutput + "1 (with 1 of role reviewers).");
            assertTrue(reviewerPr.body().contains(String.format(reviewProgressTemplate, 1, "1 reviewer")));
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request", List.of("/reviewers 2"));

            TestBotRunner.runPeriodicItems(prBot);

            var authorPR = author.pullRequest(pr.id());
            assertLastCommentContains(authorPR, reviewersCommandFinallyOutput + "2 (with 1 of role reviewers, 1 of role authors).");
            assertTrue(authorPR.body().contains(String.format(reviewProgressTemplate, 2, "1 reviewer, 1 author")));
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Change the jcheck configuration
            var confPath = localRepo.root().resolve(".jcheck/conf");
            var defaultConf = Files.readString(confPath, StandardCharsets.UTF_8);
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
            localRepo.push(confHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = integrator.pullRequest(pr.id());

            // test role contributor
            for (int i = 1; i <= 10; i++) {
                var totalNum = Math.max(i, 5);
                var contributorNum = (i < 6) ? 1 : i - 4;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " contributor",
                        getReviewersExpectedComment(totalNum, 1, 1, 1, 1, contributorNum),
                        getReviewersExpectedProgress(totalNum, 1, 1, 1, 1, contributorNum));
            }

            // test role author
            for (int i = 1; i <= 10; i++) {
                var totalNum = Math.max(i, 5);
                var contributorNum = (i < 5) ? 1 : 0;
                var authorNum = (i < 5) ? 1 : i - 3;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " author",
                        getReviewersExpectedComment(totalNum, 1, 1, 1, authorNum, contributorNum),
                        getReviewersExpectedProgress(totalNum, 1, 1, 1, authorNum, contributorNum));
            }

            // test role committer
            for (int i = 1; i <= 10; i++) {
                var totalNum = Math.max(i, 5);
                var contributorNum = (i < 4) ? 1 : 0;
                var authorNum = (i < 5) ? 1 : 0;
                var committerNum = (i < 4) ? 1 : i - 2;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " committer",
                        getReviewersExpectedComment(totalNum, 1, 1, committerNum, authorNum, contributorNum),
                        getReviewersExpectedProgress(totalNum, 1, 1, committerNum, authorNum, contributorNum));
            }

            // test role reviewer
            for (int i = 1; i <= 10; i++) {
                var totalNum = Math.max(i, 5);
                var contributorNum = (i < 3) ? 1 : 0;
                var authorNum = (i < 4) ? 1 : 0;
                var committerNum = (i < 5) ? 1 : 0;
                var reviewerNum = (i < 3) ? 1 : i - 1;
                verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers " + i + " reviewer",
                        getReviewersExpectedComment(totalNum, 1, reviewerNum, committerNum, authorNum, contributorNum),
                        getReviewersExpectedProgress(totalNum, 1, reviewerNum, committerNum, authorNum, contributorNum));

            }

            // test role lead
            verifyReviewersCommentAndProgress(reviewerPr, prBot, "/reviewers 1 lead",
                    getReviewersExpectedComment(5, 1, 1, 1, 1, 1),
                    getReviewersExpectedProgress(5, 1, 1, 1, 1, 1));
        }
    }

    private void verifyReviewersCommentAndProgress(PullRequest reviewerPr, PullRequestBot prBot, String command, String expectedComment, String expectedProgress) throws IOException {
        reviewerPr.addComment(command);
        TestBotRunner.runPeriodicItems(prBot);
        assertLastCommentContains(reviewerPr, expectedComment);
        assertTrue(reviewerPr.body().contains(expectedProgress));
    }

    private String getReviewersExpectedComment(int totalNum, int leadNum, int reviewerNum, int committerNum, int authorNum, int contributorNum) {
        StringBuilder builder = new StringBuilder();
        builder.append(reviewersCommandFinallyOutput);
        builder.append(totalNum);
        if (leadNum == 0 && reviewerNum == 0 && committerNum == 0 && authorNum == 0 && contributorNum == 0) {
            builder.append(".");
            return builder.toString();
        }
        builder.append(" (with");
        var list = new ArrayList<String>();
        var map = new LinkedHashMap<String, Integer>();
        map.put("lead", leadNum);
        map.put("reviewers", reviewerNum);
        map.put("committers", committerNum);
        map.put("authors", authorNum);
        map.put("contributors", contributorNum);
        for (var entry : map.entrySet()) {
            if (entry.getValue() > 0) {
                list.add(" " + entry.getValue() + " of role " + entry.getKey());
            }
        }
        builder.append(String.join(",", list));
        builder.append(").");
        return builder.toString();
    }

    private String getReviewersExpectedProgress(int totalNum, int leadNum, int reviewerNum, int committerNum, int authorNum, int contributorNum) {
        var requireList = new ArrayList<String>();
        var reviewRequirementMap = new LinkedHashMap<String, Integer>();
        reviewRequirementMap.put("lead", leadNum);
        reviewRequirementMap.put("reviewer", reviewerNum);
        reviewRequirementMap.put("committer", committerNum);
        reviewRequirementMap.put("author", authorNum);
        reviewRequirementMap.put("contributor", contributorNum);
        for (var reviewRequirement : reviewRequirementMap.entrySet()) {
            var requirementNum = reviewRequirement.getValue();
            if (requirementNum > 0) {
                requireList.add(requirementNum+ " " + reviewRequirement.getKey() + (requirementNum > 1 ? "s" : ""));
            }
        }
        if (totalNum == 0) {
            return zeroReviewProgress;
        } else {
            return String.format(reviewProgressTemplate, totalNum, String.join(", ", requireList));
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Change the jcheck configuration
            var confPath = localRepo.root().resolve(".jcheck/conf");
            var defaultConf = Files.readString(confPath, StandardCharsets.UTF_8);
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
            localRepo.push(confHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request", List.of(""));
            var reviewerPr = reviewer.pullRequest(pr.id());

            TestBotRunner.runPeriodicItems(prBot);
            var authorPR = author.pullRequest(pr.id());
            assertTrue(authorPR.body().contains(zeroReviewProgress));

            authorPR.addComment("/reviewers 2 reviewer");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(authorPR.body().contains(String.format(reviewProgressTemplate, 2, "2 reviewers")));

            reviewerPr.addComment("/reviewers 0");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(reviewerPr.body().contains(zeroReviewProgress));
        }
    }
}
