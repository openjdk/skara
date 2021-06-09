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
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class IntegrateTests {
    @Test
    void simpleMerge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should reply with integration message
            TestBotRunner.runPeriodicItems(mergeBot);
            var integrateComments = pr.comments()
                                      .stream()
                                      .filter(c -> c.body().contains("To integrate this PR with the above commit message to the `master` branch"))
                                      .filter(c -> c.body().contains("If you prefer to avoid any potential automatic rebasing"))
                                      .count();
            assertEquals(1, integrateComments);

            // Attempt a merge (the bot should only process the first one)
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // Author and committer should be the same
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
            assertTrue(pr.labelNames().contains("integrated"));

            // Ready label should have been removed
            assertFalse(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void reviewersRetained(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var committer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addCommitter(committer.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Review it twice
            var integratorPr = integrator.pullRequest(pr.id());
            var committerPr = committer.pullRequest(pr.id());
            integratorPr.addReview(Review.Verdict.APPROVED, "Approved");
            committerPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Attempt a merge
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));
            var headCommit = pushedRepo.commits("HEAD").asList().get(0);
            assertTrue(String.join("", headCommit.message())
                             .matches(".*Reviewed-by: integrationreviewer3, integrationcommitter2$"),
                       String.join("", headCommit.message()));
        }
    }

    @Test
    void notChecked(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Attempt a merge, but point the check at some other commit
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot, item -> {
                if (item instanceof CheckWorkItem) {
                    var newCheck = CheckBuilder.create("jcheck", masterHash).build();
                    pr.updateCheck(newCheck);
                }
            });

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("integration request cannot be fulfilled at this time"))
                          .filter(comment -> comment.body().contains("status check"))
                          .filter(comment -> comment.body().contains("has not been performed on commit"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void notReviewed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository - but without any checks enabled
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of(), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Now enable checks
            localRepo.checkout(masterHash, true);
            CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                     Set.of("author", "reviewers", "whitespace"), null);
            var updatedHash = localRepo.resolve("HEAD").orElseThrow();
            localRepo.push(updatedHash, author.url(), "master", true);

            // Attempt a merge
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "PR has not yet been marked as ready for integration");
        }
    }

    @Test
    void failedCheck(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "trailing whitespace   ");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Attempt a merge
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("integration request cannot be fulfilled at this time"))
                          .filter(comment -> comment.body().contains("status check"))
                          .filter(comment -> comment.body().contains("did not complete successfully"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void outdatedCheck(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Flag it as checked
            var check = CheckBuilder.create("testcheck", editHash);
            pr.createCheck(check.build());
            check.complete(true);
            pr.updateCheck(check.build());

            // Now push another change
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "Yet another line");
            localRepo.push(updatedHash, author.url(), "edit", true);

            // Attempt a merge, but point the check at some other commit
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot, item -> {
                if (item instanceof CheckWorkItem) {
                    var newCheck = CheckBuilder.create("jcheck", masterHash).build();
                    pr.updateCheck(newCheck);
                }
            });

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("integration request cannot be fulfilled at this time"))
                          .filter(comment -> comment.body().contains("status check"))
                          .filter(comment -> comment.body().contains("has not been performed on commit"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void mergeNotification(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it (a few times)
            TestBotRunner.runPeriodicItems(mergeBot);
            TestBotRunner.runPeriodicItems(mergeBot);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an instructional message (and only one)
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("change now passes all *automated*"))
                           .filter(comment -> comment.body().contains("Reviewed-by: integrationreviewer3"))
                           .count();
            assertEquals(1, pushed);

            // Ensure that the bot doesn't pick up on commands in the instructional message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(0, error);

            // Drop the approval
            approvalPr.addReview(Review.Verdict.DISAPPROVED, "Disapproved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The instructional message should have been updated
            pushed = pr.comments().stream()
                       .filter(comment -> comment.body().contains("no longer ready for integration"))
                       .count();
            assertEquals(1, pushed);

            // Restore the approval
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The instructional message should have been updated
            pushed = pr.comments().stream()
                       .filter(comment -> comment.body().contains("change now passes all *automated*"))
                       .filter(comment -> comment.body().contains("Reviewed-by: integrationreviewer3"))
                       .count();
            assertEquals(1, pushed);

            // Approve it as yet another user
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The instructional message should have been updated
            pushed = pr.comments().stream()
                       .filter(comment -> comment.body().contains("change now passes all *automated*"))
                       .filter(comment -> comment.body().contains("Reviewed-by: integrationreviewer3, integrationreviewer2"))
                       .count();
            assertEquals(1, pushed);
        }
    }

    @Test
    void invalidCommandAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var external = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue a merge command not as the PR author
            var externalPr = external.pullRequest(pr.id());
            externalPr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void invalidCommandSponsor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var external = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(external.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Mark it as ready for integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command not as the PR author
            var externalPr = external.pullRequest(pr.id());
            externalPr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .filter(comment -> comment.body().contains("did you mean to"))
                          .filter(comment -> comment.body().contains("`/sponsor`"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void autoRebase(TestInfo testInfo) throws IOException {
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

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.url(), "master");

            // Attempt a merge (the bot should only process the first one)
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            pr.addComment("/integrate");
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
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));
        }
    }

    @Test
    void retryOnFailure(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var censusFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var censusRepo = censusBuilder.build();
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusRepo).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Break the census to cause an exception
            var localCensus = Repository.materialize(censusFolder.path(), censusRepo.url(), "+master:current_census");
            var currentCensusHash = localCensus.resolve("current_census").orElseThrow();
            Files.writeString(censusFolder.path().resolve("contributors.xml"), "This is not xml", StandardCharsets.UTF_8);
            localCensus.add(censusFolder.path().resolve("contributors.xml"));
            var badCensusHash = localCensus.commit("Bad census update", "duke", "duke@openjdk.org");
            localCensus.push(badCensusHash, censusRepo.url(), "master", true);

            // Attempt a merge
            pr.addComment("/integrate");
            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(mergeBot));

            // Restore the census
            localCensus.push(currentCensusHash, censusRepo.url(), "master", true);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
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

            // Push something conflicting to master
            localRepo.checkout(masterHash, true);
            var conflictingHash = CheckableRepository.appendAndCommit(localRepo, "This looks like a conflict");
            localRepo.push(conflictingHash, author.url(), "master");

            // Trigger a new check run
            pr.setBody(pr.body() + " recheck");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "this pull request can not be integrated");

            // Attempt an integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            TestBotRunner.runPeriodicItems(mergeBot);
            assertLastCommentContains(pr, "PR has not yet been marked as ready for integration");
        }
    }

    @Test
    void noAutoRebase(TestInfo testInfo) throws IOException {
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

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.url(), "master");

            // Attempt a merge
            pr.addComment("/integrate " + masterHash);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "the target branch is no longer at the requested hash");

            // Now use the correct target hash
            pr.addComment("/integrate " + unrelatedHash);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr, "Pushed as commit");

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));
        }
    }

    @Test
    void missingContributingFile(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

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

            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an instructional message and no link to CONTRIBUTING.md
            var lastComment = pr.comments().get(pr.comments().size() - 1);
            assertFalse(lastComment.body().contains("CONTRIBUTING.md"));
        }
    }

    @Test
    void existingContributingFile(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var contributingFile = localRepo.root().resolve("CONTRIBUTING.md");
            Files.writeString(contributingFile, "Patches welcome!\n");
            localRepo.add(contributingFile);
            localRepo.commit("Add CONTRIBUTING.md", "duke", "duke@openjdk.org");
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

            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an instructional message and no link to CONTRIBUTING.md
            var lastComment = pr.comments().get(pr.comments().size() - 1);
            assertTrue(lastComment.body().contains("CONTRIBUTING.md"));
        }
    }

    @Test
    void contributorMissingEmail(TestInfo testInfo) throws IOException {
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
            var authorFullName = author.forge().currentUser().fullName();
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Content", "A commit", authorFullName, "");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Run the bot
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should respond with a failure about missing e-mail
            pr = author.pullRequest(pr.id());
            assertFalse(pr.labelNames().contains("ready"));
            var checks = pr.checks(pr.headHash());
            assertTrue(checks.containsKey("jcheck"));
            var jcheck = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, jcheck.status());
            assertTrue(jcheck.summary().isPresent());
            var summary = jcheck.summary().get();
            assertTrue(summary.contains("Pull request's HEAD commit must contain a valid e-mail"));
        }
    }

    @Test
    void invalidHash(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should reply with integration message
            TestBotRunner.runPeriodicItems(mergeBot);
            var integrateComments = pr.comments()
                                      .stream()
                                      .filter(c -> c.body().contains("To integrate this PR with the above commit message to the `master` branch"))
                                      .filter(c -> c.body().contains("If you prefer to avoid any potential automatic rebasing"))
                                      .count();
            assertEquals(1, integrateComments);

            // Attempt a merge (the bot should only process the first one)
            pr.addComment("/integrate a3987asdf");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("is not a valid hash"))
                           .count();
            assertEquals(1, pushed);

            // Ready label should remain
            assertTrue(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void integrateAutoInBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR with auto integration
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "PR Title", List.of("/integrate auto"));

            // The bot should add the auto label and reply
            TestBotRunner.runPeriodicItems(mergeBot);
            var integrateComments = pr.comments()
                                      .stream()
                                      .filter(c -> c.body().contains("This pull request will be automatically integrated"))
                                      .count();
            assertEquals(1, integrateComments);
            assertTrue(pr.labelNames().contains("auto"));

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should post the /integrate command and push
            TestBotRunner.runPeriodicItems(mergeBot);
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // Author and committer should be the same
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
            assertTrue(pr.labelNames().contains("integrated"));

            // Ready label should have been removed
            assertFalse(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void integrateAutoInComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR with auto integration
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "PR Title");

            // The bot should add the auto label and reply
            pr.addComment("/integrate auto");
            TestBotRunner.runPeriodicItems(mergeBot);
            var integrateComments = pr.comments()
                                      .stream()
                                      .filter(c -> c.body().contains("This pull request will be automatically integrated"))
                                      .count();
            assertEquals(1, integrateComments);
            assertTrue(pr.labelNames().contains("auto"));

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should post the /integrate command and push
            TestBotRunner.runPeriodicItems(mergeBot);
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // Author and committer should be the same
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
            assertTrue(pr.labelNames().contains("integrated"));

            // Ready label should have been removed
            assertFalse(pr.labelNames().contains("ready"));
        }
    }

    @Test
    void manualIntegration(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR with auto integration
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "PR Title", List.of("/integrate auto"));

            // The bot should add the auto label and reply
            TestBotRunner.runPeriodicItems(mergeBot);
            var integrateComments = pr.comments()
                                      .stream()
                                      .filter(c -> c.body().contains("This pull request will be automatically integrated"))
                                      .count();
            assertEquals(1, integrateComments);
            assertTrue(pr.labelNames().contains("auto"));

            // Make a comment to integrate manually
            pr.addComment("/integrate manual");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with that the PR will have to be manually integrated
            TestBotRunner.runPeriodicItems(mergeBot);
            var replies = pr.comments().stream()
                           .filter(comment -> comment.body().contains("This pull request will have to be integrated manually"))
                           .count();
            assertEquals(1, replies);

            // The "auto" label should have been removed
            assertFalse(pr.labelNames().contains("auto"));

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should reply with integration message
            TestBotRunner.runPeriodicItems(mergeBot);
            integrateComments = pr.comments()
                                  .stream()
                                  .filter(c -> c.body().contains("To integrate this PR with the above commit message to the `master` branch"))
                                  .filter(c -> c.body().contains("If you prefer to avoid any potential automatic rebasing"))
                                  .count();
            assertEquals(1, integrateComments);

            // Issue the /integrate command
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // Author and committer should be the same
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
            assertTrue(pr.labelNames().contains("integrated"));

            // Ready label should have been removed
            assertFalse(pr.labelNames().contains("ready"));
        }
    }

    /**
     * Tests recovery after successfully pushing the commit, but failing to update the PR
     */
    @Test
    void retryAfterInterrupt(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var censusFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var censusRepo = censusBuilder.build();
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusRepo).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Attempt a merge
            pr.addComment("/integrate");

            // Let it integrate
            TestBotRunner.runPeriodicItems(mergeBot);

            // Simulate that interruption occurred after prePush comment was added, but before change was
            // pushed
            pr.setState(Issue.State.OPEN);
            pr.removeLabel("integrated");
            pr.addLabel("ready");
            pr.addLabel("rfr");
            var commitComment = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .findAny().orElseThrow();
            ((TestPullRequest) pr).removeComment(commitComment);
            localRepo.push(masterHash, author.url(), "master", true);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.labelNames().contains("rfr"), "rfr label not removed");
            assertTrue(pr.labelNames().contains("integrated"), "integrated label not added");

            // Remove some labels and the commit comment to simulate that last attempt was interrupted
            // after the push was made and the PR was closed
            pr.removeLabel("integrated");
            pr.addLabel("ready");
            pr.addLabel("rfr");
            var commitComment2 = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .findAny().orElseThrow();
            ((TestPullRequest) pr).removeComment(commitComment2);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.labelNames().contains("rfr"), "rfr label not removed");
            assertTrue(pr.labelNames().contains("integrated"), "integrated label not added");

            // Simulate that interruption happened just before the commit comment was added
            var commitComment3 = pr.comments().stream()
                    .filter(comment -> comment.body().equals(commitComment2.body()))
                    .findAny().orElseThrow();
            ((TestPullRequest) pr).removeComment(commitComment3);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.labelNames().contains("rfr"), "rfr label not removed");
            assertTrue(pr.labelNames().contains("integrated"), "integrated label not added");

            // Add another command and verify that no further action is taken
            pr.addComment("/integrate");
            var numComments = pr.comments().size();

            TestBotRunner.runPeriodicItems(mergeBot);

            assertTrue(pr.comments().get(pr.comments().size() - 1).body()
                    .contains("can only be used in open pull requests"));
        }
    }

    /**
     * Tests recovery after successfully pushing the commit, but failing to update the PR,
     * and an extra commit has been integrated to the target before retrying.
     */
    @Test
    void retryAfterInterruptExtraChange(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var censusFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var censusRepo = censusBuilder.build();
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusRepo).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Attempt a merge
            pr.addComment("/integrate");

            // Let it integrate
            TestBotRunner.runPeriodicItems(mergeBot);

            // Remove some labels and the commit comment to simulate that last attempt was interrupted
            pr.removeLabel("integrated");
            pr.addLabel("ready");
            pr.addLabel("rfr");
            var commitComment = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .findAny().orElseThrow();
            ((TestPullRequest) pr).removeComment(commitComment);

            // Add a new commit to master branch
            localRepo.checkout(new Branch("master"));
            localRepo.fetch(author.url(), "master");
            localRepo.merge(new Branch("FETCH_HEAD"));
            var integratedHash = localRepo.resolve("master");
            var newMasterHash = CheckableRepository.appendAndCommit(localRepo, "Another line",
                    "New master commit");
            localRepo.push(newMasterHash, author.url(), "master", true);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit " + integratedHash.orElseThrow()))
                    .count();
            assertEquals(1, pushed, "Commit comment not found");
            assertFalse(pr.labelNames().contains("ready"), "ready label not removed");
            assertFalse(pr.labelNames().contains("rfr"), "rfr label not removed");
            assertTrue(pr.labelNames().contains("integrated"), "integrated label not added");
        }
    }
}
