/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class BackportCommitCommandTests {
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
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit");

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
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport non-existing-repo");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("target repository"));
            assertTrue(botReply.body().contains("does not exist"));
            assertEquals(List.of(), author.pullRequests());
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit");

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name() + " non-existing-branch");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("target branch"));
            assertTrue(botReply.body().contains("does not exist"));
            assertEquals(List.of(), author.pullRequests());
        }
    }

    @Test
    void backportDoesNotApply(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change push it to edit branch
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);

            var masterHash2 = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash2, author.url(), "master", true);

            // Add a backport command
            author.addCommitComment(editHash, "/backport " + author.name() + " master");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains(":warning: could not backport"));
            assertEquals(List.of(), author.pullRequests());
        }
    }
}
