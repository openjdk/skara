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
package org.openjdk.skara.bots.notify.prbranch;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Branch;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.notify.prbranch.PullRequestBranchNotifier.FORCE_PUSH_MARKER;
import static org.openjdk.skara.bots.notify.prbranch.PullRequestBranchNotifier.FORCE_PUSH_SUGGESTION;

public class PullRequestBranchNotifierTests {
    private TestBotFactory testBotBuilder(HostedRepository hostedRepository, Path storagePath) {
        return TestBotFactory.newBuilder()
                             .addHostedRepository("hostedrepo", hostedRepository)
                             .storagePath(storagePath)
                             .addConfiguration("database", JSON.object()
                                                               .put("repository", "hostedrepo:history")
                                                               .put("name", "duke")
                                                               .put("email", "duke@openjdk.org"))
                             .addConfiguration("ready", JSON.object()
                                                            .put("labels", JSON.array())
                                                            .put("comments", JSON.array()))
                             .addConfiguration("integrator", JSON.of(hostedRepository.forge().currentUser().id()))
                             .addConfiguration("repositories", JSON.object()
                                                                   .put("hostedrepo", JSON.object()
                                                                                          .put("basename", "test")
                                                                                          .put("branches", "master")
                                                                                          .put("prbranch", JSON.object())))
                             .build();
    }

    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, storageFolder).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create a PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line");
            localRepo.push(editHash, repo.url(), "source", true);
            var pr = credentials.createPullRequest(repo, "master", "source", "This is a PR", false);
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should now contain the new branch
            var hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);

            // Close the PR
            pr.setState(Issue.State.CLOSED);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should no longer contain the branch
            assertThrows(IOException.class, () -> localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr)));

            // Reopen the PR
            pr.setState(Issue.State.OPEN);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The branch should have reappeared
            hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);
        }
    }

    @Test
    void rfrMissing(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, storageFolder).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create a PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line");
            localRepo.push(editHash, repo.url(), "source", true);
            var pr = credentials.createPullRequest(repo, "master", "source", "This is a PR", false);
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should now contain the new branch
            var hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);

            // remove label `rfr`
            pr.removeLabel("rfr");

            // Close the PR
            pr.setState(Issue.State.CLOSED);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should no longer contain the branch
            assertThrows(IOException.class, () -> localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr)));

            // Reopen the PR
            pr.setState(Issue.State.OPEN);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should not contain the branch, because the pr doesn't have label `rfr`.
            assertThrows(IOException.class, () -> localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr)));

            // add label `rfr`
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The branch should have reappeared
            hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);
        }
    }

    @Test
    void updated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, storageFolder).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create a PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line");
            localRepo.push(editHash, repo.url(), "source", true);
            var pr = credentials.createPullRequest(repo, "master", "source", "This is a PR", false);
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should now contain the new branch
            var hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);

            // Push another change
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "Yet another line");
            localRepo.push(updatedHash, repo.url(), "source");

            TestBotRunner.runPeriodicItems(notifyBot);

            // The branch should have been updated
            hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(updatedHash, hash);
        }
    }

    @Test
    void branchMissing(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, storageFolder).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create a PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line");
            localRepo.push(editHash, repo.url(), "source", true);
            var pr = credentials.createPullRequest(repo, "master", "source", "This is a PR", false);
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should now contain the new branch
            var hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);
            try {
                localRepo.prune(new Branch(PreIntegrations.preIntegrateBranch(pr)), repo.url().toString());
            } catch (IOException ignored) {
            }

            // Now close it - no exception should be raised
            pr.setState(Issue.State.CLOSED);
            TestBotRunner.runPeriodicItems(notifyBot);
        }
    }

    @Test
    void retarget(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, storageFolder).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create a PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line");
            localRepo.push(editHash, repo.url(), "source", true);
            var pr = credentials.createPullRequest(repo, "master", "source", "This is a PR", false);
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should now contain the new branch
            var hash = localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr));
            assertEquals(editHash, hash);

            // Create follow-up work
            var followUp = CheckableRepository.appendAndCommit(localRepo, "Follow-up work", "Follow-up change");
            localRepo.push(followUp, repo.url(), "followup", true);
            var followUpPr = credentials.createPullRequest(repo, PreIntegrations.preIntegrateBranch(pr), "followup", "This is another pull request");
            followUpPr.addLabel("rfr");
            assertEquals(PreIntegrations.preIntegrateBranch(pr), followUpPr.targetRef());

            // Close the PR
            pr.setState(Issue.State.CLOSED);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should no longer contain the branch
            assertThrows(IOException.class, () -> localRepo.fetch(repo.url(), PreIntegrations.preIntegrateBranch(pr)));

            // The follow-up PR should have been retargeted
            assertEquals("master", followUpPr.store().targetRef());

            // Instructions on how to adapt to the newly integrated changes should have been posted
            var lastComment = followUpPr.comments().get(followUpPr.comments().size() - 1);
            assertTrue(lastComment.body().contains("The parent pull request that this pull request "
                    + "depends on has been closed without being integrated"), lastComment.body());

            // Create another follow-up work
            var anotherFollowUp = CheckableRepository.appendAndCommit(localRepo, "another follow-up work", "another follow-up change");
            localRepo.push(anotherFollowUp, repo.url(), "another-followup", true);
            var anotherFollowUpPr = credentials.createPullRequest(repo, PreIntegrations.preIntegrateBranch(followUpPr), "another-followup", "This is another follow-up pull request");
            anotherFollowUpPr.addLabel("rfr");
            assertEquals(PreIntegrations.preIntegrateBranch(followUpPr), anotherFollowUpPr.targetRef());
            TestBotRunner.runPeriodicItems(notifyBot);

            // Simulate that the PR has been integrated.
            followUpPr.setState(Issue.State.CLOSED);
            followUpPr.addLabel("integrated");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The target repo should no longer contain the branch
            var targetBranch = PreIntegrations.preIntegrateBranch(followUpPr);
            assertThrows(IOException.class, () -> localRepo.fetch(repo.url(), targetBranch));

            // The another follow-up PR should have been retargeted
            assertEquals("master", anotherFollowUpPr.store().targetRef());
            lastComment = anotherFollowUpPr.comments().get(anotherFollowUpPr.comments().size() - 1);
            assertTrue(lastComment.body().contains("The parent pull request that this "
                    + "pull request depends on has now been integrated"), lastComment.body());
            assertTrue(lastComment.body().contains("git checkout another-followup"), lastComment.body());
            assertTrue(lastComment.body().contains("git commit -m \"Merge master\""), lastComment.body());
        }
    }

    @Test
    void testForcePush(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, storageFolder).create("notify", JSON.object());

            // Create a PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line");
            localRepo.push(editHash, repo.url(), "source", true);
            var pr = credentials.createPullRequest(repo, "master", "source", "This is a PR", false);
            pr.addLabel("rfr");
            pr.addComment("initial");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The PR shouldn't have the force-push suggestion comment
            assertEquals(1, pr.comments().size());
            var lastComment = pr.comments().get(pr.comments().size() - 1);
            assertTrue(lastComment.body().contains("initial"));
            assertFalse(lastComment.body().contains(FORCE_PUSH_MARKER));
            assertFalse(lastComment.body().contains(FORCE_PUSH_SUGGESTION));

            // Normally push.
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "Normally push");
            localRepo.push(updatedHash, repo.url(), "source", false);
            pr.addComment("Normally push");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The PR shouldn't have the force-push suggestion comment.
            assertEquals(2, pr.comments().size());
            lastComment = pr.comments().get(pr.comments().size() - 1);
            assertTrue(lastComment.body().contains("Normally push"));
            assertFalse(lastComment.body().contains(FORCE_PUSH_MARKER));
            assertFalse(lastComment.body().contains(FORCE_PUSH_SUGGESTION));

            // Simulate force-push.
            updatedHash = CheckableRepository.appendAndCommit(localRepo, "test force-push");
            localRepo.checkout(editHash);
            localRepo.squash(updatedHash);
            var forcePushHash = localRepo.commit("test force-push", "duke", "duke@openjdk.org");
            localRepo.push(forcePushHash, repo.url(), "source", true);
            pr.setLastForcePushTime(ZonedDateTime.now());
            pr.addComment("Force-push");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The last comment of the PR should be the force-push suggestion comment.
            assertEquals(4, pr.comments().size());
            lastComment = pr.comments().get(pr.comments().size() - 1);
            assertFalse(lastComment.body().contains("Force-push"));
            assertTrue(lastComment.body().contains(FORCE_PUSH_MARKER));
            assertTrue(lastComment.body().contains(FORCE_PUSH_SUGGESTION));

            // Normally push again.
            updatedHash = CheckableRepository.appendAndCommit(localRepo, "Normally push");
            localRepo.push(updatedHash, repo.url(), "source", false);
            pr.addComment("Normally push again");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The last comment of the PR shouldn't be the force-push suggestion comment.
            assertEquals(5, pr.comments().size());
            lastComment = pr.comments().get(pr.comments().size() - 1);
            assertTrue(lastComment.body().contains("Normally push again"));
            assertFalse(lastComment.body().contains(FORCE_PUSH_MARKER));
            assertFalse(lastComment.body().contains(FORCE_PUSH_SUGGESTION));

            // Simulate force-push again.
            updatedHash = CheckableRepository.appendAndCommit(localRepo, "test force-push again");
            localRepo.checkout(editHash);
            localRepo.squash(updatedHash);
            forcePushHash = localRepo.commit("test force-push again", "duke", "duke@openjdk.org");
            localRepo.push(forcePushHash, repo.url(), "source", true);
            pr.setLastForcePushTime(ZonedDateTime.now());
            pr.addComment("Force-push again");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The last comment of the PR should be the force-push suggestion comment.
            assertEquals(7, pr.comments().size());
            lastComment = pr.comments().get(pr.comments().size() - 1);
            assertFalse(lastComment.body().contains("Force-push again"));
            assertTrue(lastComment.body().contains(FORCE_PUSH_MARKER));
            assertTrue(lastComment.body().contains(FORCE_PUSH_SUGGESTION));
        }
    }
}
