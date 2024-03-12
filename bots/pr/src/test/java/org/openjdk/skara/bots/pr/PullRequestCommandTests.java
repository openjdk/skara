/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class PullRequestCommandTests {
    @Test
    void invalidCommand(TestInfo testInfo) throws IOException {
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
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue a commit command
            pr.addComment("/tag");
            TestBotRunner.runPeriodicItems(mergeBot);
            PullRequestAsserts.assertLastCommentContains(pr, "The command `tag` can not be used in pull requests.");

            // Issue an invalid command
            pr.addComment("/howdy");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Unknown command"))
                          .filter(comment -> comment.body().contains("help"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void helpCommand(TestInfo testInfo) throws IOException {
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
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/help");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with some help
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Available commands"))
                          .filter(comment -> comment.body().contains("help"))
                          .filter(comment -> comment.body().contains("integrate"))
                          .filter(comment -> comment.body().contains("sponsor"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void multipleCommands(TestInfo testInfo) throws IOException {
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
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue multiple commands in a comment
            pr.addComment("/contributor add A <a@b.c>\n/summary line 1\nline 2\n/contributor add B <b@c.d>");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Each command should get a separate reply
            assertEquals(5, pr.comments().size());
            assertTrue(pr.comments().get(2).body().contains("Contributor `A <a@b.c>` successfully added"), pr.comments().get(2).body());
            assertTrue(pr.comments().get(3).body().contains("Setting summary to:\n" +
                                                                    "\n" +
                                                                    "```\n" +
                                                                    "line 1\n" +
                                                                    "line 2"), pr.comments().get(3).body());
            assertTrue(pr.comments().get(4).body().contains("Contributor `B <b@c.d>` successfully added"), pr.comments().get(4).body());

            // They should only be executed once
            TestBotRunner.runPeriodicItems(mergeBot);
            TestBotRunner.runPeriodicItems(mergeBot);
            assertEquals(5, pr.comments().size());
        }
    }

    @Test
    void selfCommand(TestInfo testInfo) throws IOException {
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
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an command using the bot account
            var botPr = integrator.pullRequest(pr.id());
            botPr.addComment("/help");

            // The bot should not reply
            assertEquals(1, pr.comments().size());
            TestBotRunner.runPeriodicItems(mergeBot);
            assertEquals(2, pr.comments().size());

            // But if we add an overriding marker, it should
            botPr.addComment("/help\n<!-- Valid self-command -->");

            assertEquals(3, pr.comments().size());
            TestBotRunner.runPeriodicItems(mergeBot);
            assertEquals(4, pr.comments().size());

            var help = pr.comments().stream()
                         .filter(comment -> comment.body().contains("Available commands"))
                         .filter(comment -> comment.body().contains("help"))
                         .count();
            assertEquals(1, help);
        }
    }

    @Test
    void inBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                                         .repo(integrator)
                                         .censusRepo(censusBuilder.build())
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid body command
            pr.setBody("This is a body\n/contributor add A <a@b.c>\n/contributor add B <b@c.d>");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The second command reply should be the last comment
            assertLastCommentContains(pr, "Contributor `B <b@c.d>` successfully added.");

            // The first command should also be reflected in the body
            assertTrue(pr.store().body().contains("A `<a@b.c>`"));
        }
    }

    @Test
    void disallowedInBody(TestInfo testInfo) throws IOException {
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

            // Issue an invalid body command
            pr.setBody("/help");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with some help
            assertLastCommentContains(pr, "The command `help` cannot be used in the pull request body");
        }
    }

    @Test
    void externalCommandFollowedByNonExternalCommand(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                                         .repo(integrator)
                                         .censusRepo(censusBuilder.build())
                                         .externalPullRequestCommands(Map.of("external", "Help for external command"))
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an external command
            var externalCommandComment = pr.addComment("/external");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should not reply since the external command will be handled by another bot
            assertLastCommentContains(pr, "This change is not yet ready to be integrated.");

            // Issue the help command
            pr.addComment("/help");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with help
            assertLastCommentContains(pr, "@user1 Available commands:");
            assertLastCommentContains(pr, " * help - shows this text");
            assertLastCommentContains(pr, " * external - Help for external command");
        }
    }

    @Test
    void summaryCommandInBodyWithBotComment(TestInfo testInfo) throws IOException {
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request", List.of(
                "/summary",
                "This is a multi-line summary",
                "",
                "With multiple paragraphs",
                "",
                "Even a final one at the end"
            ));

            // Run the bot
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with some help
            assertLastCommentContains(pr, "Setting summary to:\n\n" +
                                          "```\n" +
                                          "This is a multi-line summary\n" +
                                          "\n" +
                                          "With multiple paragraphs\n" +
                                          "\n" +
                                          "Even a final one at the end\n" +
                                          "```\n");
        }
    }

    @Test
    void interpretCommandFromReviews(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Test command in review
            pr.addReview(Review.Verdict.APPROVED, "/reviewers 3");
            // Run the bot
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The total number of required reviews for this PR (including the jcheck configuration and the last /reviewers command) is now set to 3");

            // This should work
            pr.addReview(Review.Verdict.APPROVED, "first line \n  /reviewers 4");
            // Run the bot
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The total number of required reviews for this PR (including the jcheck configuration and the last /reviewers command) is now set to 4");

            // This should not work
            pr.addReview(Review.Verdict.APPROVED, "first line \n  second line /reviewers 5");
            // Run the bot
            TestBotRunner.runPeriodicItems(prBot);
            // Reviewer number is still 4
            assertLastCommentContains(pr, "The total number of required reviews for this PR (including the jcheck configuration and the last /reviewers command) is now set to 4");
        }
    }
}
