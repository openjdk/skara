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
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.json.JSON;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class CSRTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](https://wiki.openjdk.java.net/display/csr/Main) (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.labels().contains("csr"));


            // No longer require CSR
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is no longer needed
            assertLastCommentContains(pr, "determined that a [CSR](https://wiki.openjdk.java.net/display/csr/Main) request " +
                                          "is no longer needed for this pull request.");
            assertFalse(pr.labels().contains("csr"));

            // Require CSR again with long form
            prAsReviewer.addComment("/csr needed");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](https://wiki.openjdk.java.net/display/csr/Main) (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.labels().contains("csr"));
        }
    }

    @Test
    void alreadyApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution",
                                                                                      JSON.object().put("name", "Approved")));
            csr.setState(Issue.State.CLOSED);
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the CSR is already aproved
            assertLastCommentContains(pr, "the issue for this pull request");
            assertLastCommentContains(pr, "already has an approved CSR request");
            assertFalse(pr.labels().contains("csr"));
        }
    }

    @Test
    void testMissingIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the CSR is already aproved
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](https://wiki.openjdk.java.net/display/csr/Main) " +
                                          "(CSR) request is needed for this pull request.");
            assertLastCommentContains(pr, "this pull request must refer to an issue in [JBS]");
            assertLastCommentContains(pr, "to be able to link it to a CSR request. To refer this pull request to an issue in JBS");
            assertTrue(pr.labels().contains("csr"));
        }
    }

    @Test
    void requireCSRAsCommitter(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Require CSR as committer
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that only reviewers can require a CSR
            assertLastCommentContains(pr, "only [Reviewers](https://openjdk.java.net/bylaws#reviewer) are allowed require a CSR.");
            assertFalse(pr.labels().contains("csr"));
        }
    }

    @Test
    void showHelpMessageOnUnexpectedArg(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Require CSR with bad argument
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr foobar");
            TestBotRunner.runPeriodicItems(prBot);

            // Show help
            assertLastCommentContains(pr, "usage: `/csr [needed|unneeded]`, requires that the issue the pull request refers to links " +
                                          "to an approved [CSR](https://wiki.openjdk.java.net/display/csr/Main) request.");
            assertFalse(pr.labels().contains("csr"));
        }
    }

    @Test
    void nonExistingJBSIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the PR must refer to an issue in JBS
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](https://wiki.openjdk.java.net/display/csr/Main) " +
                                          "(CSR) request is needed for this pull request.");
            assertLastCommentContains(pr, "this pull request must refer to an issue in [JBS]");
            assertLastCommentContains(pr, "to be able to link it to a CSR request. To refer this pull request to an issue in JBS");
            assertTrue(pr.labels().contains("csr"));
        }
    }

    @Test
    void csrRequestWhenCSRIsAlreadyRequested(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](https://wiki.openjdk.java.net/display/csr/Main) (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.labels().contains("csr"));

            // Require a CSR again
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is already required
            assertLastCommentContains(pr, "an approved [CSR]");
            assertLastCommentContains(pr, "request is already required for this pull request.");
            assertTrue(pr.labels().contains("csr"));
        }
    }

    @Test
    void notYetApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution",
                                                                                      JSON.object().put("name", "Unresolved")));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the PR will not be integrated until the CSR is approved
            assertLastCommentContains(pr, "this pull request will not be integrated until the [CSR]");
            assertLastCommentContains(pr, "for issue ");
            assertLastCommentContains(pr, "has been approved.");
        }
    }

    @Test
    void csrWithNullResolution(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution", JSON.of()));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the PR will not be integrated until the CSR is approved
            assertLastCommentContains(pr, "this pull request will not be integrated until the [CSR]");
            assertLastCommentContains(pr, "for issue ");
            assertLastCommentContains(pr, "has been approved.");
        }
    }
}
