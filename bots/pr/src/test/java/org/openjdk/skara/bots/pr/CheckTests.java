/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

            // Verify that the check succeeded
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should now be ready for review
            assertTrue(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

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
            assertTrue(pr.labels().contains("ready"));
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
            assertFalse(pr.labels().contains("rfr"));

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
            assertFalse(pr.labels().contains("rfr"));

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
            assertTrue(pr.labels().contains("ready"));

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
            assertTrue(authorPr.body().contains("Review applies to"));

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
    void multipleCommitters(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make two changes with different authors
            CheckableRepository.appendAndCommit(localRepo, "First edit", "Edit by number 1",
                                                "number1", "number1@none.none");
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Second edit", "Edit by number 2",
                                                               "number2", "number2@none.none");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should still be failing
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // The PR should not be flagged as ready for review, as multiple committers is a problem
            assertFalse(pr.labels().contains("rfr"));
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
            assertTrue(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

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
            assertTrue(pr.labels().contains("rfr"));
            assertTrue(pr.labels().contains("ready"));

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
            assertFalse(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

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
            assertTrue(comment.body().contains(reviewer.forge().currentUser().userName()));
            assertTrue(comment.body().contains("approved"));

            // Drop the review
            approvalPr.addReview(Review.Verdict.NONE, "Unreviewed");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // There should now be yet another comment
            comments = pr.comments();
            assertEquals(commentCount + 3, comments.size());
            comment = comments.get(commentCount + 2);
            assertTrue(comment.body().contains(reviewer.forge().currentUser().userName()));
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
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var updated = pr.comments().stream()
                            .filter(comment -> comment.body().contains("there has been 1 commit"))
                            .filter(comment -> comment.body().contains(" * " + unrelatedHash.abbreviate()))
                            .filter(comment -> comment.body().contains("please merge"))
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
            assertTrue(pr.labels().contains("ready"));

            // Push something conflicting to master
            localRepo.checkout(masterHash, true);
            var conflictingHash = CheckableRepository.appendAndCommit(localRepo, "This looks like a conflict");
            localRepo.push(conflictingHash, author.url(), "master");

            // Let the bot see the changes
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should not yet post the ready for integration message
            var updated = pr.comments().stream()
                            .filter(comment -> comment.body().contains("change now passes all automated"))
                            .count();
            assertEquals(0, updated);

            // The PR should be flagged as outdated
            assertTrue(pr.labels().contains("merge-conflict"));
            assertFalse(pr.labels().contains("ready"));

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
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should now post an integration message
            updated = pr.comments().stream()
                        .filter(comment -> comment.body().contains("change now passes all automated"))
                        .count();
            assertEquals(1, updated);

            // The PR should not be flagged as outdated
            assertFalse(pr.labels().contains("merge-conflict"));
            assertTrue(pr.labels().contains("ready"));
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
            assertTrue(pr.labels().contains("block"));
            assertFalse(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

            // Check the status again
            pr.removeLabel("block");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));
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
            assertTrue(check.summary().orElseThrow().contains("The pull request body must not be empty."));

            // Additional errors should be displayed in the body
            var updatedPr = author.pullRequest(pr.id());
            assertTrue(updatedPr.body().contains("## Error"));
            assertTrue(updatedPr.body().contains("The pull request body must not be empty."));

            // There should be an indicator of where the pr body should be entered
            assertTrue(updatedPr.body().contains("Replace this text with a description of your pull request"));

            // The PR should not yet be ready for review
            assertFalse(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

            // Check the status again
            pr.setBody("Here's that body");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

            // The additional errors should be gone
            updatedPr = author.pullRequest(pr.id());
            assertFalse(updatedPr.body().contains("## Error"));
            assertFalse(updatedPr.body().contains("The pull request body must not be empty."));

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
            assertFalse(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

            // Drop that error
            Files.setPosixFilePermissions(tempFolder.path().resolve("executable.exe"), Set.of(PosixFilePermission.OWNER_READ));
            localRepo.add(Path.of("executable.exe"));
            var updatedHash = localRepo.commit("Make it unexecutable", "duke", "duke@openjdk.org");
            localRepo.push(updatedHash, author.url(), "edit");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));

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
            assertFalse(pr.labels().contains("rfr"));

            // Check the status again
            pr.addLabel("good-to-go");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labels().contains("rfr"));
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
            var checkBot = PullRequestBot.newBuilder().repo(author).censusRepo(censusBuilder.build()).readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("proceed"))).build();

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
            assertFalse(pr.labels().contains("rfr"));

            // Check the status again
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("proceed");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.labels().contains("rfr"));
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

            var issue1 = issues.createIssue("My first issue", List.of("Hello"), Map.of());

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
            var issue2 = issues.createIssue("My second issue", List.of("Body"), Map.of());
            pr.setTitle(issue2.id() + ": This is a pull request");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The body should contain the updated issue title
            assertFalse(pr.body().contains("My first issue"));
            assertTrue(pr.body().contains("My second issue"));

            // The PR title does not match the issue title
            assertTrue(pr.body().contains("Title mismatch"));

            // Correct it
            pr.setTitle(issue2.id() + " - " + issue2.title());

            // Check the status again - it should now match
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(pr.body().contains("Title mismatch"));

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
            assertFalse(pr.labels().contains("rfr"));
            assertFalse(pr.labels().contains("ready"));
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
            assertTrue(pr.labels().contains("ready"));
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
            assertFalse(pr.labels().contains("ready"));
            assertTrue(pr.labels().contains("rfr"));
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
                                         .allowedIssueTypes(Set.of("Bug"))
                                         .issueProject(issues)
                                         .build();

            var bug = issues.createIssue("My first bug", List.of("A bug"),
                                         Map.of("issuetype", JSON.of("Bug")));
            var feature = issues.createIssue("My first feature", List.of("A feature"),
                                             Map.of("issuetype", JSON.of("Enhancement")));

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
            var featureHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(featureHash, author.url(), "feature", true);
            var featurePR = credentials.createPullRequest(author, "master", "feature",
                                                          feature.id() + ": My first feature", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed for the feature PR
            var featureChecks = featurePR.checks(featureHash);
            assertEquals(1, featureChecks.size());
            var featureCheck = featureChecks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, featureCheck.status());
            var link = "[" + feature.id() + "](" + feature.webUrl() + ")";
            assertTrue(featureCheck.summary()
                                   .orElseThrow()
                                   .contains("The issue " + link + " is not of the expected type. " +
                                             "The allowed issue types are:\n" +
                                             "   - Bug\n"));
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
}
