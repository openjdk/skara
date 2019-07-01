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

import org.openjdk.skara.host.*;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckTests {
    @Test
    void simpleCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // The PR should now be ready for review
            assertTrue(pr.getLabels().contains("rfr"));
            assertFalse(pr.getLabels().contains("ready"));

            // Approve it as another user
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should now be successful
            checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should not be flagged as ready for review, at it is already reviewed
            assertFalse(pr.getLabels().contains("rfr"));
            assertTrue(pr.getLabels().contains("ready"));
        }
    }

    @Test
    void whitespaceIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line with a trailing whitespace   ");
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should not be flagged as ready for review
            assertFalse(pr.getLabels().contains("rfr"));

            // Approve it as another user
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // The PR should not still not be flagged as ready for review
            assertFalse(pr.getLabels().contains("rfr"));

            // Remove the trailing whitespace in a new commit
            editHash = CheckableRepository.replaceAndCommit(localRepo, "A line without a trailing whitespace");
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);

            // Make sure that the push registered
            var lastHeadHash = pr.getHeadHash();
            var refreshCount = 0;
            do {
                pr = author.getPullRequest(pr.getId());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.getHeadHash().equals(lastHeadHash));

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should not be flagged as ready for review, at it is already reviewed
            assertFalse(pr.getLabels().contains("rfr"));

            // The check should now be successful
            checks = pr.getChecks(editHash);
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
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id())
                                           .addReviewer(commenter.host().getCurrentUserDetails().id());

            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var authorPr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Let the status bot inspect the PR
            TestBotRunner.runPeriodicItems(checkBot);
            assertFalse(authorPr.getBody().contains("Approvers"));

            // Approve it
            var reviewerPr = reviewer.getPullRequest(authorPr.getId());
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it has been approved
            authorPr = author.getPullRequest(authorPr.getId());
            assertTrue(authorPr.getBody().contains("Approvers"));

            // Update the file after approval
            editHash = CheckableRepository.appendAndCommit(localRepo, "Now I've gone and changed it");
            localRepo.push(editHash, author.getUrl(), "edit", true);

            // Make sure that the push registered
            var lastHeadHash = authorPr.getHeadHash();
            var refreshCount = 0;
            do {
                authorPr = author.getPullRequest(authorPr.getId());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (authorPr.getHeadHash().equals(lastHeadHash));

            // Check that the review is flagged as stale
            TestBotRunner.runPeriodicItems(checkBot);
            authorPr = author.getPullRequest(authorPr.getId());
            assertTrue(authorPr.getBody().contains("Note"));

            // Now we can approve it again
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it has been approved (once) and is no longer stale
            authorPr = author.getPullRequest(authorPr.getId());
            assertTrue(authorPr.getBody().contains("Approvers"));
            assertEquals(1, authorPr.getBody().split("Generated Reviewer", -1).length - 1);
            assertTrue(authorPr.getReviews().size() >= 1);
            assertFalse(authorPr.getBody().contains("Note"));

            // Add a review with disapproval
            var commenterPr = commenter.getPullRequest(authorPr.getId());
            commenterPr.addReview(Review.Verdict.DISAPPROVED, "Disapproved");
            TestBotRunner.runPeriodicItems(checkBot);

            // Refresh the PR and check that it still only approved once (but two reviews) and is no longer stale
            authorPr = author.getPullRequest(authorPr.getId());
            assertTrue(authorPr.getBody().contains("Approvers"));
            assertEquals(1, authorPr.getBody().split("Generated Reviewer", -1).length - 1);
            assertTrue(authorPr.getReviews().size() >= 2);
            assertFalse(authorPr.getBody().contains("Note"));
        }
    }

    @Test
    void multipleCommitters(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make two changes with different authors
            CheckableRepository.appendAndCommit(localRepo, "First edit", "Edit by number 1",
                                                "number1", "number1@none.none");
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Second edit", "Edit by number 2",
                                                               "number2", "number2@none.none");
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // Approve it as another user
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should still be failing
            checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // The PR should not be flagged as ready for review, as multiple committers is a problem
            assertFalse(pr.getLabels().contains("rfr"));
        }
    }

    @Test
    void updatedContentFailsCheck(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());

            // The PR should now be ready for review
            assertTrue(pr.getLabels().contains("rfr"));
            assertFalse(pr.getLabels().contains("ready"));

            // Approve it as another user
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // The check should now be successful
            checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("jcheck");
            assertEquals(CheckStatus.SUCCESS, check.status());

            // The PR should not be flagged as ready for review, at it is already reviewed
            assertFalse(pr.getLabels().contains("rfr"));
            assertTrue(pr.getLabels().contains("ready"));

            var addedHash = CheckableRepository.appendAndCommit(localRepo, "trailing whitespace   ");
            localRepo.push(addedHash, author.getUrl(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.getHeadHash();
            var refreshCount = 0;
            do {
                pr = author.getPullRequest(pr.getId());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.getHeadHash().equals(lastHeadHash));

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR is now neither ready for review nor integration
            assertFalse(pr.getLabels().contains("rfr"));
            assertFalse(pr.getLabels().contains("ready"));
        }
    }

    @Test
    void individualReviewComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);
            var comments = pr.getComments();
            var commentCount = comments.size();

            // Approve it as another user
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // There should now be two additional comments
            comments = pr.getComments();
            assertEquals(commentCount + 2, comments.size());
            var comment = comments.get(commentCount);
            assertTrue(comment.body().contains(reviewer.host().getCurrentUserDetails().userName()));
            assertTrue(comment.body().contains("approved"));

            // Drop the review
            approvalPr.addReview(Review.Verdict.NONE, "Unreviewed");

            // Check the status again
            TestBotRunner.runPeriodicItems(checkBot);

            // There should now be yet another comment
            comments = pr.getComments();
            assertEquals(commentCount + 3, comments.size());
            comment = comments.get(commentCount + 2);
            assertTrue(comment.body().contains(reviewer.host().getCurrentUserDetails().userName()));
            assertTrue(comment.body().contains("comment"));

            // No changes should not generate additional comments
            TestBotRunner.runPeriodicItems(checkBot);
            comments = pr.getComments();
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
                                           .addCommitter(author.host().getCurrentUserDetails().id())
                                           .addReviewer(integrator.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Get all messages up to date
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.getUrl(), "master");

            // Let the bot see the changes
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var updated = pr.getComments().stream()
                            .filter(comment -> comment.body().contains("there has been 1 commit"))
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
                                           .addCommitter(author.host().getCurrentUserDetails().id())
                                           .addReviewer(integrator.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Get all messages up to date
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something conflicting to master
            localRepo.checkout(masterHash, true);
            var conflictingHash = CheckableRepository.appendAndCommit(localRepo, "This looks like a conflict");
            localRepo.push(conflictingHash, author.getUrl(), "master");

            // Let the bot see the changes
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with that there is a conflict
            var updated = pr.getComments().stream()
                            .filter(comment -> comment.body().contains("there has been 1 commit"))
                            .filter(comment -> comment.body().contains("cannot be rebased automatically"))
                            .count();
            assertEquals(1, updated);

            // The PR should be flagged as outdated
            assertTrue(pr.getLabels().contains("outdated"));

            // Restore the master branch
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Let the bot see the changes
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should no longer detect a conflict
            updated = pr.getComments().stream()
                            .filter(comment -> comment.body().contains("change can now be integrated"))
                            .count();
            assertEquals(1, updated);

            // The PR should not be flagged as outdated
            assertFalse(pr.getLabels().contains("outdated"));
        }
    }

    @Test
    void blockingLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var checkBot = new PullRequestBot(author, censusBuilder.build(), "master", Map.of(), Map.of(),
                                              Map.of("block", "Test Blocker"));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");
            pr.addLabel("block");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify that the check failed
            var checks = pr.getChecks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertTrue(check.summary().orElseThrow().contains("Test Blocker"));

            // The PR should not yet be ready for review
            assertTrue(pr.getLabels().contains("block"));
            assertFalse(pr.getLabels().contains("rfr"));
            assertFalse(pr.getLabels().contains("ready"));

            // Check the status again
            pr.removeLabel("block");
            TestBotRunner.runPeriodicItems(checkBot);

            // The PR should now be ready for review
            assertTrue(pr.getLabels().contains("rfr"));
            assertFalse(pr.getLabels().contains("ready"));
        }
    }
}
