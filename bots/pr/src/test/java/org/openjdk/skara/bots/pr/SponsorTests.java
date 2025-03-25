/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.vcs.Branch;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class SponsorTests {
    private void runSponsortest(TestInfo testInfo, boolean isAuthor) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id());
            if (isAuthor) {
                censusBuilder.addAuthor(author.forge().currentUser().id());
            }
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var authorEmail = "ta@none.none";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, authorEmail);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.comments().stream()
                            .filter(comment -> comment.body().contains("sponsor"))
                            .filter(comment -> comment.body().contains("your change"))
                            .count();
            assertEquals(1, sponsor);

            // The bot should not have pushed the commit
            var notPushed = pr.comments().stream()
                              .filter(comment -> comment.body().contains("Pushed as commit"))
                              .count();
            assertEquals(0, notPushed);

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.authenticatedUrl(), "master");
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            if (isAuthor) {
                assertEquals("Generated Author 2", headCommit.author().name());
                assertEquals("integrationauthor2@openjdk.org", headCommit.author().email());
            } else {
                assertEquals(authorFullName, headCommit.author().name());
                assertEquals(authorEmail, headCommit.author().email());
            }

            assertEquals("Generated Reviewer 1", headCommit.committer().name());
            assertEquals("integrationreviewer1@openjdk.org", headCommit.committer().email());
            assertTrue(pr.store().labelNames().contains("integrated"));
            assertFalse(pr.store().labelNames().contains("ready"));
            assertFalse(pr.store().labelNames().contains("sponsor"));
        }
    }

    @Test
    void sponsorNonAuthor(TestInfo testInfo) throws IOException {
        runSponsortest(testInfo, false);
    }

    @Test
    void sponsorAuthor(TestInfo testInfo) throws IOException {
        runSponsortest(testInfo, true);
    }

    @Test
    void sponsorNotNeeded(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("does not need sponsoring"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void sponsorNotAllowed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Committers"))
                          .filter(comment -> comment.body().contains("are allowed to sponsor"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void sponsorNotReady(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Reviewer now tries to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("before the integration can be sponsored"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void sponsorAfterChanges(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, "ta@none.none");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Flag it as ready for integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Bot should have replied
            var ready = pr.comments().stream()
                          .filter(comment -> comment.body().contains("now ready to be sponsored"))
                          .filter(comment -> comment.body().contains("at version " + editHash.hex()))
                          .count();
            assertEquals(1, ready);
            assertTrue(pr.store().labelNames().contains("sponsor"));

            // Push another change
            var updateHash = CheckableRepository.appendAndCommit(localRepo, "Yet more stuff", "Append commit", authorFullName, "ta@none.none");
            localRepo.push(updateHash, author.authenticatedUrl(), "edit");

            // The label should have been dropped
            TestBotRunner.runPeriodicItems(mergeBot);
            assertFalse(pr.store().labelNames().contains("sponsor"));

            // Reviewer now tries to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("The PR has been updated since the change"))
                          .count();
            assertEquals(1, error);

            // Flag it as ready for integration again
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertTrue(pr.store().labelNames().contains("sponsor"));

            // It should now be possible to sponsor
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertFalse(pr.store().labelNames().contains("sponsor"));

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);
        }
    }

    @Test
    void autoRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.authenticatedUrl(), "master");

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.comments().stream()
                            .filter(comment -> comment.body().contains("sponsor"))
                            .filter(comment -> comment.body().contains("your change"))
                            .count();
            assertEquals(1, sponsor);

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var prePush = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Going to push as commit"))
                    .filter(comment -> comment.body().contains("commit was automatically rebased without conflicts"))
                    .count();
            assertEquals(1, prePush);
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.authenticatedUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));
        }
    }

    @Test
    void noAutoRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.authenticatedUrl(), "master");

            // Issue a merge command without being a Committer
            pr.addComment("/integrate " + masterHash);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "the target branch is no longer at the requested hash");

            // Now choose the actual hash
            pr.addComment("/integrate " + unrelatedHash);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            assertLastCommentContains(pr, "your sponsor will make the final decision onto which target hash to integrate");

            // Push more unrelated things
            Files.writeString(unrelated, "Hello again");
            localRepo.add(unrelated);
            var unrelatedHash2 = localRepo.commit("Unrelated 2", "X", "x@y.z");
            localRepo.push(unrelatedHash2, author.authenticatedUrl(), "master");

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor " + unrelatedHash);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "head of the target branch is no longer at the requested hash");

            // Use the current hash
            reviewerPr.addComment("/sponsor " + unrelatedHash2);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr, "Pushed as commit");

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.authenticatedUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));
        }
    }

    @Test
    void sponsorAfterFailingCheck(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, "ta@none.none");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Flag it as ready for integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Bot should have replied
            var ready = pr.comments().stream()
                          .filter(comment -> comment.body().contains("now ready to be sponsored"))
                          .filter(comment -> comment.body().contains("at version " + editHash.hex()))
                          .count();
            assertEquals(1, ready);
            assertTrue(pr.store().labelNames().contains("sponsor"));

            // The reviewer now changes their mind
            approvalPr.addReview(Review.Verdict.DISAPPROVED, "No wait, disapproved");

            // The label should have been dropped
            TestBotRunner.runPeriodicItems(mergeBot);
            assertFalse(pr.store().labelNames().contains("sponsor"));

            // Reviewer now tries to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "PR has not yet been marked as ready for integration");

            // Make it ready for integration again
            approvalPr.addReview(Review.Verdict.APPROVED, "Sorry, wrong button");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertTrue(pr.store().labelNames().contains("sponsor"));

            // It should now be possible to sponsor
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertFalse(pr.store().labelNames().contains("sponsor"));

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);
        }
    }

    @Test
    void cannotRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.comments().stream()
                            .filter(comment -> comment.body().contains("sponsor"))
                            .filter(comment -> comment.body().contains("your change"))
                            .count();
            assertEquals(1, sponsor);

            // The bot should not have pushed the commit
            var notPushed = pr.comments().stream()
                              .filter(comment -> comment.body().contains("Pushed as commit"))
                              .count();
            assertEquals(0, notPushed);

            // Push something conflicting to master
            localRepo.checkout(masterHash, true);
            var conflictingHash = CheckableRepository.appendAndCommit(localRepo, "This looks like a conflict");
            localRepo.push(conflictingHash, author.authenticatedUrl(), "master");

            // Trigger a new check run
            pr.setBody(pr.body() + " recheck");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "this pull request can not be integrated");

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");

            // The bot should reply with an error message
            TestBotRunner.runPeriodicItems(mergeBot);
            assertLastCommentContains(pr, "PR has not yet been marked as ready for integration");
        }
    }

    @Test
    void sponsorMergeCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var reviewerId = reviewer.forge().currentUser().id();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewerId)
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("local.git"), author.repositoryType());
            var initialHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            var anotherFile = localRepo.root().resolve("ANOTHER_FILE.txt");
            Files.writeString(anotherFile, "A string\n");
            localRepo.add(anotherFile);
            var masterHash = localRepo.commit("Another commit\n\nReviewed-by: " + reviewerId, "duke", "duke@openjdk.org");
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Create a new branch, new commit and publish it
            var editBranch = localRepo.branch(initialHash, "edit");
            localRepo.checkout(editBranch);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);

            // Prepare to merge edit into master
            localRepo.checkout(new Branch("master"));
            var editToMasterBranch = localRepo.branch(masterHash, "edit->master");
            localRepo.checkout(editToMasterBranch);
            localRepo.merge(editHash);
            var mergeHash = localRepo.commit("Merge edit", "duke", "duke@openjdk.org");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit->master", true);


            var pr = credentials.createPullRequest(author, "master", "edit->master", "Merge edit");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            //System.out.println(pr.comments());
            //for (var entry : pr.checks(pr.headHash()).entrySet()) {
            //    System.out.println(entry.getValue().summary().orElseThrow());
            //}

            // The bot should reply that a sponsor is required
            var sponsor = pr.comments().stream()
                            .filter(comment -> comment.body().contains("sponsor"))
                            .filter(comment -> comment.body().contains("your change"))
                            .count();
            assertEquals(1, sponsor);

            // The bot should not have pushed the commit
            var notPushed = pr.comments().stream()
                              .filter(comment -> comment.body().contains("Pushed as commit"))
                              .count();
            assertEquals(0, notPushed);

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            var targetRepo = Repository.clone(author.authenticatedUrl(), tempFolder.path().resolve("target.git"));
            var masterHead = targetRepo.lookup(new Branch("origin/master")).orElseThrow();
            assertEquals("Merge edit", masterHead.message().get(0));
        }
    }

    @Test
    void sponsorAutoIntegration(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, "ta@none.none");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Flag it as ready for integration automatically
            pr.addComment("/integrate auto");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Bot should have replied
            var replies = pr.comments().stream()
                          .filter(comment -> comment.body().contains("will be automatically integrated"))
                          .count();
            assertEquals(1, replies);

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it, needs two runs to first mark ready, then add /integrate
            TestBotRunner.runPeriodicItems(mergeBot);
            TestBotRunner.runPeriodicItems(mergeBot);

            // Bot should have marked the PR as ready for sponsor
            var ready = pr.comments().stream()
                          .filter(comment -> comment.body().contains("now ready to be sponsored"))
                          .filter(comment -> comment.body().contains("at version " + editHash.hex()))
                          .count();
            assertEquals(1, ready);
            assertTrue(pr.store().labelNames().contains("sponsor"));

            // Reviewer now sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);
        }
    }

    @Test
    void sponsorAutoIntegrationOutOfOrder(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, "ta@none.none");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Flag it as ready for integration automatically
            pr.addComment("/integrate auto");
            // Reviewer now sponsor a bit too early
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Bot should have replied
            var replies = pr.comments().stream()
                    .filter(comment -> comment.body().contains("will be automatically integrated"))
                    .count();
            assertEquals(1, replies);

            // Bot should have replied that sponsoring wasn't yet possible
            var sponsorReply = pr.comments().stream()
                    .filter(comment -> comment.body().contains("The PR is not yet marked as ready to be sponsored"))
                    .count();
            assertEquals(1, sponsorReply);

            // Bot should have marked the PR as ready for sponsor
            var ready = pr.comments().stream()
                    .filter(comment -> comment.body().contains("now ready to be sponsored"))
                    .filter(comment -> comment.body().contains("at version " + editHash.hex()))
                    .count();
            assertEquals(1, ready);

            // Try sponsor again
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed);
        }
    }


    /**
     * Tests recovery after successfully pushing the commit, but failing to update the PR
     */
    @Test
    void retryAfterInterrupt(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addAuthor(author.forge().currentUser().id());

            var censusRepo = censusBuilder.build();
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusRepo).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var authorEmail = "ta@none.none";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, authorEmail);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.comments().stream()
                    .filter(comment -> comment.body().contains("sponsor"))
                    .filter(comment -> comment.body().contains("your change"))
                    .count();
            assertEquals(1, sponsor);

            // The bot should not have pushed the commit
            var notPushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(0, notPushed);

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Simulate that interruption occurred after prePush comment was added, but before change was
            // pushed
            pr.setState(Issue.State.OPEN);
            pr.removeLabel("integrated");
            pr.addLabel("ready");
            pr.addLabel("rfr");
            pr.addLabel("sponsor");
            var commitComment = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .findAny().orElseThrow();
            pr.removeComment(commitComment);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.store().labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.store().labelNames().contains("rfr"), "rfr label not removed");
            assertFalse(pr.store().labelNames().contains("sponsor"), "sponsor label not removed");
            assertTrue(pr.store().labelNames().contains("integrated"), "integrated label not added");

            // Remove some labels and the commit comment to simulate that last attempt was interrupted
            // after the push was made and the PR was closed
            pr.removeLabel("integrated");
            pr.addLabel("ready");
            pr.addLabel("rfr");
            pr.addLabel("sponsor");
            var commitComment2 = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .findAny().orElseThrow();
            pr.removeComment(commitComment2);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.store().labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.store().labelNames().contains("rfr"), "rfr label not removed");
            assertFalse(pr.store().labelNames().contains("sponsor"), "sponsor label not removed");
            assertTrue(pr.store().labelNames().contains("integrated"), "integrated label not added");

            // Simulate that interruption happened just before the commit comment was added
            var commitComment3 = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .findAny().orElseThrow();
            pr.removeComment(commitComment3);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.store().labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.store().labelNames().contains("rfr"), "rfr label not removed");
            assertFalse(pr.store().labelNames().contains("sponsor"), "sponsor label not removed");
            assertTrue(pr.store().labelNames().contains("integrated"), "integrated label not added");

            // Add another command and verify that no further action is taken
            pr.addComment("/integrate");
            var numComments = pr.comments().size();

            TestBotRunner.runPeriodicItems(mergeBot);

            assertTrue(pr.comments().getLast().body()
                    .contains("can only be used in open pull requests"));
        }
    }

    @Test
    void sponsorWithAmendingCommitMessage(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer1 = credentials.getHostedRepository();
            var reviewer2 = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer1.forge().currentUser().id())
                    .addReviewer(reviewer2.forge().currentUser().id())
                    .addAuthor(author.forge().currentUser().id());

            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).useStaleReviews(false).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var authorFullName = author.forge().currentUser().fullName();
            var authorEmail = "ta@none.none";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "This is a new line", "Append commit", authorFullName, authorEmail);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as reviewer2
            var approval2Pr = reviewer2.pullRequest(pr.id());
            approval2Pr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Make a change with a corresponding PR
            var updateHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(updateHash, author.authenticatedUrl(), "refs/heads/edit", true);
            TestBotRunner.runPeriodicItems(mergeBot);

            // Approve it as reviewer1
            var approval1Pr = reviewer1.pullRequest(pr.id());
            approval1Pr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.comments().stream()
                    .filter(comment -> comment.body().contains("sponsor"))
                    .filter(comment -> comment.body().contains("your change"))
                    .count();
            assertEquals(1, sponsor);

            // The bot should not have pushed the commit
            var notPushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(0, notPushed);

            // Reviewer now agrees to sponsor
            var reviewer1Pr = reviewer1.pullRequest(pr.id());
            pr.addComment("/summary amendCommitMessage");
            reviewer1Pr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.authenticatedUrl(), "master");
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            assertEquals("Generated Author 3", headCommit.author().name());
            assertEquals("integrationauthor3@openjdk.org", headCommit.author().email());

            // The committer should be the sponsor
            assertEquals("Generated Reviewer 1", headCommit.committer().name());
            assertEquals("integrationreviewer1@openjdk.org", headCommit.committer().email());
            assertTrue(pr.store().labelNames().contains("integrated"));
            assertFalse(pr.store().labelNames().contains("ready"));
            assertFalse(pr.store().labelNames().contains("sponsor"));
        }
    }
}
