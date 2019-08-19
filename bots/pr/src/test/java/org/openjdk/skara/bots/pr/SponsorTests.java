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

import static org.junit.jupiter.api.Assertions.*;

class SponsorTests {
    private void runSponsortest(TestInfo testInfo, boolean isAuthor) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var pushedFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
            if (isAuthor) {
                censusBuilder.addAuthor(author.host().getCurrentUserDetails().id());
            }
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
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.getComments().stream()
                            .filter(comment -> comment.body().contains("sponsor"))
                            .filter(comment -> comment.body().contains("your change"))
                            .count();
            assertEquals(1, sponsor);

            // The bot should not have pushed the commit
            var notPushed = pr.getComments().stream()
                              .filter(comment -> comment.body().contains("Pushed as commit"))
                              .count();
            assertEquals(0, notPushed);

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.getPullRequest(pr.getId());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.getComments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepo = Repository.materialize(pushedFolder.path(), author.getUrl(), "master");
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            if (isAuthor) {
                assertEquals("Generated Author 2", headCommit.author().name());
                assertEquals("integrationauthor2@openjdk.java.net", headCommit.author().email());
            } else {
                assertEquals("testauthor", headCommit.author().name());
                assertEquals("ta@none.none", headCommit.author().email());
            }

            assertEquals("Generated Reviewer 1", headCommit.committer().name());
            assertEquals("integrationreviewer1@openjdk.java.net", headCommit.committer().email());
            assertTrue(pr.getLabels().contains("integrated"));
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
                                           .addCommitter(author.host().getCurrentUserDetails().id());
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

            // Issue an invalid command
            pr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
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
                                           .addAuthor(author.host().getCurrentUserDetails().id());
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

            // Issue an invalid command
            pr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
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
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
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

            // Reviewer now tries to sponsor
            var reviewerPr = reviewer.getPullRequest(pr.getId());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
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
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id());
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
            var approvalPr = reviewer.getPullRequest(pr.getId());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(mergeBot);

            // Flag it as ready for integration
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Bot should have replied
            var ready = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("now ready to be sponsored"))
                          .filter(comment -> comment.body().contains("at version " + editHash.hex()))
                          .count();
            assertEquals(1, ready);

            // Push another change
            var updateHash = CheckableRepository.appendAndCommit(localRepo,"Yet more stuff");
            localRepo.push(updateHash, author.getUrl(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.getHeadHash();
            var refreshCount = 0;
            do {
                pr = author.getPullRequest(pr.getId());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.getHeadHash().equals(lastHeadHash));

            // Reviewer now tries to sponsor
            var reviewerPr = reviewer.getPullRequest(pr.getId());
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.getComments().stream()
                          .filter(comment -> comment.body().contains("The PR has been updated since the change"))
                          .count();
            assertEquals(1, error);

            // Flag it as ready for integration again
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // It should now be possible to sponsor
            reviewerPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should have pushed the commit
            var pushed = pr.getComments().stream()
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
                                           .addAuthor(author.host().getCurrentUserDetails().id())
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

            // Issue a merge command without being a Committer
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply that a sponsor is required
            var sponsor = pr.getComments().stream()
                            .filter(comment -> comment.body().contains("sponsor"))
                            .filter(comment -> comment.body().contains("your change"))
                            .count();
            assertEquals(1, sponsor);

            // Reviewer now agrees to sponsor
            var reviewerPr = reviewer.getPullRequest(pr.getId());
            reviewerPr.addComment("/sponsor");
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
}
