/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommitCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .processPR(false)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change directly on master
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "master");

            // Add a help command
            author.addCommitComment(editHash, "/help");
            TestBotRunner.runPeriodicItems(bot);

            // Look at the reply
            var replies = author.commitComments(editHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "Available commands");
            CommitCommandAsserts.assertLastCommentContains(replies, "Commit Commands documentation");

            // Add a command which is only valid in pull request
            author.addCommitComment(editHash, "/issue 12");
            TestBotRunner.runPeriodicItems(bot);

            replies = author.commitComments(editHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "The command `issue` can only be used in pull requests.");

            // Try an invalid one
            author.addCommitComment(editHash, "/hello");
            TestBotRunner.runPeriodicItems(bot);

            replies = author.commitComments(editHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "Unknown command `hello` - for a list of valid commands use `/help`.");

            editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // The command would not be processed
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/help");
            TestBotRunner.runPeriodicItems(bot);
            PullRequestAsserts.assertLastCommentContains(pr, "/help");
        }
    }

    @Test
    void simplePullRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var botRepo = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(botRepo)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of("jdk17u-dev", credentials.getHostedRepository()))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Add a `backport` command
            pr.addComment("/backport jdk17u-dev");
            TestBotRunner.runPeriodicItems(bot);
            // The `backport` command is invalid because the pull request is not integrated.
            PullRequestAsserts.assertLastCommentContains(pr, "Backport for repo `jdk17u-dev` on branch `master` was successfully enabled");

            // Simulate an integration
            var botPr = botRepo.pullRequest(pr.id());
            localRepo.push(editHash, author.authenticatedUrl(), "master");
            botPr.addComment("Pushed as commit " + editHash.hex() + ".");
            botPr.addLabel("integrated");
            botPr.setState(Issue.State.CLOSED);

            // Add a help command
            pr.addComment("/help");
            TestBotRunner.runPeriodicItems(bot);
            PullRequestAsserts.assertLastCommentContains(pr, "Available commands");

            // Add a `backport` command
            pr.addComment("/backport jdk17u-dev");
            TestBotRunner.runPeriodicItems(bot);
            // The `backport` command is valid.
            PullRequestAsserts.assertLastCommentContains(pr, "Could **not** automatically backport");
            PullRequestAsserts.assertLastCommentContains(pr, "Please fetch the appropriate branch/commit and manually resolve these conflicts");

            // Try an unavailable one
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);
            PullRequestAsserts.assertLastCommentContains(pr, "can only be used in open pull requests");
        }
    }

    @Test
    void commitNotItRepository(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change directly on master
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "master");

            // Make a commit only present in pr branch
            var prHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(prHash, author.authenticatedUrl(), "pr/1", true);

            // Add a help command to commit in pr branch
            var comment = author.addCommitComment(prHash, "/help");
            TestBotRunner.runPeriodicItems(bot);

            // Verify that the bot did *not* reply
            assertEquals(List.of(comment), author.commitComments(prHash));
        }
    }

    @Test
    void externalCommitCommand(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .externalCommitCommands(Map.of("external", "Help for external command"))
                                    .seedStorage(seedFolder)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change directly on master
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "master");

            // Add a help command
            author.addCommitComment(editHash, "/help");
            TestBotRunner.runPeriodicItems(bot);

            // Look at the reply
            var replies = author.commitComments(editHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "Available commands");
            CommitCommandAsserts.assertLastCommentContains(replies, "external");
            CommitCommandAsserts.assertLastCommentContains(replies, "Help for external command");
        }
    }

    @Test
    void missingJcheckConf(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .externalCommitCommands(Map.of("external", "Help for external command"))
                                    .seedStorage(seedFolder)
                                    .build();

            // Populate the projects repository
            var localRepo = Repository.init(tempFolder.path(), author.repositoryType());
            var readme = localRepo.root().resolve("README");
            Files.writeString(readme, "Hello, world!");
            localRepo.add(readme);
            var masterHash = localRepo.commit("Added README", "duke", "duke@openjdk.org");
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a help command
            author.addCommitComment(masterHash, "/help");
            TestBotRunner.runPeriodicItems(bot);

            // Look at the reply
            var replies = author.commitComments(masterHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "There is no `.jcheck/conf` present at revision");
            CommitCommandAsserts.assertLastCommentContains(replies, "cannot process command");
        }
    }

    @Test
    void disableProcessCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(author)
                    .censusRepo(censusBuilder.build())
                    .censusLink("https://census.com/{{contributor}}-profile")
                    .seedStorage(seedFolder)
                    .processCommit(false)
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change directly on master
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "master");

            // Add a help command
            author.addCommitComment(editHash, "/help");
            TestBotRunner.runPeriodicItems(bot);

            // The command should not be processed
            var replies = author.commitComments(editHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "/help");

            editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // The pr command would be processed
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/help");
            TestBotRunner.runPeriodicItems(bot);
            PullRequestAsserts.assertLastCommentContains(pr, "Available commands:");
            PullRequestAsserts.assertLastCommentContains(pr, "Pull Request Commands documentation");
        }
    }

    @Test
    void disableProcessCommitAndProcessPR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(author)
                    .censusRepo(censusBuilder.build())
                    .censusLink("https://census.com/{{contributor}}-profile")
                    .seedStorage(seedFolder)
                    .processCommit(false)
                    .processPR(false)
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change directly on master
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "master");

            // Add a help command
            author.addCommitComment(editHash, "/help");
            TestBotRunner.runPeriodicItems(bot);

            // The command should not be processed
            var replies = author.commitComments(editHash);
            CommitCommandAsserts.assertLastCommentContains(replies, "/help");

            editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // The pr command would not be processed
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/help");
            TestBotRunner.runPeriodicItems(bot);
            PullRequestAsserts.assertLastCommentContains(pr, "/help");
        }
    }
}
