/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class CleanCommandTests {
    @Test
    void cleanCommandOnRegularPullRequestShouldNotWork(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");
            TestBotRunner.runPeriodicItems(prBot);

            assertFalse(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().labelNames().contains("clean"));

            // Try to issue the "/clean" PR command, should not work
            pr.addComment("/clean");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().labelNames().contains("clean"));
            assertLastCommentContains(pr, "Can only mark [backport pull requests]");
            assertLastCommentContains(pr, ", with an original hash, as clean");
        }
    }

    @Test
    void alreadyCleanPullRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
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
            var comments = pr.comments();
            var backportComment = comments.get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", pr.store().title());
            assertTrue(pr.store().labelNames().contains("backport"));

            // The bot should have added the "clean" label
            assertTrue(pr.store().labelNames().contains("clean"));

            // Issue the "/clean" PR command, should do nothing
            pr.addComment("/clean");
            TestBotRunner.runPeriodicItems(bot);
            assertTrue(pr.store().labelNames().contains("clean"));
            assertLastCommentContains(pr, "This backport pull request is already marked as clean");
        }
    }

    @Test
    void makeNonCleanBackportClean(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
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

            // The bot should not have added the "clean" label
            assertFalse(pr.store().labelNames().contains("clean"));

            // Use the "/clean" pull request command to mark the backport PR as clean
            pr.addComment("/clean");
            TestBotRunner.runPeriodicItems(bot);
            assertTrue(pr.store().labelNames().contains("clean"), "PR not marked clean");
            assertTrue(pr.comments().stream()
                    .anyMatch(c -> c.body().contains("This backport pull request is now marked as clean")));
            assertTrue(pr.store().labelNames().contains("ready"), "PR not marked ready");
        }
    }

    @Test
    void authorShouldNotBeAllowed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var contributor = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(contributor.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
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

            // The bot should not have added the "clean" label
            assertFalse(pr.store().labelNames().contains("clean"));

            // Use the "/clean" pull request command as author, should not work
            var prAsAuthor = contributor.pullRequest(pr.id());
            prAsAuthor.addComment("/clean");
            TestBotRunner.runPeriodicItems(bot);
            assertFalse(pr.store().labelNames().contains("clean"));
            assertLastCommentContains(pr, "Only OpenJDK [Committers]");
            assertLastCommentContains(pr, "can use the `/clean` command");
        }
    }

    @Test
    void missingBackportHash(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var issues = credentials.getIssueProject();
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var issue = credentials.createIssue(issues, "An issue");

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + issue.id());
            TestBotRunner.runPeriodicItems(prBot);

            assertTrue(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().labelNames().contains("clean"));

            // Try to issue the "/clean" PR command, should not work
            pr.addComment("/clean");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("backport"));
            assertFalse(pr.store().labelNames().contains("clean"));
            assertLastCommentContains(pr, "Can only mark [backport pull requests]");
            assertLastCommentContains(pr, ", with an original hash, as clean");
        }
    }

    @Test
    void cleanCommandDisabled(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory(false)) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id())
                    .addReviewer(integrator.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .cleanCommandEnabled(false)
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

            TestBotRunner.runPeriodicItems(bot);

            // The bot should not have added the "clean" label
            assertFalse(pr.store().labelNames().contains("clean"));

            // Use the "/clean" pull request command to mark the backport PR as clean
            pr.addComment("/clean");
            TestBotRunner.runPeriodicItems(bot);
            // The pr shouldn't have clean label since clean command is disabled
            assertFalse(pr.store().labelNames().contains("clean"));
            assertLastCommentContains(pr, "The `/clean` pull request command is not enabled for this repository");
        }
    }
}
