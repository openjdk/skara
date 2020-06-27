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
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.process.Process;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class MergeTests {
    @Test
    void branchMerge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.url(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.url(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.url(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            // The commits from the "other" branch should be preserved and not squashed (but not the merge commit)
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            Set<Hash> commits;
            try (var tempCommits = pushedRepo.commits(masterHash.hex() + ".." + headHash.hex())) {
                commits = tempCommits.stream()
                        .map(Commit::hash)
                        .collect(Collectors.toSet());
            }
            assertTrue(commits.contains(otherHash1));
            assertTrue(commits.contains(otherHash2));
            assertFalse(commits.contains(mergeHash));

            // Author and committer should updated in the merge commit
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
        }
    }

    @Test
    void branchMergeShortName(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.url(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.url(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            // The commits from the "other" branch should be preserved and not squashed (but not the merge commit)
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            Set<Hash> commits;
            try (var tempCommits = pushedRepo.commits(masterHash.hex() + ".." + headHash.hex())) {
                commits = tempCommits.stream()
                                     .map(Commit::hash)
                                     .collect(Collectors.toSet());
            }
            assertTrue(commits.contains(otherHash1));
            assertTrue(commits.contains(otherHash2));
            assertFalse(commits.contains(mergeHash));

            // Author and committer should updated in the merge commit
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
        }
    }

    @Test
    void tagMerge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.url(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            var tag = localRepo.tag(otherHash2, "othertag", "Tagging other", "tagger", "tagger@one");
            var tagHash = localRepo.lookup(tag).orElseThrow().hash();
            localRepo.push(tagHash, author.url(), "refs/tags/othertag");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge othertag");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);

            // The change should now be present on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            // The commits from the "other" branch should be preserved and not squashed (but not the merge commit)
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            Set<Hash> commits;
            try (var tempCommits = pushedRepo.commits(masterHash.hex() + ".." + headHash.hex())) {
                commits = tempCommits.stream()
                                     .map(Commit::hash)
                                     .collect(Collectors.toSet());
            }
            assertTrue(commits.contains(otherHash1));
            assertTrue(commits.contains(otherHash2));
            assertFalse(commits.contains(mergeHash));

            // Author and committer should updated in the merge commit
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
        }
    }

    @Test
    void branchMergeRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.url(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.url(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something new to master
            localRepo.checkout(updatedMaster, true);
            var newMaster = Files.writeString(localRepo.root().resolve("newmaster.txt"), "New on master", StandardCharsets.UTF_8);
            localRepo.add(newMaster);
            var newMasterHash = localRepo.commit("New commit on master", "some", "some@one");
            localRepo.push(newMasterHash, author.url(), "master");

            // Let the bot notice
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            // The change should now be present on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            // The commits from the "other" branch should be preserved and not squashed (but not the merge commit)
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            Set<Hash> commits;
            try (var tempCommits = pushedRepo.commits(masterHash.hex() + ".." + headHash.hex())) {
                commits = tempCommits.stream()
                        .map(Commit::hash)
                        .collect(Collectors.toSet());
            }
            assertTrue(commits.contains(otherHash1));
            assertTrue(commits.contains(otherHash2));
            assertFalse(commits.contains(mergeHash));

            // Author and committer should updated in the merge commit
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());
        }
    }

    @Test
    void branchMergeAdditionalCommits(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.url(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.url(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Our own merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something new to master
            localRepo.checkout(updatedMaster, true);
            var newMaster = Files.writeString(localRepo.root().resolve("newmaster.txt"), "New on master", StandardCharsets.UTF_8);
            localRepo.add(newMaster);
            var newMasterHash = localRepo.commit("New commit on master", "some", "some@one");
            localRepo.push(newMasterHash, author.url(), "master");

            // Let the bot notice
            TestBotRunner.runPeriodicItems(mergeBot);

            // Add another commit on top of the merge commit
            localRepo.checkout(mergeHash, true);
            var extraHash = CheckableRepository.appendAndCommit(localRepo, "Fixing up stuff after merge");
            localRepo.push(extraHash, author.url(), "edit");

            // Let the bot notice again
            TestBotRunner.runPeriodicItems(mergeBot);

            // Merge the latest from master
            localRepo.merge(newMasterHash);
            var latestMergeHash = localRepo.commit("Our to be squashed merge commit", "some", "some@one");
            localRepo.push(latestMergeHash, author.url(), "edit");

            // Let the bot notice again
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            // The change should now be present on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            // The commits from the "other" branch should be preserved and not squashed (but not the merge commit)
            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            String commits;
            try (var tempCommits = pushedRepo.commits(masterHash.hex() + ".." + headHash.hex())) {
                commits = tempCommits.stream()
                                     .map(c -> c.hash().hex() + ":" + c.message().get(0))
                                     .collect(Collectors.joining(","));
            }
            assertTrue(commits.contains(otherHash1.hex() + ":First other"));
            assertTrue(commits.contains(otherHash2.hex() + ":Second other"));
            assertFalse(commits.contains("Our own merge commit"));

            // Author and committer should updated in the merge commit
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.java.net", headCommit.committer().email());

            // The latest content from the source and the updated master should be present
            assertEquals("New on master", Files.readString(pushedRepoFolder.resolve("newmaster.txt")));
            assertEquals("Unrelated", Files.readString(pushedRepoFolder.resolve("unrelated.txt")));
        }
    }

    @Test
    void invalidMergeCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.url(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot will create a proper merge commit
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            // The change should now be present with correct parents on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var head = pushedRepo.commitMetadata("HEAD^!").get(0);
            assertEquals(2, head.parents().size());
            assertEquals(masterHash, head.parents().get(0));
            assertEquals(otherHash, head.parents().get(1));
        }
    }

    @Test
    void invalidSourceRepo(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.url(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + "xyz" + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a failure message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("did not complete successfully"))
                          .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            var check = pr.checks(mergeHash).get("jcheck");
            assertEquals("- Could not find project `" + author.name() + "xyz` - check that it is correct.", check.summary().orElseThrow());
        }
    }

    @Test
    void invalidSourceBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.url(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":otherxyz");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a failure message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("did not complete successfully"))
                          .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            var check = pr.checks(mergeHash).get("jcheck");
            assertEquals("- Could not find the branch or tag `otherxyz` in the project `" + author.name() + "` - check that it is correct.", check.summary().orElseThrow());
        }
    }

    @Test
    void inferredSourceProject(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.url(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge otherxyz");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a failure message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("did not complete successfully"))
                          .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            var check = pr.checks(mergeHash).get("jcheck");
            assertEquals("- Could not find project `otherxyz` - check that it is correct.", check.summary().orElseThrow());
        }
    }

    @Test
    void wrongSourceBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var other1Hash = CheckableRepository.appendAndCommit(localRepo, "Change in other1",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(other1Hash, author.url(), "other1", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make yet another change in another branch
            var other2Hash = CheckableRepository.appendAndCommit(localRepo, "Change in other2",
                                                                "Unrelated\n\nReviewed-by: integrationreviewer2");
            localRepo.push(other2Hash, author.url(), "other2", true);

            // Make a change with a corresponding PR
            localRepo.checkout(masterHash, true);
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(other1Hash, "ours");
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other2");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a failure message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("did not complete successfully"))
                          .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            var check = pr.checks(mergeHash).get("jcheck");
            assertEquals("- A merge PR must contain at least one commit from the source branch that is not already present in the target.", check.summary().orElseThrow());
        }
    }

    @Test
    void invalidAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.url(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a need for sponsor
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Afterwards, your sponsor types `/sponsor`"))
                          .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));
        }
    }

    @Test
    void unrelatedHistory(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            // Need to force merge unrelated histories
            assumeTrue(author.repositoryType().equals(VCS.GIT));

            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();

            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make an unrelated change in another branch
            var unrelatedRepoFolder = tempFolder.path().resolve("unrelated");
            var unrelatedRepo = CheckableRepository.init(unrelatedRepoFolder, author.repositoryType(), Path.of("anotherfile.txt"));
            unrelatedRepo.amend("Unrelated initial commit\n\nReviewed-by: integrationreviewer2", "some", "one@mail");
            var otherHash = CheckableRepository.appendAndCommit(unrelatedRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            unrelatedRepo.push(otherHash, author.url(), "other", true);
            localRepo.fetch(author.url(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            localRepo.commit("Unrelated", "some", "some@one");
            var mergeCmd = Process.command("git", "merge", "--no-commit", "--allow-unrelated-histories", "-s", "ours", otherHash.hex())
                                  .workdir(localRepo.root())
                                  .environ("GIT_AUTHOR_NAME", "some")
                                  .environ("GIT_AUTHOR_EMAIL", "some@one")
                                  .environ("GIT_COMMITTER_NAME", "another")
                                  .environ("GIT_COMMITTER_EMAIL", "another@one")
                                  .execute();
            mergeCmd.check();

            //localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a failure message
            var error = pr.comments().stream()
                    .filter(comment -> comment.body().contains("did not complete successfully"))
                    .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            var check = pr.checks(mergeHash).get("jcheck");
            assertEquals("- The target and the source branches do not share common history - cannot merge them.", check.summary().orElseThrow());
        }
    }

    @Test
    void invalidSyntax(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType(), Path.of("appendable.txt"), Set.of("merge"), "1.0");
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.url(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge this or that");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with a failure message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("did not complete successfully"))
                          .count();
            assertEquals(1, error, () -> pr.comments().stream().map(Comment::body).collect(Collectors.joining("\n\n")));

            var check = pr.checks(mergeHash).get("jcheck");
            assertEquals("- Could not determine the source for this merge. A Merge PR title must be specified on the format: Merge `project`:`branch` to allow verification of the merge contents.", check.summary().orElseThrow());
        }
    }

    @Test
    void foreignCommitWarning(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.url(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.url(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated", StandardCharsets.UTF_8);
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit( "Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.url(), "master");

            // Go back to the original master again
            localRepo.checkout(masterHash, true);
            var editChange = Files.writeString(localRepo.root().resolve("edit.txt"), "Edit", StandardCharsets.UTF_8);
            localRepo.add(editChange);
            var editHash = localRepo.commit( "Edit", "some", "some@one");

            // Merge the latest commit from master
            localRepo.merge(updatedMaster);
            var masterMergeHash = localRepo.commit("Master merge commit", "some", "some@one");
            localRepo.push(masterMergeHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "1234: A change");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Merging latest master should not trigger a warning
            assertEquals(0, pr.comments().size());

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.url(), "edit", true);

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // There should be a warning
            assertLastCommentContains(pr, "This pull request looks like it contains a merge commit");
        }
    }
}
