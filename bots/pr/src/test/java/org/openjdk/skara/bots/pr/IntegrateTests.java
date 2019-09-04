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
import org.openjdk.skara.vcs.Repository;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

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
                                           .addCommitter(author.host().getCurrentUserDetails().id())
                                           .addReviewer(integrator.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Attempt a merge (the bot should only process the first one)
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.getUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // Author and committer should be the same
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
            assertTrue(pr.getLabels().contains("integrated"));

            // Ready label should have been removed
            assertFalse(pr.getLabels().contains("ready"));
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
                                           .addCommitter(author.host().getCurrentUserDetails().id())
                                           .addCommitter(committer.host().getCurrentUserDetails().id())
                                           .addReviewer(integrator.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Review it twice
            var integratorPr = integrator.getPullRequest(pr.getId());
            var committerPr = committer.getPullRequest(pr.getId());
            integratorPr.addReview(Review.Verdict.APPROVED, "Approved");
            committerPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Attempt a merge
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.getUrl(), "master");
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
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Attempt a merge, do not run the check from the bot
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot, item -> item instanceof CheckWorkItem);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("merge request cannot be fulfilled at this time"))
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
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Attempt a merge
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("merge request cannot be fulfilled at this time"))
                          .filter(comment -> comment.body().contains("failed the final jcheck"))
                          .count();
            assertEquals(1, error, pr.getComments().stream().map(Comment::body).collect(Collectors.joining("\n---\n")));
        }
    }

    @Test
    void failedCheck(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "trailing whitespace   ");
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Attempt a merge
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("merge request cannot be fulfilled at this time"))
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
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Flag it as checked
            var check = CheckBuilder.create("testcheck", editHash);
            pr.createCheck(check.build());
            check.complete(true);
            pr.updateCheck(check.build());

            // Now push another change
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "Yet another line");
            localRepo.push(updatedHash, author.getUrl(), "edit", true);

            // Attempt a merge - avoid running checks from the bot
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot, item -> item instanceof CheckWorkItem);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("merge request cannot be fulfilled at this time"))
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
                                           .addAuthor(author.host().getCurrentUserDetails().id())
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id())
                                           .addReviewer(integrator.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it (a few times)
            TestBotRunner.runPeriodicItems(mergeBot);
            TestBotRunner.runPeriodicItems(mergeBot);
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an instructional message (and only one)
            var pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("change can now be integrated"))
                           .filter(comment -> comment.body().contains("Reviewed-by: integrationreviewer3"))
                           .count();
            assertEquals(1, pushed);

            // Ensure that the bot doesn't pick up on commands in the instructional message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(0, error);

            // Drop the approval
            approvalPr.addReview(Review.Verdict.DISAPPROVED, "Disapproved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The instructional message should have been updated
            pushed = pr.getComments().stream()
                       .filter(comment -> comment.body().contains("no longer ready for integration"))
                       .count();
            assertEquals(1, pushed);

            // Restore the approval
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The instructional message should have been updated
            pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("change can now be integrated"))
                           .filter(comment -> comment.body().contains("Reviewed-by: integrationreviewer3"))
                           .count();
            assertEquals(1, pushed);

            // Approve it as yet another user
            var reviewerPr = reviewer.getPullRequest(pr.getId());
            reviewerPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The instructional message should have been updated
            pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("change can now be integrated"))
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
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var mergeBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue a merge command not as the PR author
            var externalPr = external.getPullRequest(pr.getId());
            externalPr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
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

            // Push something unrelated to master
            localRepo.checkout(masterHash, true);
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.getUrl(), "master");

            // Attempt a merge (the bot should only process the first one)
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .filter(comment -> comment.body().contains("commit was automatically rebased without conflicts"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.getUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));
        }
    }

    @Test
    void retryOnFailure(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

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
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Pre-push to cause a failure
            localRepo.push(editHash, author.getUrl(), "master");

            // Attempt a merge (without triggering another check)
            pr.addComment("/integrate");
            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(mergeBot, wi -> wi instanceof CheckWorkItem));

            // Restore the master branch
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // The bot should now retry
            TestBotRunner.runPeriodicItems(mergeBot, wi -> wi instanceof CheckWorkItem);

            // The bot should reply with an ok message
            var pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);
        }
    }
}
