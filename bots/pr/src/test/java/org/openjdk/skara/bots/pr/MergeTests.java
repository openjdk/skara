/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.CheckStatus;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.process.Process;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.forge.CheckStatus.SUCCESS;

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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                                                                "First other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                                                                "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // There is a merge commit at HEAD, but the merge commit is empty
            assertTrue(pr.store().labelNames().contains("clean"));

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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            assertEquals("Merge " + author.name() + ":other_/-1.2", headCommit.message().get(0));
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
        }
    }

    @Test
    void branchMergeWithReviewMergeRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build())
                    .reviewMerge(MergePullRequestReviewConfiguration.ALWAYS).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                    "First other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // pr should not be ready, because review needed for merge pull requests
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("- [ ] Change must be properly reviewed"));

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);

            assertTrue(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("- [x] Change must be properly reviewed"));

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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            assertEquals("Merge " + author.name() + ":other_/-1.2", headCommit.message().get(0));
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
            assertTrue(String.join("", headCommit.message())
                            .matches(".*Reviewed-by: integrationreviewer2$"),
                    String.join("", headCommit.message()));
        }
    }

    @Test
    void branchMergeWithReviewersCommand(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                    "First other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            pr.addComment("/reviewers 1");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);
            assertTrue(pr.store().labelNames().contains("clean"));
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("[ ] Change must be properly reviewed (1 review required, with at least 1 [Reviewer](https://openjdk.org/bylaws#reviewer))"));

            // Approve it
            var reviewerPr = integrator.pullRequest(pr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "LGTM");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertTrue(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("[x] Change must be properly reviewed (1 review required, with at least 1 [Reviewer](https://openjdk.org/bylaws#reviewer))"));

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(1, pushed);
        }
    }


    @Test
    void runJCheckTwiceInMergePR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator1 = credentials.getHostedRepository();
            var integrator2 = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator1.forge().currentUser().id())
                    .addReviewer(integrator2.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator1).censusRepo(censusBuilder.build())
                    .reviewMerge(MergePullRequestReviewConfiguration.JCHECK).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch

            var checkConf = localRepoFolder.resolve(".jcheck/conf");
            try (var output = Files.newBufferedWriter(checkConf)) {
                output.append("[general]\n");
                output.append("project=test\n");
                output.append("jbs=tstprj\n");
                output.append("\n");
                output.append("[checks]\n");
                output.append("error=");
                output.append(String.join(",", Set.of("author", "reviewers", "whitespace")));
                output.append("\n\n");
                output.append("[census]\n");
                output.append("version=0\n");
                output.append("domain=openjdk.org\n");
                output.append("\n");
                output.append("[checks \"whitespace\"]\n");
                output.append("files=.*\\.txt\n");
                output.append("\n");
                output.append("[checks \"reviewers\"]\n");
                output.append("reviewers=2\n");
                output.append("merge=check");
            }
            localRepo.add(checkConf);
            var otherHash1 = localRepo.commit("add conf to master", "testauthor", "ta@none.none");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2");

            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // pr should not be ready, because JCheck conf updated in source branch
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("Too few reviewers with at least role reviewer found (have 0, need at least 2) (failed with updated jcheck configuration in pull request)"));

            // Approve it as another user
            var approvalPr1 = integrator1.pullRequest(pr.id());
            approvalPr1.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("Too few reviewers with at least role reviewer found (have 1, need at least 2)"));

            var approvalPr2 = integrator2.pullRequest(pr.id());
            approvalPr2.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(mergeBot);
            assertTrue(pr.store().labelNames().contains("ready"));
            assertFalse(pr.store().body().contains("Too few reviewers with at least role reviewer found"));
        }
    }

    @Test
    void mergeAllowed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                                         .repo(integrator)
                                         .censusRepo(censusBuilder.build())
                                         .integrators(Set.of(author.forge().currentUser().username()))
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            assertLastCommentContains(pr, "Pushed as commit");
        }
    }

    @Test
    void mergeDisallowed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                                         .repo(integrator)
                                         .censusRepo(censusBuilder.build())
                                         .integrators(Set.of(integrator.forge().currentUser().username()))
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            assertLastCommentContains(pr, "Your integration request cannot be fulfilled at this time");
        }
    }

    @Test
    void hashMerge(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + otherHash2.hex());

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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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

            // Author and committer should be updated in the merge commit
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Merge " + otherHash2.hex(), headCommit.message().get(0));
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
        }
    }

    @Test
    void hashMergeExisting(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Push the new commits to master and then return to the original one
            localRepo.push(otherHash2, author.authenticatedUrl(), "master");
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + otherHash2.hex());

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
    void branchMergeRestrictedMessage(TestInfo testInfo) throws IOException {
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
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType(),
                                                     Path.of("appendable.txt"), Set.of("merge"), "1.0");
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            assertLastCommentContains(pr, "Pushed as commit");

            // The change should now be present on the master branch
            var pushedRepoFolder = tempFolder.path().resolve("pushedrepo");
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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

            // The commit message should be just "Merge"
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
            assertEquals("Merge", headCommit.message().get(0));
            assertEquals("Generated Committer 1", headCommit.author().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            var tag = localRepo.tag(otherHash2, "othertag", "Tagging other", "tagger", "tagger@one");
            var tagHash = localRepo.lookup(tag).orElseThrow().hash();
            localRepo.push(tagHash, author.authenticatedUrl(), "refs/tags/othertag");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something new to master
            localRepo.checkout(updatedMaster, true);
            var newMaster = Files.writeString(localRepo.root().resolve("newmaster.txt"), "New on master");
            localRepo.add(newMaster);
            var newMasterHash = localRepo.commit("New commit on master", "some", "some@one");
            localRepo.push(newMasterHash, author.authenticatedUrl(), "master");

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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Our own merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Push something new to master
            localRepo.checkout(updatedMaster, true);
            var newMaster = Files.writeString(localRepo.root().resolve("newmaster.txt"), "New on master");
            localRepo.add(newMaster);
            var newMasterHash = localRepo.commit("New commit on master", "some", "some@one");
            localRepo.push(newMasterHash, author.authenticatedUrl(), "master");

            // Let the bot notice
            TestBotRunner.runPeriodicItems(mergeBot);

            // Add another commit on top of the merge commit
            localRepo.checkout(mergeHash, true);
            var extraHash = CheckableRepository.appendAndCommit(localRepo, "Fixing up stuff after merge");
            localRepo.push(extraHash, author.authenticatedUrl(), "edit");

            // Let the bot notice again
            TestBotRunner.runPeriodicItems(mergeBot);

            // Merge the latest from master
            localRepo.merge(newMasterHash);
            var latestMergeHash = localRepo.commit("Our to be squashed merge commit", "some", "some@one");
            localRepo.push(latestMergeHash, author.authenticatedUrl(), "edit");

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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            assertEquals("integrationcommitter1@openjdk.org", headCommit.author().email());
            assertEquals("Generated Committer 1", headCommit.committer().name());
            assertEquals("integrationcommitter1@openjdk.org", headCommit.committer().email());

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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            var pushedRepo = Repository.materialize(pushedRepoFolder, author.authenticatedUrl(), "master");
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + TestHost.NON_EXISTING_REPO + ":other");

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
            assertEquals("- Could not find project `" + TestHost.NON_EXISTING_REPO + "` - check that it is correct.", check.summary().orElseThrow());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + TestHost.NON_EXISTING_REPO);

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
            assertEquals("- Could not find project `" + TestHost.NON_EXISTING_REPO + "` - check that it is correct.", check.summary().orElseThrow());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var other1Hash = CheckableRepository.appendAndCommit(localRepo, "Change in other1",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(other1Hash, author.authenticatedUrl(), "other1", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make yet another change in another branch
            var other2Hash = CheckableRepository.appendAndCommit(localRepo, "Change in other2",
                                                                "Unrelated\n\nReviewed-by: integrationreviewer2");
            localRepo.push(other2Hash, author.authenticatedUrl(), "other2", true);

            // Make a change with a corresponding PR
            localRepo.checkout(masterHash, true);
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(other1Hash, "ours");
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make an unrelated change in another branch
            var unrelatedRepoFolder = tempFolder.path().resolve("unrelated");
            var unrelatedRepo = CheckableRepository.init(unrelatedRepoFolder, author.repositoryType(), Path.of("anotherfile.txt"));
            unrelatedRepo.amend("Unrelated initial commit\n\nReviewed-by: integrationreviewer2", "some", "one@mail");
            var otherHash = CheckableRepository.appendAndCommit(unrelatedRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            unrelatedRepo.push(otherHash, author.authenticatedUrl(), "other", true);
            localRepo.fetch(author.authenticatedUrl(), "other").orElseThrow();

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            localRepo.commit("Unrelated", "some", "some@one");
            try (var p = Process.command("git", "merge", "--no-commit", "--allow-unrelated-histories", "-s", "ours", otherHash.hex())
                    .workdir(localRepo.root())
                    .environ("GIT_AUTHOR_NAME", "some")
                    .environ("GIT_AUTHOR_EMAIL", "some@one")
                    .environ("GIT_COMMITTER_NAME", "another")
                    .environ("GIT_COMMITTER_EMAIL", "another@one")
                    .execute()) {
                p.check();
            }

            //localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "other", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
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
            assertEquals("- Could not determine the source for this merge. A Merge PR title must be specified in the format: `^Merge ([-/.\\w:+]+)$` to allow verification of the merge contents.", check.summary().orElseThrow());
        }
    }

    @Test
    void branchWithPlus(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change in another branch
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Change in other",
                                                                "Other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash, author.authenticatedUrl(), "branch-a+b", true);

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            localRepo.merge(otherHash);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge branch-a+b");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // should be successful
            var check = pr.checks(mergeHash).get("jcheck");
            assertSame(SUCCESS, check.status());
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                                                                 "First other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                                                                 "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit( "Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            // Go back to the original master again
            localRepo.checkout(masterHash, true);
            var editChange = Files.writeString(localRepo.root().resolve("edit.txt"), "Edit");
            localRepo.add(editChange);
            var editHash = localRepo.commit( "Edit", "some", "some@one");

            // Merge the latest commit from master
            localRepo.merge(updatedMaster);
            var masterMergeHash = localRepo.commit("Master merge commit", "some", "some@one");
            localRepo.push(masterMergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "1234: A change");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // Merging latest master should not trigger a warning
            assertEquals(1, pr.comments().size());

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // There should be a warning
            assertLastCommentContains(pr, "This pull request contains merges that bring in commits not present");
        }
    }

    @Test
    void noMergeCommitAtHead(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                    "First other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var pr = credentials.createPullRequest(author, "master", "other_/-1.2", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            assertTrue(pr.store().labelNames().contains("clean"));
        }
    }

    @Test
    void MergeCommitWithResolutionAtHead(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                    "First other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // update
            var defaultAppendable = Files.readString(localRepo.root().resolve("appendable.txt"));
            var newAppendable = "11111\n" + defaultAppendable;
            Files.writeString(localRepo.root().resolve("appendable.txt"), newAppendable);
            localRepo.add(localRepo.root().resolve("appendable.txt"));
            localRepo.commit("updated", "test", "test@test.com");

            localRepo.merge(otherHash2);
            var mergeHash = localRepo.commit("Merge commit\n\n This is Body", "some", "some@one");

            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // There is a merge commit at HEAD and the merge commit is not empty
            assertFalse(pr.store().labelNames().contains("clean"));
        }
    }

    @Test
    void EmptyMergeCommitAtHead(TestInfo testInfo) throws IOException {
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
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var feature = localRepo.branch(masterHash, "feature");
            localRepo.checkout(feature);
            var featureHash = CheckableRepository.appendAndCommit(localRepo);

            localRepo.checkout(masterHash);
            localRepo.merge(featureHash, Repository.FastForward.DISABLE);
            var mergeHash = localRepo.commit("merged\n\n This is Body", "xxx", "xxx@gmail.com");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // There is a merge commit at HEAD and the merge commit is not empty
            assertTrue(pr.store().labelNames().contains("clean"));
        }
    }

    @Test
    void mergeSourceInvalid(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository("openjdk/jdk");
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                    .repo(author)
                    .censusRepo(censusBuilder.build())
                    .mergeSources(Set.of("openjdk/playground", "openjdk/skara"))
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge openjdk/test:other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);
            assertEquals(2, pr.comments().size());
            assertLastCommentContains(pr, "can not be source repo for merge-style pull requests in this repository.");
        }
    }

    @Test
    void JCheckFailInOneOfTheCommits(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).jcheckMerge(true).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                    "First other_/-1.2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2\n\r",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // There is a merge commit at HEAD, but the merge commit is empty
            assertTrue(pr.store().labelNames().contains("clean"));

            // Push it
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should not push the commit
            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("Pushed as commit"))
                    .count();
            assertEquals(0, pushed);

            assertTrue(pr.store().body().contains("Too few reviewers with at least role reviewer found (have 0, need at least 1) (in commit `" + otherHash1.hex() + "` with target configuration)"));
            assertTrue(pr.store().body().contains("Whitespace errors (in commit `" + otherHash2.hex() + "` with target configuration)"));
        }
    }

    @Test
    void JCheckConfInvalidInOneOfTheCommits(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).jcheckMerge(true).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                    "First other_/-1.2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);

            var confPath = localRepoFolder.resolve(".jcheck/conf");
            Files.writeString(confPath, "Hello there!");
            localRepo.add(confPath);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2\n\r",
                    "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2");

            // Let the bot check the status
            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(mergeBot));

            var checks = pr.checks(mergeHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            assertEquals(CheckStatus.FAILURE, check.status());
            assertEquals("line 0: entry must be of form 'key = value'", check.summary().get());
            assertEquals("Exception occurred during merge jcheck with target conf in commit " + otherHash2.hex() + " - the operation will be retried",
                    check.title().get());
        }
    }

    @Test
    void noSecondParentSpecified(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                                         .repo(integrator)
                                         .censusRepo(censusBuilder.build())
                                         .reviewMerge(MergePullRequestReviewConfiguration.ALWAYS)
                                         .jcheckMerge(true)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other",
                    "First other\n\nReviewed-by: integrationreviewer2");
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other",
                    "Second other\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var pr = credentials.createPullRequest(author, "master", "other", "Merge");

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(mergeBot);

            // The PR title should have been updated
            assertEquals("Merge " + otherHash2.hex(), pr.title());
            assertLastCommentContains(pr,
                    "The second parent of the resulting merge commit from this pull request will be set to `" +
                    otherHash2.hex() + "`");
        }
    }
}
