/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

class CheckTests {
    @Test
    void simpleCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .censusLink("https://census.com/{{contributor}}-profile")
                                         .seedStorage(seedFolder)
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check succeeded
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should now be successful
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should now be ready
            assertTrue(pr.labelNames().contains("ready"));
            assertTrue(pr.body().contains("https://census.com/integrationreviewer2-profile"));
        }
    }

    @Test
    void whitespaceIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line with a trailing whitespace   ");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should not be flagged as ready for review
            assertFalse(pr.labelNames().contains("rfr"));

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // The PR should not still not be flagged as ready for review
            assertFalse(pr.labelNames().contains("rfr"));

            // Remove the trailing whitespace in a new commit
            editHash = CheckableRepository.replaceAndCommit(localRepo, "A line without a trailing whitespace");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready
            assertTrue(pr.labelNames().contains("ready"));

            // The check should now be successful
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());
        }
    }

    @Test
    void multipleReviews(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var commenter = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addReviewer(commenter.forge().currentUser().id());

            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var authorPr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Let the status bot inspect the PR
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(authorPr.body().contains("Reviewers"));

            // Approve it
            var reviewerPr = reviewer.pullRequest(authorPr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "Reviewers");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it has been approved
            authorPr = author.pullRequest(authorPr.id());
            assertTrue(authorPr.body().contains("Reviewers"));

            // Update the file after approval
            editHash = CheckableRepository.appendAndCommit(localRepo, "Now I've gone and changed it");
            localRepo.push(editHash, author.url(), "edit", true);

            // Make sure that the push registered
            var lastHeadHash = authorPr.headHash();
            var refreshCount = 0;
            do {
                authorPr = author.pullRequest(authorPr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (authorPr.headHash().equals(lastHeadHash));

            // Check that the review is flagged as stale
            TestBotRunner.runPeriodicItems(checkBot);
            authorPr = author.pullRequest(authorPr.id());
            Pattern compilePattern = Pattern.compile(".*Review applies to \\[.*\\]\\(.*\\).*", Pattern.MULTILINE | Pattern.DOTALL);
            assertTrue(compilePattern.matcher(authorPr.body()).matches());

            // Now we can approve it again
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it has been approved (once) and is no longer stale
            authorPr = author.pullRequest(authorPr.id());
            assertTrue(authorPr.body().contains("Reviewers"));
            assertEquals(1, authorPr.body().split("Generated Reviewer", -1).length - 1);
            assertTrue(authorPr.reviews().size() >= 1);
            assertFalse(authorPr.body().contains("Note"));

            // Add a review with disapproval
            var commenterPr = commenter.pullRequest(authorPr.id());
            commenterPr.addReview(Review.Verdict.DISAPPROVED, "Disapproved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it still only approved once (but two reviews) and is no longer stale
            authorPr = author.pullRequest(authorPr.id());
            assertTrue(authorPr.body().contains("Reviewers"));
            assertEquals(1, authorPr.body().split("Generated Reviewer", -1).length - 1);
            assertTrue(authorPr.reviews().size() >= 2);
            assertFalse(authorPr.body().contains("Note"));

            // No census link is set
            var reviewerString = "Generated Reviewer 2 (@" + reviewer.forge().currentUser().username() + " - **Reviewer**)";
            assertTrue(authorPr.body().contains(reviewerString));
        }
    }

    @Test
    void selfReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(author.forge().currentUser().id());

            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var authorPr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Let the status bot inspect the PR
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(authorPr.body().contains("Reviewers"));

            // Approve it
            authorPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it has been approved
            authorPr = author.pullRequest(authorPr.id());
            assertTrue(authorPr.body().contains("Reviewers"));

            // Verify that the check failed
            var checks = authorPr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
        }
    }

    @Test
    void updatedContentFailsCheck(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check passed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should now be successful
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should now be ready
            assertTrue(pr.labelNames().contains("rfr"));
            assertTrue(pr.labelNames().contains("ready"));

            var addedHash = CheckableRepository.appendAndCommit(localRepo, "trailing whitespace   ");
            localRepo.push(addedHash, author.url(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR is now neither ready for review nor integration
            assertFalse(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // The check should now be failing
            checks = pr.checks(addedHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
        }
    }

    @Test
    void individualReviewComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            // This test is only relevant on hosts not supporting proper review comment bodies
            assumeTrue(!author.forge().supportsReviewBody());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);
            var comments = pr.comments();
            var commentCount = comments.size();

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // There should now be two additional comments
            comments = pr.comments();
            assertEquals(commentCount + 2, comments.size());
            var comment = comments.get(commentCount);
            assertTrue(comment.body().contains(reviewer.forge().currentUser().username()));
            assertTrue(comment.body().contains("approved"));

            // Drop the review
            approvalPr.addReview(Review.Verdict.NONE, "Unreviewed");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // There should now be yet another comment
            comments = pr.comments();
            assertEquals(commentCount + 3, comments.size());
            comment = comments.get(commentCount + 2);
            assertTrue(comment.body().contains(reviewer.forge().currentUser().username()));
            assertTrue(comment.body().contains("comment"));

            // No changes should not generate additional comments
            TestBotRunner.runPeriodicItems(checkBot);
            comments = pr.comments();
            assertEquals(commentCount + 3, comments.size());
        }
    }

    @Test
    void mergeMessage(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Get all messages up to date
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.url(), "master");

            // Let the bot see the changes
            pr.setBody(pr.body() + "recheck");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var updated = pr.comments().stream()
                            .filter(comment -> comment.body().contains("there had been 1 new commit"))
                            .filter(comment -> comment.body().contains(" * " + unrelatedHash.abbreviate()))
                            .filter(comment -> comment.body().contains("automatic rebasing"))
                            .count();
            assertEquals(1, updated);
        }
    }

    @Test
    void cannotRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Get all messages up to date
            TestBotRunner.runPeriodicItems(mergeBot);
            assertTrue(pr.labelNames().contains("ready"));

            // Push something conflicting to master
            localRepo.checkout(masterHash, true);
            var conflictingHash = CheckableRepository.appendAndCommit(localRepo, "This looks like a conflict");
            localRepo.push(conflictingHash, author.url(), "master");

            // Let the bot see the changes
            pr.setBody(pr.body() + "recheck");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should not yet post the ready for integration message
            var updated = pr.comments().stream()
                            .filter(comment -> comment.body().contains("change now passes all automated"))
                            .count();
            assertEquals(0, updated);

            // The PR should be flagged as outdated
            assertTrue(pr.labelNames().contains("merge-conflict"));
            assertFalse(pr.labelNames().contains("ready"));

            // An instructional message should have been bosted
            var help = pr.comments().stream()
                         .filter(comment -> comment.body().contains("To resolve these merge conflicts"))
                         .count();
            assertEquals(1, help);

            // But it should still pass jcheck
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // Restore the master branch
            localRepo.push(masterHash, author.url(), "master", true);

            // Let the bot see the changes
            pr.setBody(pr.body() + "recheck");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should now post an integration message
            updated = pr.comments().stream()
                        .filter(comment -> comment.body().contains("change now passes all *automated*"))
                        .count();
            assertEquals(1, updated);

            // The PR should not be flagged as outdated
            assertFalse(pr.labelNames().contains("merge-conflict"));
            assertTrue(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void blockingLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).blockingCheckLabels(Map.of("block", "Test Blocker")).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");
            pr.addLabel("block");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertTrue(check.summary().orElseThrow().contains("Test Blocker"));

            // The PR should not yet be ready for review
            assertTrue(pr.labelNames().contains("block"));
            assertFalse(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // Check the status again
            pr.removeLabel("block");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void emptyPRBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Another PR");
            pr.setBody("    ");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertTrue(check.summary().orElseThrow().contains(CheckRun.MSG_EMPTY_BODY));

            // Additional errors should be displayed in the body
            var updatedPr = author.pullRequest(pr.id());
            assertTrue(updatedPr.body().contains("## Error"));
            assertTrue(updatedPr.body().contains(CheckRun.MSG_EMPTY_BODY));

            // There should be an indicator of where the pr body should be entered
            assertTrue(updatedPr.body().contains("Replace this text with a description of your pull request"));

            // The PR should not yet be ready for review
            assertFalse(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // Check the status again
            pr.setBody("Here's that body");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // The additional errors should be gone
            updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.body().contains("## Error"));
            assertFalse(updatedPr.body().contains(CheckRun.MSG_EMPTY_BODY));

            // And no new helper marker
            assertFalse(updatedPr.body().contains("Replace this text with a description of your pull request"));
        }
    }

    @Test
    void executableFile(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("executable.exe"), Set.of("reviewers", "executable"), "0.1");
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            Files.writeString(tempFolder.path().resolve("executable.exe"), "Executable file contents", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(tempFolder.path().resolve("executable.exe"), Set.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ));
            localRepo.add(Path.of("executable.exe"));
            var editHash = localRepo.commit("Make it executable", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Another PR");
            pr.setBody("This should now be ready");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertTrue(check.summary().orElseThrow().contains("Executable files are not allowed (file: executable.exe)"));

            // Additional errors should be displayed in the body
            var updatedPr = author.pullRequest(pr.id());
            assertTrue(updatedPr.body().contains("## Error"));
            assertTrue(updatedPr.body().contains("Executable files are not allowed (file: executable.exe)"));

            // The PR should not yet be ready for review
            assertFalse(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // Drop that error
            Files.setPosixFilePermissions(tempFolder.path().resolve("executable.exe"), Set.of(PosixFilePermission.OWNER_READ));
            localRepo.add(Path.of("executable.exe"));
            var updatedHash = localRepo.commit("Make it unexecutable", "duke", "duke@openjdk.org");
            localRepo.push(updatedHash, author.url(), "edit");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));

            // The additional errors should be gone
            updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.body().contains("## Error"));
            assertFalse(updatedPr.body().contains("Executable files are not allowed"));
        }
    }

    @Test
    void missingReadyLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).readyLabels(Set.of("good-to-go")).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that no checks have been run
            var checks = pr.checks(editHash);
            assertEquals(0, checks.size());

            // The PR should not yet be ready for review
            assertFalse(pr.labelNames().contains("rfr"));

            // Check the status again
            pr.addLabel("good-to-go");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
        }
    }

    @Test
    void missingReadyComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).readyComments(Map.of(reviewer.forge().currentUser().username(), Pattern.compile("proceed"))).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that no checks have been run
            var checks = pr.checks(editHash);
            assertEquals(0, checks.size());

            // The PR should not yet be ready for review
            assertFalse(pr.labelNames().contains("rfr"));

            // Check the status again
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("proceed");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labelNames().contains("rfr"));
        }
    }

    @Test
    void issueIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // Add an issue to the title
            pr.setTitle("1234: This is a pull request");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should now be successful
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());
        }
    }

    @Test
    void issueTitleCutOff(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).issueProject(issues).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                    Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Verify that a cut-off title is corrected
            var issue1 = issues.createIssue("My first issue with a very long title that is going to be cut off by the Git Forge provider", List.of("Hello"), Map.of());

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var prBadTitle =  credentials.createPullRequest(author, "master", "edit", issue1.id() + ": My OTHER issue with a very long title that is going to be cut off by …", List.of("…the Git Forge provider"), false);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            assertTrue(prBadTitle.body().contains("Title mismatch between PR and JBS for issue"));

            var prCutOff =  credentials.createPullRequest(author, "master", "edit", issue1.id() + ": My first issue with a very long title that is going to be cut off by …", List.of("…the Git Forge provider", "", "It also has a second line!"), false);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            assertFalse(prCutOff.body().contains("Title mismatch between PR and JBS for issue"));

            // The PR title should contain the full issue title
            assertEquals("1: My first issue with a very long title that is going to be cut off by the Git Forge provider", prCutOff.title());
            // And the body should not contain the issue title
            assertTrue(prCutOff.body().startsWith("It also has a second line!"));

            // Verify that trailing space in issue is ignored
            var issue2 = issues.createIssue("My second issue ending in space   ", List.of("Hello"), Map.of());

            // Make a change with a corresponding PR
            var editHash2 = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash2, author.url(), "edit", true);

            var prCutOff2 =  credentials.createPullRequest(author, "master", "edit", issue2.id() + ": My second issue ending in space", List.of(), false);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR title should contain the issue title without trailing space
            assertEquals("TEST-2: My second issue ending in space", prCutOff2.title());
        }
    }

    @Test
    void issueInSummary(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).issueProject(issues).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var issue1 = issues.createIssue("My first issue", List.of("Hello"), Map.of("issuetype", JSON.of("Bug")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue1.id() + ": This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should be successful
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // And the body should contain the issue title
            assertTrue(pr.body().contains("My first issue"));

            // Change the issue
            var issue2 = issues.createIssue("My second issue", List.of("Body"), Map.of("issuetype", JSON.of("Bug")));
            pr.setTitle(issue2.id() + ": This is a pull request");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The body should contain the updated issue title
            assertFalse(pr.body().contains("My first issue"));
            assertTrue(pr.body().contains("My second issue"));

            // The PR title does not match the issue title
            assertTrue(pr.body().contains("Title mismatch"));
            assertTrue(pr.body().contains("Integration blocker"));

            // Correct it
            pr.setTitle(issue2.id() + " - " + issue2.title());

            // Check the status again - it should now match
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(pr.body().contains("Title mismatch"));
            assertFalse(pr.body().contains("Integration blocker"));

            // Use an invalid issue key
            var issueKey = issue1.id().replace("TEST", "BADPROJECT");
            pr.setTitle(issueKey + ": This is a pull request");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(pr.body().contains("My first issue"));
            assertFalse(pr.body().contains("My second issue"));
            assertTrue(pr.body().contains("does not belong to the `TEST` project"));

            // Now drop the issue key
            issueKey = issue1.id().replace("TEST-", "");
            pr.setTitle(issueKey + ": This is a pull request");

            // The body should now contain the updated issue title
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("My first issue"));
            assertFalse(pr.body().contains("My second issue"));

            // Now enter an invalid issue id
            pr.setTitle("2384848: This is a pull request");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(pr.body().contains("My first issue"));
            assertFalse(pr.body().contains("My second issue"));
            assertTrue(pr.body().contains("Failed to retrieve"));

            // The check should still be successful though
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());
        }
    }

    @Test
    void issueInSummaryExternalUpdate(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).issueProject(issues).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var issue1 = issues.createIssue("My first issue", List.of("Hello"), Map.of("issuetype", JSON.of("Bug")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue1.id() + ": This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should be successful
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // And the body should contain the issue title
            assertTrue(pr.body().contains("My first issue"));

            // Change the issue
            var issue2 = issues.createIssue("My second issue", List.of("Body"), Map.of("issuetype", JSON.of("Bug")));
            pr.setTitle(issue2.id() + ": This is a pull request");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The body should contain the updated issue title
            assertFalse(pr.body().contains("My first issue"));
            assertTrue(pr.body().contains("My second issue"));

            // The PR title does not match the issue title
            assertTrue(pr.body().contains("Title mismatch"));
            assertTrue(pr.body().contains("Integration blocker"));

            // Correct it
            issue2.setTitle("This is a pull request");

            // Check the status again - it should still not match due to caching
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("Title mismatch"));
            assertTrue(pr.body().contains("Integration blocker"));

            // Ensure the check cache expires
            checkBot.scheduleRecheckAt(pr, Instant.now().minus(Duration.ofDays(1)));
            var currentCheck = pr.checks(editHash).get("jcheck");
            assertTrue(currentCheck.metadata().orElseThrow().contains(":"));
            var outdatedMeta = currentCheck.metadata().orElseThrow().replaceAll(":\\d+", ":100");
            var updatedCheck = CheckBuilder.from(currentCheck)
                                           .metadata(outdatedMeta)
                                           .build();
            pr.updateCheck(updatedCheck);

            // Check the status again - now it should be fine
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(pr.body().contains("Title mismatch"));
            assertFalse(pr.body().contains("Integration blocker"));
        }
    }

    @Test
    void issueWithCsr(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                .addAuthor(author.forge().currentUser().id())
                                .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues)
                                            .censusRepo(censusBuilder.build()).enableCsr(true).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Set the version to 17
            localRepo.checkout(localRepo.defaultBranch());
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            var newConf = defaultConf.replace("project=test", "project=test\nversion=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "master", true);

            var mainIssue = issues.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            var csrIssue = issues.createIssue("The csr issue", List.of("csr"), Map.of("issuetype", JSON.of("CSR")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should have one issue
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertFalse(pr.body().contains("The csr issue (**CSR**)"));

            // Require CSR
            mainIssue.addLink(Link.create(csrIssue, "csr for").build());
            pr.addComment("/csr");

            // PR should have two issues
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertTrue(pr.body().contains("The csr issue (**CSR**)"));

            // Set the state of the csr issue to `closed`
            csrIssue.setState(Issue.State.CLOSED);
            // Push a commit to trigger the check which can update the PR body.
            var newHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(newHash, author.url(), "edit", false);

            // PR should have two issues
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertTrue(pr.body().contains("The csr issue (**CSR**)"));
            // The csr issue state don't need to be `open`.
            assertFalse(pr.body().contains("Issue is not open"));
        }
    }

    @Test
    void testJepIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(bot).issueProject(issueProject)
                    .censusRepo(censusBuilder.build()).enableJep(true).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            var jepIssue = issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Submitted"), JEP_NUMBER, JSON.of("123")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should have one issue
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertFalse(pr.body().contains("The jep issue (**JEP**)"));

            // Require jep
            pr.addComment("/jep JEP-123");

            // PR should have two issues
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertTrue(pr.body().contains("The jep issue (**JEP**)"));

            // Set the state of the jep issue to `Targeted`.
            // This step is not necessary, because the JEPBot is not actually running
            // in this test case. But it is good to keep it to show the logic.
            jepIssue.setProperty("status", JSON.object().put("name", "Targeted"));

            // Simulate the JEPBot to remove the `jep` label when the jep issue has been targeted.
            jepIssue.removeLabel("jep");

            // Push a commit to trigger the check which can update the PR body.
            var newHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(newHash, author.url(), "edit", false);

            // PR should have two issues even though the jep issue has been targeted
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertTrue(pr.body().contains("The jep issue (**JEP**)"));

            // Set the state of the jep issue to `Closed`.
            jepIssue.setState(Issue.State.CLOSED);
            jepIssue.setProperty("status", JSON.object().put("name", "Closed"));

            // Push a commit to trigger the check which can update the PR body.
            newHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(newHash, author.url(), "edit", false);

            // PR should have two issues even though the jep issue has been Closed
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(pr.body().contains("### Issues"));
            assertTrue(pr.body().contains("The main issue"));
            assertTrue(pr.body().contains("The jep issue (**JEP**)"));
            // The jep issue state doesn't need to be `open`.
            assertFalse(pr.body().contains("Issue is not open"));
        }
    }

    @Test
    void cancelCheck(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Verify no checks exists
            var checks = pr.checks(editHash);
            assertEquals(0, checks.size());

            // Create a check that is running
            var original = CheckBuilder.create("jcheck", editHash)
                                       .title("jcheck title")
                                       .summary("jcheck summary")
                                       .build();
            pr.createCheck(original);

            // Verify check is created
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var retrieved = checks.get("jcheck");
            assertEquals("jcheck title", retrieved.title().get());
            assertEquals("jcheck summary", retrieved.summary().get());
            assertEquals(CheckStatus.IN_PROGRESS, retrieved.status());

            // Cancel the check
            var cancelled = CheckBuilder.from(retrieved).cancel().build();
            pr.updateCheck(cancelled);
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            retrieved = checks.get("jcheck");
            assertEquals("jcheck title", retrieved.title().get());
            assertEquals("jcheck summary", retrieved.summary().get());
            assertEquals(CheckStatus.CANCELLED, retrieved.status());
        }
    }

    @Test
    void rebaseBeforeCheck(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Enable a new check in the target branch
            localRepo.checkout(masterHash, true);
            CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                     Set.of("author", "reviewers", "whitespace", "issues"), null);
            var headHash = localRepo.resolve("HEAD").orElseThrow();
            localRepo.push(headHash, author.url(), "master", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertTrue(check.summary().orElseThrow().contains("commit message does not reference any issue"));
            assertEquals(CheckStatus.FAILURE, check.status());

            // Adjust the title to conform and check the status again
            pr.setTitle("12345: This is a pull request");
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check passed
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());
        }
    }

    @Test
    void draft(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   "This is a pull request", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check succeeded
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should still not be ready for review as it is a draft
            assertFalse(pr.labelNames().contains("rfr"));
            assertFalse(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void excessiveFailures(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR containing more errors than at least GitHub can handle in a check
            var badContent = "\tline   \n".repeat(200);
            var editHash = CheckableRepository.appendAndCommit(localRepo, badContent);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   "This is a pull request", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
        }
    }

    @Test
    void useJCheckConfFromTargetBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Break the jcheck configuration on the "edit" branch
            var confPath = tempFolder.path().resolve(".jcheck/conf");
            var oldConf = Files.readString(confPath, StandardCharsets.UTF_8);
            Files.writeString(confPath, "Hello there!", StandardCharsets.UTF_8);
            localRepo.add(confPath);
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A change");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   "This is a pull request", true);

            // Check the status - should *not* throw because valid .jcheck/conf from
            // "master" branch should be used
            TestBotRunner.runPeriodicItems(checkBot);
            TestBotRunner.runPeriodicItems(checkBot);
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check succeeded
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());
        }
    }

    @Test
    void noCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "master");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertEquals("- This PR contains no changes", check.summary().orElseThrow());
        }
    }

    @Test
    void redundantCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make the same change with different messages in master and edit
            String identicalChangeBody = "identical change";
            var editHash = CheckableRepository.appendAndCommit(localRepo, identicalChangeBody, "edit message");
            localRepo.push(editHash, author.url(), "edit", true);
            localRepo.checkout(masterHash, true);
            masterHash = CheckableRepository.appendAndCommit(localRepo, identicalChangeBody, "master message");
            localRepo.push(masterHash, author.url(), "master", true);

            // Create PR
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertEquals("- This PR only contains changes already present in the target", check.summary().orElseThrow());
        }
    }

    @Test
    void ignoreStale(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).ignoreStaleReviews(true).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line with");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should be flagged as ready
            assertTrue(pr.labelNames().contains("ready"));
            assertFalse(pr.body().contains("Re-review required"));

            // Add another commit
            editHash = CheckableRepository.replaceAndCommit(localRepo, "Another line");
            localRepo.push(editHash, author.url(), "edit", true);

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should no longer be ready, as the review is stale
            assertFalse(pr.labelNames().contains("ready"));
            assertTrue(pr.labelNames().contains("rfr"));
            assertTrue(pr.body().contains("Re-review required"));
        }
    }

    @Test
    void targetBranchPattern(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build())
                                         .allowedTargetBranches("^(?!master$).*")
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, author.url(), "notmaster", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   "This is a pull request", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertTrue(check.summary().orElseThrow().contains("The branch `master` is not allowed as target branch"));
            assertTrue(check.summary().orElseThrow().contains("notmaster"));

            var anotherPr = credentials.createPullRequest(author, "notmaster", "edit",
                                                   "This is a pull request", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(anotherPr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check passed
            checks = anotherPr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());
        }
    }

    @Test
    void allowedIssueTypes(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build())
                                         .issueProject(issues)
                                         .build();

            var bug = issues.createIssue("My first bug", List.of("A bug"),
                                         Map.of("issuetype", JSON.of("Bug")));
            var backport = issues.createIssue("My first feature", List.of("A feature"),
                                              Map.of("issuetype", JSON.of("Backport")));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var bugHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(bugHash, author.url(), "bug", true);
            var bugPR = credentials.createPullRequest(author, "master", "bug",
                                                      bug.id() + ": My first bug", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check passed
            var bugChecks = bugPR.checks(bugHash);
            assertEquals(1, bugChecks.size());
            var bugCheck = bugChecks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, bugCheck.status());

            // Make a change with a corresponding PR
            var backportHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(backportHash, author.url(), "backport", true);
            var backportPR = credentials.createPullRequest(author, "master", "backport",
                                                           backport.id() + ": My first backport", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);
            assertTrue(backportPR.body().contains(backport.id()));
            assertTrue(backportPR.body().contains("My first feature"));
            assertTrue(backportPR.body().contains("### Integration blocker"));
            assertTrue(backportPR.body().contains("Issue of type `Backport` is not allowed for integrations"));
        }
    }

    @Test
    void expandTitleWithNumericIssueId(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .issueProject(issues)
                                         .build();

            var bug = issues.createIssue("My first bug", List.of("A bug"), Map.of());
            var numericId = bug.id().split("-")[1];

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var bugHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(bugHash, author.url(), "bug", true);
            var bugPR = credentials.createPullRequest(author, "master", "bug", numericId, true);
            assertEquals(numericId, bugPR.title());

            // Check the status (should expand title)
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the title is expanded
            bugPR = author.pullRequest(bugPR.id());
            assertEquals(numericId + ": " + bug.title(), bugPR.title());
        }
    }

    @Test
    void expandTitleWithIssueId(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .issueProject(issues)
                                         .build();

            var bug = issues.createIssue("My first bug", List.of("A bug"), Map.of());

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var bugHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(bugHash, author.url(), "bug", true);
            var bugPR = credentials.createPullRequest(author, "master", "bug", bug.id(), true);
            assertEquals(bug.id(), bugPR.title());

            // Check the status (should expand title)
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the title is expanded
            bugPR = author.pullRequest(bugPR.id());
            var numericId = bug.id().split("-")[1];
            assertEquals(numericId + ": " + bug.title(), bugPR.title());
        }
    }

    @Test
    void expandInvalidTitleWithNumericIssueId(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .issueProject(issues)
                                         .build();

            var bug = issues.createIssue("My first bug", List.of("A bug"), Map.of());
            var numericId = bug.id().split("-")[1];

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                                                     Path.of("appendable.txt"), Set.of("issues"), "0.9");
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var bugHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(bugHash, author.url(), "bug", true);

            var bugPR = credentials.createPullRequest(author, "master", "bug", "bad title", true);

            // Check the status (should not expand title)
            TestBotRunner.runPeriodicItems(checkBot);
            assertEquals("bad title", bugPR.title());
            assertEquals(CheckStatus.FAILURE, bugPR.checks(bugHash).get("jcheck").status());
            assertTrue(bugPR.checks(bugHash).get("jcheck").summary().get().contains("The commit message does not reference any issue"));

            // Now update it
            bugPR.setTitle(numericId);
            bugPR = author.pullRequest(bugPR.id());
            assertEquals(numericId, bugPR.title());

            // Check the status (should expand title)
            TestBotRunner.runPeriodicItems(checkBot);
            assertEquals(CheckStatus.SUCCESS, bugPR.checks(bugHash).get("jcheck").status());

            // Verify that the title is expanded
            bugPR = author.pullRequest(bugPR.id());
            assertEquals(numericId + ": " + bug.title(), bugPR.title());
        }
    }

    @Test
    void removeNonBreakableSpaceInTitle(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                    .repo(author)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .build();

            var bug = issues.createIssue("My first bug", List.of("A bug"), Map.of());

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var bugHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(bugHash, author.url(), "bug", true);
            var bugPR = credentials.createPullRequest(author, "master", "bug",
                    bug.id() + ":\u00A0" + bug.title(), true);

            // Check the status (should expand title)
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the title is expanded
            bugPR = author.pullRequest(bugPR.id());
            var numericId = bug.id().split("-")[1];
            assertEquals(numericId + ": " + bug.title(), bugPR.title());
        }
    }

    @Test
    void overrideJcheckConf(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var confFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var conf = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .confOverrideRepo(conf)
                                         .confOverrideName("jcheck.conf")
                                         .confOverrideRef("jcheck-branch")
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Create a different conf on a different branch
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            var newConf = defaultConf.replace("reviewers=1", "reviewers=0");
            Files.writeString(localRepo.root().resolve("jcheck.conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve("jcheck.conf"));
            var confHash = localRepo.commit("Separate conf", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "jcheck-branch", true);
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var testHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(testHash, author.url(), "test", true);
            var pr = credentials.createPullRequest(author, "master", "test", "This is a PR");

            // Check the status (should become ready immediately as reviewercount is overridden to 0)
            TestBotRunner.runPeriodicItems(checkBot);
            assertEquals(Set.of("rfr", "ready"), new HashSet<>(pr.labelNames()));
        }
    }

    @Test
    void overrideNonexistingJcheckConf(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var confFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var conf = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .confOverrideRepo(conf)
                                         .confOverrideName("jcheck.conf")
                                         .confOverrideRef("jcheck-branch")
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Create a different conf on a different branch
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            var newConf = defaultConf.replace("reviewers=1", "reviewers=0");
            Files.writeString(localRepo.root().resolve("jcheck.conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve("jcheck.conf"));
            var confHash = localRepo.commit("Separate conf", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "jcheck-branch", true);
            localRepo.checkout(masterHash, true);

            // Remove the default one
            localRepo.remove(localRepo.root().resolve(".jcheck/conf"));
            var newMasterHash = localRepo.commit("No more conf", "duke", "duke@openjdk.org");
            localRepo.push(newMasterHash, author.url(), "master");

            // Make a change with a corresponding PR
            var testHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(testHash, author.url(), "test", true);
            var pr = credentials.createPullRequest(author, "master", "test", "This is a PR");

            // Check the status (should become ready immediately as reviewercount is overridden to 0)
            TestBotRunner.runPeriodicItems(checkBot);
            assertEquals(Set.of("rfr", "ready"), new HashSet<>(pr.labelNames()));
        }
    }

    @Test
    void differentAuthors(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var committer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(committer.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(reviewer).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR with an empty e-mail
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Content", "A commit", "A Random User", "a.random.user@foo.com");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve the PR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should respond with an integration message and a warning about different authors
            pr = author.pullRequest(pr.id());
            var comments = pr.comments();
            var numComments = comments.size();
            var lastComment = comments.get(comments.size() - 1).body();
            assertTrue(lastComment.contains("This change now passes all *automated* pre-integration checks."));
            var nextToLastComment = comments.get(comments.size() - 2).body();
            assertTrue(nextToLastComment.contains("the full name on your profile does not match the author name"));

            // Run the bot again, should not result in any new comments
            TestBotRunner.runPeriodicItems(mergeBot);
            pr = author.pullRequest(pr.id());
            assertEquals(numComments, pr.comments().size());
        }
    }

    @Test
    void testBackportCsr(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var botRepo = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder().repo(botRepo)
                    .censusRepo(censusBuilder.build()).issueProject(issueProject).build();

            var issue = issueProject.createIssue("This is the primary issue", List.of(), Map.of());
            issue.setState(Issue.State.CLOSED);
            issue.setProperty("issuetype", JSON.of("Bug"));
            issue.setProperty("fixVersions", JSON.array().add("18"));

            var csr = issueProject.createIssue("This is the primary CSR", List.of(), Map.of());
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setProperty("fixVersions", JSON.array().add("18"));
            issue.addLink(Link.create(csr, "csr for").build());

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Push a commit to the jdk18 branch
            var jdk18Branch = localRepo.branch(masterHash, "jdk18");
            localRepo.checkout(jdk18Branch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a_new_file");
            localRepo.add(newFile);
            var issueNumber = issue.id().split("-")[1];
            var commitMessage = issueNumber + ": This is the primary issue\n\nReviewed-by: integrationreviewer2";
            var commitHash = localRepo.commit(commitMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(commitHash, author.url(), "jdk18", true);

            // "backport" the commit to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "a_new_file");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + commitHash);

            // Remove `version=0.1` from `.jcheck/conf`, set the version as null
            localRepo.checkout(localRepo.defaultBranch());
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            var newConf = defaultConf.replace("version=0.1", "");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set version as null", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "master", true);
            // Run bot. The bot won't get a CSR.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The PR should have primary issue and shouldn't have primary CSR.
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));

            // Add `version=bla` to `.jcheck/conf`, set the version as a wrong value
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            newConf = defaultConf.replace("project=test", "project=test\nversion=bla");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as a wrong value", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "master", true);
            // Run bot. The bot won't get a CSR.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The PR should have primary issue and shouldn't have primary CSR.
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));

            // Set the `version` in `.jcheck/conf` as 17 which is an available version.
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            newConf = defaultConf.replace("version=bla", "version=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "master", true);
            // Run bot. The primary CSR doesn't have the fix version `17`, so the bot won't get a CSR.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The PR should have primary issue and shouldn't have primary CSR.
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));

            // Set the fix versions of the primary CSR to 17 and 18.
            csr.setProperty("fixVersions", JSON.array().add("17").add("18"));
            // Run bot. The primary CSR has the fix version `17`, so it would be used.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The bot should have primary issue and primary CSR
            assumeTrue(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assumeTrue(pr.body().contains(csr.id()));
            assumeTrue(pr.body().contains(csr.title() + " (**CSR**)"));

            // Revert the fix versions of the primary CSR to 18.
            csr.setProperty("fixVersions", JSON.array().add("18"));
            // Create a backport issue whose fix version is 17
            var backportIssue = issueProject.createIssue("This is the backport issue", List.of(), Map.of());
            backportIssue.setProperty("issuetype", JSON.of("Backport"));
            backportIssue.setProperty("fixVersions", JSON.array().add("17"));
            backportIssue.setState(Issue.State.OPEN);
            issue.addLink(Link.create(backportIssue, "backported by").build());
            // Run bot. The bot can find a backport issue but can't find a backport CSR.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The bot should have primary issue and shouldn't have primary CSR.
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));
            assertFalse(pr.body().contains(backportIssue.id()));
            assertFalse(pr.body().contains(backportIssue.title()));

            // Create a backport CSR whose fix version is 17.
            var backportCsr = issueProject.createIssue("This is the backport CSR", List.of(), Map.of());
            backportCsr.setProperty("issuetype", JSON.of("CSR"));
            backportCsr.setProperty("fixVersions", JSON.array().add("17"));
            backportCsr.setState(Issue.State.OPEN);
            backportIssue.addLink(Link.create(backportCsr, "csr for").build());
            // Run bot. The bot can find a backport issue and a backport CSR.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The bot should have primary issue and backport CSR.
            assertTrue(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assumeTrue(pr.body().contains(backportCsr.id()));
            assumeTrue(pr.body().contains(backportCsr.title() + " (**CSR**)"));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));
            assertFalse(pr.body().contains(backportIssue.id()));
            assertFalse(pr.body().contains(backportIssue.title()));

            // Now we have a primary issue, a primary CSR, a backport issue, a backport CSR.
            // Set the backport CSR to have multiple fix versions, included 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("11").add("8"));
            // Set the `version` in `.jcheck/conf` as 11.
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            newConf = defaultConf.replace("version=17", "version=11");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 11", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "master", true);
            // Run bot.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The PR should have primary issue and backport CSR.
            assertTrue(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assumeTrue(pr.body().contains(backportCsr.id()));
            assumeTrue(pr.body().contains(backportCsr.title() + " (**CSR**)"));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));
            assertFalse(pr.body().contains(backportIssue.id()));
            assertFalse(pr.body().contains(backportIssue.title()));

            // Set the backport CSR to have multiple fix versions, excluded 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("8"));
            // Run bot.
            pr.addComment("/summary\n" + commitMessage);
            TestBotRunner.runPeriodicItems(bot);
            // The bot should have primary issue and shouldn't have CSR.
            assertTrue(pr.body().contains("### Issue"));
            assertFalse(pr.body().contains("### Issues"));
            assumeTrue(pr.body().contains(issue.id()));
            assumeTrue(pr.body().contains(issue.title()));
            assertFalse(pr.body().contains(csr.id()));
            assertFalse(pr.body().contains(csr.title()));
            assertFalse(pr.body().contains(backportIssue.id()));
            assertFalse(pr.body().contains(backportIssue.title()));
            assertFalse(pr.body().contains(backportCsr.id()));
            assertFalse(pr.body().contains(backportCsr.title()));
        }
    }
}
