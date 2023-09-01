/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class AuthorCommandTests {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/author xx");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "Syntax");

            // set an author
            pr.addComment("/author set Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr, "Setting overriding author to `Test Person <test@test.test>`. " +
                    "When this pull request is integrated, the overriding author will be used in the commit.");

            // Remove it
            pr.addComment("/author remove Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr, "Overriding author `Test Person <test@test.test>` was successfully removed. " +
                    "When this pull request is integrated, the pull request author will be used as the author of the commit.");

            // Remove something that isn't there
            pr.addComment("/author remove Test Person 2 <test2@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "There is no overriding author set for this pull request.");

            // Remove something that isn't there
            pr.addComment("/author remove");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "There is no overriding author set for this pull request.");

            // Now add someone back again
            pr.addComment("/author Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr, "Setting overriding author to `Test Person <test@test.test>`. " +
                    "When this pull request is integrated, the overriding author will be used in the commit.");

            // Remove something that isn't there
            pr.addComment("/author remove Test Person 2 <test2@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "Cannot remove `Test Person 2 <test2@test.test>`, the overriding author is currently set to: `Test Person <test@test.test>`");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("change now passes all *automated*"))
                    .count();
            assertEquals(1, pushed);

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr, "Pushed as commit");

            // The change should now be present on the master branch
            var pushedFolder = tempFolder.path().resolve("pushed");
            var pushedRepo = Repository.materialize(pushedFolder, author.authenticatedUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            assertEquals("Test Person", headCommit.author().name());
            assertEquals("Generated Committer 2", headCommit.committer().name());
        }
    }
}
