/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class BackportCommitCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var forkCredentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var fork = forkCredentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), fork))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Initiate the fork repo
            localRepo.push(masterHash, fork.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");
            // Create more branches
            for (int i = 1; i <= 20; i++) {
                localRepo.push(editHash, author.authenticatedUrl(), "jdk" + i, true);
            }
            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name());
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("backport"));
            assertTrue(botReply.body().contains("was successfully created"));
            assertTrue(botReply.body().contains("To create a pull request"));
            assertTrue(botReply.body().contains("with this backport"));
            assertTrue(botReply.body().contains("@" + botReply.author().username()));
            assertEquals(botReply.body().indexOf("@" + botReply.author().username()), botReply.body().lastIndexOf("@" + botReply.author().username()));

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name() + ":" + author.defaultBranchName());
            TestBotRunner.runPeriodicItems(bot);
            recentCommitComments = author.recentCommitComments();
            assertEquals(4, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("To create a pull request with this backport targeting " +
                    "[" + author.name() + ":" + author.defaultBranchName() + "]"));

            // Add a backport command
            author.addCommitComment(editHash, "/backport :" + author.defaultBranchName());
            TestBotRunner.runPeriodicItems(bot);
            recentCommitComments = author.recentCommitComments();
            assertEquals(6, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("To create a pull request with this backport targeting " +
                    "[" + author.name() + ":" + author.defaultBranchName() + "]"));

            author.addCommitComment(editHash, "/backport jdk11");
            TestBotRunner.runPeriodicItems(bot);
            recentCommitComments = author.recentCommitComments();
            assertEquals(8, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("There is a branch `jdk11` in the current repository `test`."));

            author.addCommitComment(editHash, "/backport :jdk31");
            TestBotRunner.runPeriodicItems(bot);
            recentCommitComments = author.recentCommitComments();
            assertEquals(10, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("List of valid branches:"));
        }
    }

    @Test
    void unknownTargetRepo(TestInfo testInfo) throws IOException {
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
                                    .forks(Map.of(author.name(), author, "foobar/other-repo", author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport foobar/non-existing-repo");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("target repository"));
            assertTrue(botReply.body().contains("is not a valid target for backports"));
            assertTrue(botReply.body().contains("List of valid target repositories: `foobar/other-repo`, `test`"));
            assertEquals(List.of(), author.openPullRequests());
        }
    }

    @Test
    void unknownTargetRepoFork(TestInfo testInfo) throws IOException {
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
                    .forks(Map.of("foobar/other-repo", author))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name());
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("is not a valid target for backports"));
            assertTrue(botReply.body().contains("List of valid target repositories: `foobar/other-repo`"));
            assertEquals(List.of(), author.openPullRequests());
        }
    }

    @Test
    void unknownTargetBranch(TestInfo testInfo) throws IOException {
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
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name() + " non-existing-branch");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("target branch"));
            assertTrue(botReply.body().contains("does not exist"));
            assertEquals(List.of(), author.openPullRequests());
        }
    }

    @Test
    void backportDoesNotApply(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var forkCredentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var fork = forkCredentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), fork))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var initialHash = localRepo.head();

            // Initiate the fork repository
            localRepo.push(initialHash, fork.authenticatedUrl(), "master", true);

            // Make a change and push it to edit branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);

            // Add another conflicting change in the master branch
            localRepo.checkout(initialHash);
            var masterHash2 = CheckableRepository.appendAndCommit(localRepo, "a different line");
            localRepo.push(masterHash2, author.authenticatedUrl(), "master", true);

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name() + " master");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Could **not** automatically backport"));
            assertTrue(botReply.body().contains("Please fetch the appropriate branch/commit and manually resolve these conflicts"));
            assertTrue(botReply.body().contains("master:master"));
            assertTrue(botReply.body().contains("$ git checkout master"));
            assertTrue(botReply.body().contains("Below you can find a suggestion for the pull request body:"));
            assertEquals(List.of(), author.openPullRequests());
        }
    }

    @Test
    void backportTwice(TestInfo testInfo) throws IOException {
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
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name());
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("backport"));
            assertTrue(botReply.body().contains("was successfully created"));
            assertTrue(botReply.body().contains("To create a pull request"));
            assertTrue(botReply.body().contains("with this backport"));

            // Add a backport command again
            author.addCommitComment(editHash, "/backport " + author.name());
            TestBotRunner.runPeriodicItems(bot);

            recentCommitComments = author.recentCommitComments();
            assertEquals(4, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("backport"));
            assertTrue(botReply.body().contains("was successfully created"));
            assertTrue(botReply.body().contains("To create a pull request"));
            assertTrue(botReply.body().contains("with this backport"));
        }
    }

    /**
     * Tests backport with unrelated change in target and a common dependency
     * change in both target and source
     */
    @Test
    void complex(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var forkCredentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var fork = forkCredentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(author)
                    .censusRepo(censusBuilder.build())
                    .censusLink("https://census.com/{{contributor}}-profile")
                    .seedStorage(seedFolder)
                    .forks(Map.of(author.name(), fork))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Initiate the fork repo
            localRepo.push(masterHash, fork.authenticatedUrl(), "master", true);

            // Make an unrelated change in master
            var unrelated = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelated, "Hello");
            localRepo.add(unrelated);
            var unrelatedHash = localRepo.commit("Unrelated", "X", "x@y.z");
            localRepo.push(unrelatedHash, author.authenticatedUrl(), "master");

            // Make a change in edit branch, without the unrelated change
            localRepo.checkout(masterHash);
            var editHashCommon = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHashCommon, author.authenticatedUrl(), "edit");

            // Make the same change in master
            localRepo.checkout(unrelatedHash);
            var masterHashCommon = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHashCommon, author.authenticatedUrl(), "master");

            // Make another change in edit branch that will be the source of the backport
            localRepo.checkout(editHashCommon);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name());
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("backport"));
            assertTrue(botReply.body().contains("was successfully created"));
            assertTrue(botReply.body().contains("To create a pull request"));
            assertTrue(botReply.body().contains("with this backport"));
        }
    }

    @Test
    void alreadyPresent(TestInfo testInfo) throws IOException {
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
                    .forks(Map.of(author.name(), author))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // Make the same change in the master branch
            localRepo.push(editHash, author.authenticatedUrl(), "master");

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name());
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Could **not** apply backport"));
            assertTrue(botReply.body().contains("because the change is already present in the target."));
        }
    }
}
