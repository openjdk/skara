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

import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class SummaryTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Try setting a summary when none has been set yet
            pr.addComment("/summary");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"To set a summary");

            // Add a summary
            pr.addComment("/summary This is a summary");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Setting summary to");

            // Remove it again
            pr.addComment("/summary");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Removing existing");

            // Now add one again
            pr.addComment("/summary Yet another summary");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Setting summary to");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // Now update it
            pr.addComment("/summary Third time is surely the charm");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Updating existing summary");

            // The commit message preview should contain the final summary
            var summaryLine = pr.comments().stream()
                                .flatMap(comment -> comment.body().lines())
                                .filter(line -> !line.contains("/summary"))
                                .filter(line -> !line.contains("Updating existing"))
                                .filter(line -> line.contains("Third time"))
                                .findAny()
                                .orElseThrow();
            assertEquals("Third time is surely the charm", summaryLine);

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr,"Pushed as commit");

            // The change should now be present on the master branch
            var pushedFolder = tempFolder.path().resolve("pushed");
            var pushedRepo = Repository.materialize(pushedFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // The summary should be present
            summaryLine = headCommit.message().stream()
                                   .filter(line -> line.contains("Third time"))
                                   .findAny()
                                   .orElseThrow();
            assertEquals("Third time is surely the charm", summaryLine);
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

            // Issue a contributor command not as the PR author
            var externalPr = external.pullRequest(pr.id());
            externalPr.addComment("/summary a summary");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void multiline(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Try setting a summary when none has been set yet
            pr.addComment("/summary");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"To set a summary");

            // Add a multi-line summary
            pr.addComment("/summary\nFirst line\nSecond line");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,
                "Setting summary to:\n" +
                "\n" +
                "```\n" +
                "First line\n" +
                "Second line\n" +
                "```");

            // Remove it again
            pr.addComment("/summary");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Removing existing");

            // Now add one again
            pr.addComment("/summary\nL1\nL2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,
                "Setting summary to:\n" +
                "\n" +
                "```\n" +
                "L1\n" +
                "L2\n" +
                "```");

            // Now update it
            pr.addComment("/summary\n1L\n2L");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,
                "Updating existing summary to:\n" +
                "\n" +
                "```\n" +
                "1L\n" +
                "2L\n" +
                "```");

            // Finally update it to a single line summary
            pr.addComment("/summary single line");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr, "Updating existing summary to `single line`");
        }
    }
}
