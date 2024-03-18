/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class BackportPRCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var targetRepo = credentials.getHostedRepository("targetRepo");
            var targetRepo2 = credentials.getHostedRepository("targetRepo2");
            var seedFolder = tempFolder.path().resolve("seed");

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .censusRepo(censusBuilder.build())
                    .seedStorage(seedFolder)
                    .forks(Map.of("targetRepo", targetRepo, "targetRepo2", targetRepo2, "test", author))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);


            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var reviewerPr = (TestPullRequest) integrator.pullRequest(pr.id());

            // Enable backport for targetRepo on master
            pr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was successfully enabled");
            assertTrue(pr.store().labelNames().contains("backport=targetRepo:master"));

            // Enable backport for targetRepo2 on dev, but dev does not exist
            pr.addComment("/backport targetRepo2 dev");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The target branch `dev` does not exist");
            assertFalse(pr.store().labelNames().contains("backport=targetRepo2:dev"));

            // Enable backport for targetRepo2 on dev
            localRepo.push(masterHash, targetRepo2.authenticatedUrl(), "dev", true);
            pr.addComment("/backport targetRepo2 dev");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo2` on branch `dev` was successfully enabled");
            assertTrue(pr.store().labelNames().contains("backport=targetRepo2:dev"));

            // Enable backport for test on master
            pr.addComment("/backport :master");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `test` on branch `master` was successfully enabled");
            assertTrue(pr.store().labelNames().contains("backport=test:master"));

            // Disable backport for test on master
            pr.addComment("/backport disable test:master");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `test` on branch `master` was successfully disabled");
            assertFalse(pr.store().labelNames().contains("backport=test:master"));

            // Disable backport for targetRepo on master
            reviewerPr.addComment("/backport disable targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was successfully disabled.");
            assertFalse(pr.store().labelNames().contains("backport=targetRepo:master"));

            // Disable backport for targetRepo again
            reviewerPr.addComment("/backport disable targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was already disabled.");
            assertFalse(pr.store().labelNames().contains("backport=targetRepo:master"));

            // Enable backport for targetRepo on master as reviewer
            reviewerPr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Backport for repo `targetRepo` on branch `master` was successfully enabled");
            assertTrue(pr.store().labelNames().contains("backport=targetRepo:master"));

            // Approve this PR
            reviewerPr.addReview(Review.Verdict.APPROVED, "");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("ready"));

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "@user1");
            assertLastCommentContains(pr, "was successfully created on the branch");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "@user2");
            assertLastCommentContains(pr, "Could **not** automatically backport");
            assertLastCommentContains(pr, "Below you can find a suggestion for the pull request body:");

            // Resolve conflict
            localRepo.push(masterHash, targetRepo.authenticatedUrl(), "master", true);
            // Use /backport after the pr is integrated
            reviewerPr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "was successfully created on the branch");
        }
    }

    @Test
    void testBackportCommandWhenPrIsClosed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var targetRepo = credentials.getHostedRepository("targetRepo");
            var targetRepo2 = credentials.getHostedRepository("targetRepo2");
            var seedFolder = tempFolder.path().resolve("seed");

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addReviewer(bot.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .censusRepo(censusBuilder.build())
                    .seedStorage(seedFolder)
                    .forks(Map.of("targetRepo", targetRepo, "targetRepo2", targetRepo2))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            TestBotRunner.runPeriodicItems(prBot);

            // Close the pr
            pr.store().setState(Issue.State.CLOSED);
            pr.addComment("/backport targetRepo");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "`/backport` command can not be used in a closed but not integrated pull request");
        }
    }
}
