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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.jcheck.ReviewersCheck;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class BackportTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void withSummary(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "This is a summary\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of("This is a summary"), message.summaries());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void withMultipleIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue1Number = issue1.id().split("-")[1];
            var issue2Number = issue2.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "This is a summary\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(2, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals("Another issue", message.issues().get(1).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of("This is a summary"), message.summaries());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void nonExitingCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding backport PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport 0123456789012345678901234567890123456789");

            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "could not find any commit with hash `0123456789012345678901234567890123456789`");
            assertFalse(pr.labelNames().contains("backport"));

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(1, pr.comments().size());
        }
    }

    /**
     * Tests that setting a backport title to points to the head commit of the PR
     * itself is handled as an error.
     */
    @Test
    void prHeadCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                    "\n" +
                    "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            // Create the backport with the hash from the PR branch
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + editHash.hex());

            // The bot should detect the bad hash
            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "the given backport hash");
            assertLastCommentContains(pr, "is an ancestor of your proposed change.");
            assertFalse(pr.labelNames().contains("backport"));

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(1, pr.comments().size());
        }
    }

    /**
     * Tests that setting a backport title to points to an ancestor of the head commit of the PR
     * itself is handled as an error.
     */
    @Test
    void prAncestorOfHeadCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                    "\n" +
                    "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            // Add another change on top of the backport
            var editHash2 = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash2, author.url(), "refs/heads/edit", true);
            // Create the backport with the hash from the PR branch
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + editHash.hex());

            // The bot should detect the bad hash
            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "the given backport hash");
            assertLastCommentContains(pr, "is an ancestor of your proposed change.");
            assertFalse(pr.labelNames().contains("backport"));

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(1, pr.comments().size());
        }
    }

    @Test
    void cleanBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex(), List.of());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));
            assertFalse(pr.body().contains(ReviewersCheck.DESCRIPTION), "Reviewer requirement found in pr body");
            assertFalse(pr.body().contains(CheckRun.MSG_EMPTY_BODY), "Body not empty requirement found in pr body");

            // The bot should have added the "clean" label
            assertTrue(pr.labelNames().contains("clean"));
        }
    }

    @Test
    void fuzzyCleanBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());

            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a\nb\nc\nd\n");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var masterHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");

            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue2Number = issue2.id().split("-")[1];
            var upstreamMessage = issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var upstreamHash = localRepo.commit(upstreamMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(upstreamHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            Files.writeString(newFile, "a\nb\nc\ne\nd\n");
            localRepo.add(newFile);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + upstreamHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + upstreamHash.hex() + " -->"));
            assertEquals(issue2Number + ": Another issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // The bot should have added the "clean" label
            assertTrue(pr.labelNames().contains("clean"));
        }
    }

    @Test
    void notCleanBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());

            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a\nb\nc\nd");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var masterHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");

            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue2Number = issue2.id().split("-")[1];
            var upstreamMessage = issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var upstreamHash = localRepo.commit(upstreamMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(upstreamHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            Files.writeString(newFile, "a\nb\nc\nd\nd");
            localRepo.add(newFile);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + upstreamHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + upstreamHash.hex() + " -->"));
            assertEquals(issue2Number + ": Another issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));
            assertTrue(pr.body().contains(ReviewersCheck.DESCRIPTION), "Reviewer requirement not found in pr body");

            // The bot should not have added the "clean" label
            assertFalse(pr.labelNames().contains("clean"));
        }
    }

    @Test
    void notCleanBackportAdditionalFile(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());

            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a\nb\nc\nd");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var masterHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");

            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue2Number = issue2.id().split("-")[1];
            var upstreamMessage = issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var upstreamHash = localRepo.commit(upstreamMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(upstreamHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var anotherFile = localRepo.root().resolve("another_file.txt");
            Files.writeString(anotherFile, "f\ng\nh\ni");
            localRepo.add(anotherFile);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + upstreamHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + upstreamHash.hex() + " -->"));
            assertEquals(issue2Number + ": Another issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // The bot should not have added the "clean" label
            assertFalse(pr.labelNames().contains("clean"));
        }
    }

    @Test
    void cleanBackportFromCommitterCanBeIntegrated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message and that the PR is ready
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");
            assertTrue(pr.labelNames().contains("ready"));
            assertTrue(pr.labelNames().contains("rfr"));
            assertTrue(pr.labelNames().contains("clean"));
            assertTrue(pr.labelNames().contains("backport"));

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of(), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void cleanBackportFromAuthorCanBeIntegrated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message and that the PR is ready
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");
            assertTrue(pr.labelNames().contains("ready"));
            assertTrue(pr.labelNames().contains("rfr"));
            assertTrue(pr.labelNames().contains("clean"));
            assertFalse(pr.labelNames().contains("sponsor"));
            assertTrue(pr.labelNames().contains("backport"));

            // Integrate
            var prAsAuthor = author.pullRequest(pr.id());
            prAsAuthor.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply with a sponsor message
            assertTrue(pr.labelNames().contains("sponsor"));

            // Sponsor the commit
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            for (var comment : pr.comments()) {
                System.out.println(comment.body());
            }
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertNotEquals(commit.author(), commit.committer());
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of(), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void whitespaceInMiddle(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport  " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));
        }
    }

    @Test
    void whitespaceAtEnd(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex() + " ");

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));
        }
    }

    @Test
    void noWhitespace(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport" + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));
        }
    }

    @Test
    void commitWithMismatchingIssueTitle(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": A issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.of(releaseHash), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void badIssueInOriginal(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .issueProject(issues)
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + " An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.java.net");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("<!-- backport error -->"));
            assertTrue(backportComment.contains("the commit `" + releaseHash.hex() + "` does not refer to an issue"));
            assertFalse(pr.labelNames().contains("backport"));
        }
    }

    @Test
    void noHashOnlyIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];

            // Create change
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.java.net");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create various kinds of bad pull request titles
            // Use a bad project
            var pr = credentials.createPullRequest(author, "master", "edit",
                    "Backport " + "FOO-" + issue1.id().split("-")[1]);
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(0).body();
            assertTrue(backportComment.contains("does not match project"));
            assertFalse(pr.labelNames().contains("backport"));

            // Use bad issue ID
            pr.setTitle("Backport TEST-4711");
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("does not exist in project"));
            assertFalse(pr.labelNames().contains("backport"));

            // Use different kinds of good titles
            // Use the full issue ID
            pr.setTitle("Backport " + issue1.id());
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(2).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with the original issue"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // Set the title without project name
            pr.setTitle("Backport " + issue1.id().split("-")[1]);
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(3).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with the original issue"));
            assertEquals(issue1Number + ": An issue", pr.title());
            assertTrue(pr.labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            var prAsCommitter = author.pullRequest(pr.id());
            prAsCommitter.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().get(pr.comments().size() - 1);
            var lines = comment.body().split("\n");
            var pattern = Pattern.compile(".* Pushed as commit ([0-9a-z]{40}).*");
            for (var line : lines) {
                var m = pattern.matcher(line);
                if (m.matches()) {
                    hex = m.group(1);
                    break;
                }
            }
            assertNotNull(hex);
            assertEquals(40, hex.length());
            localRepo.checkout(localRepo.defaultBranch());
            localRepo.pull(author.url().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            assertEquals(Optional.empty(), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }
}
