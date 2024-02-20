/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class PreIntegrateTests {
    @Test
    void integrateFollowup(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                                         .repo(integrator)
                                         .censusRepo(censusBuilder.build())
                                         .seedStorage(seedFolder.path())
                                         .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "First PR", "Base change");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should reply with integration message
            TestBotRunner.runPeriodicItems(mergeBot);
            assertLastCommentContains(pr, "To integrate this PR with the above commit message to the `master` branch");

            // Simulate population of the pr branch
            localRepo.push(editHash, author.authenticatedUrl(), PreIntegrations.preIntegrateBranch(pr), true);

            // Create follow-up work
            var followUp = CheckableRepository.appendAndCommit(localRepo, "Follow-up work", "Follow-up change");
            localRepo.push(followUp, author.authenticatedUrl(), "followup", true);
            var followUpPr = credentials.createPullRequest(author, PreIntegrations.preIntegrateBranch(pr), "followup", "This is another pull request");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Approve it as another user
            var approvalFollowUpPr = integrator.pullRequest(followUpPr.id());
            approvalFollowUpPr.addReview(Review.Verdict.APPROVED, "Approved");

            // The bot should add an integration blocker message
            assertTrue(followUpPr.store().body().contains("Integration blocker"));
            assertTrue(followUpPr.store().body().contains("Dependency #" + pr.id() + " must be integrated"));

            // Try to integrate it
            followUpPr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertLastCommentContains(followUpPr, "This pull request has not yet been marked as ready for integration");

            // Push something unrelated to the target
            localRepo.checkout(masterHash, true);
            var unrelatedFile = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelatedFile, "Other things happens in master");
            localRepo.add(unrelatedFile);
            var newMasterHash = localRepo.commit("Unrelated change", "duke", "duke@openjdk.org");
            localRepo.push(newMasterHash, author.authenticatedUrl(), "master");

            // Now integrate the first one
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr, "Pushed as commit");

            // The notifier will now retarget the follow up PR, simulate this
            followUpPr.setTargetRef("master");

            // The second should now become ready
            TestBotRunner.runPeriodicItems(mergeBot);
            assertFalse(followUpPr.store().body().contains("Integration blocker"));
            assertTrue(followUpPr.store().labelNames().contains("ready"));

            // Push something else unrelated to the target
            var currentMaster = localRepo.fetch(author.authenticatedUrl(), "master").orElseThrow();
            localRepo.checkout(currentMaster, true);
            var unrelatedFile2 = localRepo.root().resolve("unrelated2.txt");
            Files.writeString(unrelatedFile2, "Some other things happens in master");
            localRepo.add(unrelatedFile2);
            newMasterHash = localRepo.commit("Second unrelated change", "duke", "duke@openjdk.org");
            localRepo.push(newMasterHash, author.authenticatedUrl(), "master");

            // Refresh the status
            followUpPr.setBody(followUpPr.body() + " recheck");
            TestBotRunner.runPeriodicItems(mergeBot);

            // Try to integrate it again
            followUpPr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertLastCommentContains(followUpPr, "Pushed as commit");

            // Check that everything is present
            var finalMaster = localRepo.fetch(author.authenticatedUrl(), "master").orElseThrow();
            localRepo.checkout(finalMaster, true);
            assertEquals("Other things happens in master", Files.readString(localRepo.root().resolve("unrelated.txt")));
            assertEquals("Some other things happens in master", Files.readString(localRepo.root().resolve("unrelated2.txt")));
            assertTrue(CheckableRepository.hasBeenEdited(localRepo));
        }
    }
}
