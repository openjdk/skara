/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertFirstCommentContains;
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding backport PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport 0123456789012345678901234567890123456789");

            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "could not find any commit with hash `0123456789012345678901234567890123456789`");
            assertFalse(pr.store().labelNames().contains("backport"));

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(2, pr.comments().size());
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            // Create the backport with the hash from the PR branch
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + editHash.hex());

            // The bot should detect the bad hash
            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "the given backport hash");
            assertLastCommentContains(pr, "is an ancestor of your proposed change.");
            assertFalse(pr.store().labelNames().contains("backport"));

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(2, pr.comments().size());
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            // Add another change on top of the backport
            var editHash2 = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash2, author.authenticatedUrl(), "refs/heads/edit", true);
            // Create the backport with the hash from the PR branch
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + editHash.hex());

            // The bot should detect the bad hash
            // The bot should reply with a backport error
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(pr, "<!-- backport error -->");
            assertLastCommentContains(pr, ":warning:");
            assertLastCommentContains(pr, "the given backport hash");
            assertLastCommentContains(pr, "is an ancestor of your proposed change.");
            assertFalse(pr.store().labelNames().contains("backport"));

            // Re-running the bot should not cause any more error comments
            TestBotRunner.runPeriodicItems(bot);
            assertEquals(2, pr.comments().size());
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex(), List.of());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().body().contains(ReviewersCheck.DESCRIPTION), "Reviewer requirement found in pr body");
            assertFalse(pr.store().body().contains(CheckRun.MSG_EMPTY_BODY), "Body not empty requirement found in pr body");

            // The bot should have added the "clean" label
            assertTrue(pr.store().labelNames().contains("clean"));
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
                    .issuePRMap(new HashMap<>())
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
            var masterHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");

            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue2Number = issue2.id().split("-")[1];
            var upstreamMessage = issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var upstreamHash = localRepo.commit(upstreamMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(upstreamHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            Files.writeString(newFile, "a\nb\nc\ne\nd\n");
            localRepo.add(newFile);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + upstreamHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + upstreamHash.hex() + " -->"));
            assertEquals(issue2Number + ": Another issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // The bot should have added the "clean" label
            assertTrue(pr.store().labelNames().contains("clean"));
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
                    .issuePRMap(new HashMap<>())
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
            var masterHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");

            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue2Number = issue2.id().split("-")[1];
            var upstreamMessage = issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var upstreamHash = localRepo.commit(upstreamMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(upstreamHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            Files.writeString(newFile, "a\nb\nc\nd\nd");
            localRepo.add(newFile);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + upstreamHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + upstreamHash.hex() + " -->"));
            assertEquals(issue2Number + ": Another issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            assertTrue(pr.store().body().contains(ReviewersCheck.DESCRIPTION), "Reviewer requirement not found in pr body");

            // The bot should not have added the "clean" label
            assertFalse(pr.store().labelNames().contains("clean"));
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
                    .issuePRMap(new HashMap<>())
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
            var masterHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");

            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var issue2 = credentials.createIssue(issues, "Another issue");
            var issue2Number = issue2.id().split("-")[1];
            var upstreamMessage = issue2Number + ": Another issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var upstreamHash = localRepo.commit(upstreamMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(upstreamHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            Files.writeString(newFile, "a\nb\nc\nd\ne");
            localRepo.add(newFile);
            var anotherFile = localRepo.root().resolve("another_file.txt");
            Files.writeString(anotherFile, "f\ng\nh\ni");
            localRepo.add(anotherFile);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + upstreamHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + upstreamHash.hex() + " -->"));
            assertEquals(issue2Number + ": Another issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // The bot should not have added the "clean" label
            assertFalse(pr.store().labelNames().contains("clean"));
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message and that the PR is ready
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");
            assertTrue(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().labelNames().contains("rfr"));
            assertTrue(pr.store().labelNames().contains("clean"));
            assertTrue(pr.store().labelNames().contains("backport"));

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message and that the PR is ready
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");
            assertTrue(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().labelNames().contains("rfr"));
            assertTrue(pr.store().labelNames().contains("clean"));
            assertFalse(pr.store().labelNames().contains("sponsor"));
            assertTrue(pr.store().labelNames().contains("backport"));

            // Integrate
            var prAsAuthor = author.pullRequest(pr.id());
            prAsAuthor.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply with a sponsor message
            assertTrue(pr.store().labelNames().contains("sponsor"));

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
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport  " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex() + " ");

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
        }
    }

    @Test
    void caseInsensitive(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "bacKporT" + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport" + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("<!-- backport error -->"));
            assertTrue(backportComment.contains("the commit `" + releaseHash.hex() + "` does not refer to an issue"));
            assertFalse(pr.store().labelNames().contains("backport"));
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];

            // Create change
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);

            // Create various kinds of bad pull request titles
            // Use a bad project
            var pr = credentials.createPullRequest(author, "master", "edit",
                    "Backport " + "FOO-" + issue1.id().split("-")[1]);
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("does not match project"));
            assertFalse(pr.store().labelNames().contains("backport"));

            // Use bad issue ID
            pr.setTitle("Backport TEST-4711");
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(2).body();
            assertTrue(backportComment.contains("does not exist in project"));
            assertFalse(pr.store().labelNames().contains("backport"));

            // Use different kinds of good titles
            // Use the full issue ID
            pr.setTitle("Backport " + issue1.id());
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(3).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with the original issue"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Case insensitive
            pr.setTitle("bAcKpoRT" + issue1.id());
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(4).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with the original issue"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Set the title without project name
            pr.setTitle("Backport " + issue1.id().split("-")[1]);
            TestBotRunner.runPeriodicItems(bot);
            backportComment = pr.comments().get(5).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with the original issue"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            var prAsCommitter = author.pullRequest(pr.id());
            prAsCommitter.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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

    /**
     * Tests that the correct original hash is used if the PR is updated with a new
     * "Backport HASH" title
     */
    @Test
    void updateOriginal(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // Create the same fix in another release branch
            var releaseBranch2 = localRepo.branch(masterHash, "release2");
            localRepo.checkout(releaseBranch2);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello2");
            localRepo.add(newFile);
            var releaseHash2 = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash2, author.authenticatedUrl(), "refs/heads/release2", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile3 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile3, "hello");
            localRepo.add(newFile3);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            assertTrue(pr.store().labelNames().contains("clean"));
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Update the PR title and use the hash from release2 instead
            pr.setTitle("Backport " + releaseHash2.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment2 = pr.comments().get(2).body();
            assertTrue(backportComment2.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment2.contains("<!-- backport " + releaseHash2.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            // The backport is no longer clean as the release2 version of the change was different
            assertFalse(pr.store().labelNames().contains("clean"));
            assertFirstCommentContains(pr, "This change is no longer ready for integration - check the PR body for details");

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
            var commit = localRepo.lookup(new Hash(hex)).orElseThrow();

            var message = CommitMessageParsers.v1.parse(commit);
            assertEquals(1, message.issues().size());
            assertEquals("An issue", message.issues().get(0).description());
            assertEquals(List.of("integrationreviewer3"), message.reviewers());
            // Verify that the correct original hash is present in the commit message
            assertEquals(Optional.of(releaseHash2), message.original());
            assertEquals(List.of(), message.contributors());
            assertEquals(List.of(), message.summaries());
            assertEquals(List.of(), message.additional());
        }
    }

    @Test
    void updateOriginalHashFromWrongToCorrect(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            var newFileSZ = localRepo.root().resolve("a_new_file2.txt");
            Files.writeString(newFileSZ, "hello2");
            localRepo.add(newFileSZ);
            var releaseHash2 =localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash2, author.authenticatedUrl(), "refs/heads/release", true);


            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            // create a pr with wrong hash
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash2.hex());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash2.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            // The pr must not contain clean label
            assertFalse(pr.store().labelNames().contains("clean"));

            // correct the Backport original commit Hash
            pr.setTitle("Backport "+releaseHash.hex());
            TestBotRunner.runPeriodicItems(bot);
            var backportComment2 = pr.comments().get(2).body();
            assertTrue(backportComment2.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment2.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            // The pr must contain clean label
            assertTrue(pr.store().labelNames().contains("clean"));

            // Approve PR and re-run bot
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
    void cleanBackportRequiresReview(TestInfo testInfo) throws IOException {
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
                    .reviewCleanBackport(true)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex());

            // The bot should reply with a backport message and that the PR is not ready
            TestBotRunner.runPeriodicItems(bot);
            var backportComment = pr.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("rfr"));
            assertTrue(pr.store().labelNames().contains("clean"));
            assertTrue(pr.store().labelNames().contains("backport"));
            assertTrue(pr.store().body().contains("Change must be properly reviewed"));

            // Approve this pr as a reviewer
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(bot);
            assertTrue(pr.store().labelNames().contains("ready"));

            // Integrate
            author.pullRequest(pr.id());
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(bot);

            // Find the commit
            assertLastCommentContains(pr, "Pushed as commit");

            String hex = null;
            var comment = pr.comments().getLast();
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
            localRepo.pull(author.authenticatedUrl().toString(), "master", false);
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
    void cleanBackportWithReviewersCommand(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());

            var confPath = localRepo.root().resolve(".jcheck/conf");
            var defaultConf = Files.readString(confPath);
            var newConf = defaultConf.replace("reviewers=1", """
                    lead=0
                    reviewers=2
                    committers=0
                    authors=0
                    contributors=0
                    ignore=duke
                    """);
            Files.writeString(confPath, newConf);
            localRepo.add(confPath);
            var confHash = localRepo.commit("Change conf", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.url(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.url(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex(), List.of());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().body().contains(ReviewersCheck.DESCRIPTION), "Reviewer requirement found in pr body");
            assertFalse(pr.store().body().contains(CheckRun.MSG_EMPTY_BODY), "Body not empty requirement found in pr body");

            // The bot should have added the "clean" label
            assertTrue(pr.store().labelNames().contains("clean"));

            pr.addComment("/reviewers 1");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change is no longer ready for integration - check the PR body for details.");
            assertTrue(pr.store().body().contains("Change must be properly reviewed (2 reviews required"));
            assertFalse(pr.store().labelNames().contains("ready"));

            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "LGTM");
            var integratorPr = integrator.pullRequest(pr.id());
            integratorPr.addReview(Review.Verdict.APPROVED, "LGTM");

            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks.");
            assertTrue(pr.store().labelNames().contains("ready"));

            pr.addComment("/reviewers 3");
            TestBotRunner.runPeriodicItems(bot);
            assertFirstCommentContains(pr, "This change is no longer ready for integration - check the PR body for details.");
            assertTrue(pr.store().body().contains("Change must be properly reviewed (3 reviews required"));
            assertFalse(pr.store().labelNames().contains("ready"));
        }
    }

    @Test
    void cleanBackportWithCopyrightUpdate(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();

            // Initialize master branch
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, """
                    /*
                     * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
                     * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
                     */
                     Line1
                     Line2
                     """);
            localRepo.add(newFile);
            var updateHash = localRepo.commit("initial", "Test", "test@test.test");
            localRepo.push(updateHash, author.authenticatedUrl(), "master", true);

            // Initialize release branch
            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, """
                    /*
                     * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
                     * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
                     */
                     Line1
                     Line2
                     """);
            localRepo.add(newFile);
            var releaseHash = localRepo.commit("initial", "Test", "test@test.test");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // Update release branch
            newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, """
                    /*
                     * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
                     * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
                     */
                     Line1
                     Line2
                     Line3
                     """);
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                    "\n" +
                    "Reviewed-by: integrationreviewer2";
            var updateReleaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(updateReleaseHash, author.authenticatedUrl(), "refs/heads/release", true);


            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(updateHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, """
                    /*
                     * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
                     * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
                     */
                     Line1
                     Line2
                     Line3
                     """);
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + updateReleaseHash.hex(), List.of());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + updateReleaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().body().contains(ReviewersCheck.DESCRIPTION), "Reviewer requirement found in pr body");
            assertFalse(pr.store().body().contains(CheckRun.MSG_EMPTY_BODY), "Body not empty requirement found in pr body");

            // The bot should have added the "clean" label
            assertTrue(pr.store().labelNames().contains("clean"));
        }
    }


    @Test
    void incompleteBackport(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex(), List.of());
            // Force the pr to return incomplete diff
            pr.setReturnCompleteDiff(false);

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));
            assertLastCommentContains(pr, "This backport pull request is too large to be automatically evaluated as clean.");

            // The bot should not have added the "clean" label
            assertFalse(pr.store().labelNames().contains("clean"));
        }
    }

    @Test
    void cleanBackportWithProblemListIssue(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("appendable.txt"), Set.of("author", "reviewers", "whitespace", "problemlists"), "0.1");

            // Add problemlists configuration to conf
            var checkConf = tempFolder.path().resolve(".jcheck/conf");
            Files.writeString(checkConf, "\n[checks \"problemlists\"]\n", StandardOpenOption.APPEND);
            Files.writeString(checkConf, "dirs=test/jdk\n", StandardOpenOption.APPEND);
            // Create ProblemList.txt
            Files.createDirectories(tempFolder.path().resolve("test/jdk"));
            var problemList = tempFolder.path().resolve("test/jdk/ProblemList.txt");
            Files.writeString(problemList, "test 1 windows-all", StandardOpenOption.CREATE);
            localRepo.add(tempFolder.path().resolve(".jcheck/conf"));
            localRepo.add(problemList);
            localRepo.commit("add problemList.txt", "testauthor", "ta@none.none");

            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

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
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex(), List.of());

            // The bot should reply with a backport message
            TestBotRunner.runPeriodicItems(bot);
            // Should be marked as ready for review
            assertTrue(pr.store().labelNames().contains("rfr"));
            // Shouldn't be marked as ready
            assertFalse(pr.store().labelNames().contains("ready"));
        }
    }
}
