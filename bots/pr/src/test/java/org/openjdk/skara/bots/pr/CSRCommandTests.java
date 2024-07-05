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

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.bots.pr.CheckRun.CSR_PROCESS_LINK;

class CSRCommandTests {
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
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // No longer require CSR
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is no longer needed
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                                          "is not needed for this pull request.");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Require CSR again with long form
            prAsReviewer.addComment("/csr needed");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
        }
    }

    private String generateCSRProgressMessage(IssueTrackerIssue issue) {
        return "Change requires CSR request [" + issue.id() + "](" + issue.webUrl() + ") to be approved";
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

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of());
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));
            csr.setProperty("issuetype", JSON.of("CSR"));
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the CSR is already approved
            assertLastCommentContains(pr, "This pull request already associated with these approved CSRs:");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [x] " + generateCSRProgressMessage(csr)));
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
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the CSR is already aproved
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") " +
                                          "(CSR) request is needed for this pull request.");
            assertLastCommentContains(pr, "this pull request must refer to an issue in [JBS]");
            assertLastCommentContains(pr, "To refer this pull request to an issue in JBS");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
        }
    }

    @Test
    void requireCSRAsCommitter(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var anotherPerson = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id())
                                           .addCommitter(anotherPerson.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Require CSR from another person who is not a reviewer and is not the author
            var prAsAnother = anotherPerson.pullRequest(pr.id());
            prAsAnother.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(prAsAnother, "only the pull request author and [Reviewers]");
            assertLastCommentContains(prAsAnother, "are allowed to use the `csr` command.");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Stating that a CSR is not needed should not work
            prAsAnother.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(prAsAnother, "only the pull request author and [Reviewers]");
            assertLastCommentContains(prAsAnother, "are allowed to use the `csr` command.");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Require CSR as committer
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Stating that a CSR is not needed should not work
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "only [Reviewers]");
            assertLastCommentContains(pr, "can determine that a CSR is not needed.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Stating that a CSR is not needed should not work
            prAsAnother.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(prAsAnother, "only the pull request author and [Reviewers]");
            assertLastCommentContains(prAsAnother, "are allowed to use the `csr` command.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
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
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Require CSR with bad argument
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr foobar");
            TestBotRunner.runPeriodicItems(prBot);

            // Show help
            assertLastCommentContains(pr, "usage: `/csr [needed|unneeded]`, requires that the issue the pull request refers to links " +
                                          "to an approved [CSR](" + CSR_PROCESS_LINK + ") request.");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
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
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that the PR must refer to an issue in JBS
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") " +
                                          "(CSR) request is needed for this pull request.");
            assertLastCommentContains(pr, "this pull request must refer to an issue in [JBS]");
            assertLastCommentContains(pr, "to be able to link it to a [CSR](" + CSR_PROCESS_LINK + ") request. To refer this pull request to an issue in JBS");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
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
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Require a CSR again
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is already required
            assertLastCommentContains(pr, "an approved [CSR]");
            assertLastCommentContains(pr, "request is already required for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
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
            issue.setProperty("issuetype", JSON.of("Bug"));
            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution",
                                                                                      JSON.object().put("name", "Unresolved")));
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issues, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that there is already an approved CSR request
            // Before '/csr' is handled, csr label is added to this pr
            assertLastCommentContains(pr, "an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");
            assertLastCommentContains(pr, "<!-- csr: 'needed' -->");
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(csr)));

            // Indicate the PR doesn't require CSR, but it doesn't work.
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message which directs the user to withdraw the csr firstly.
            assertLastCommentContains(pr, "The CSR requirement cannot be removed as CSR issues already exist.");
            assertLastCommentContains(pr, "and then use the command `/csr unneeded` again.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(csr)));

            // withdraw the csr
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Withdrawn"));
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Indicate the PR doesn't require CSR, now it works
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is no longer needed
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains(generateCSRProgressMessage(csr)));
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
            issue.setProperty("issuetype", JSON.of("Bug"));

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution", JSON.of()));
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issues, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that there is already an approved CSR request
            // Before '/csr' is handled, csr label is added to this pr
            assertLastCommentContains(pr, "an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");
            assertLastCommentContains(pr, "<!-- csr: 'needed' -->");
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(csr)));

            // Indicate the PR doesn't require CSR, but it doesn't work.
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message which directs the user to withdraw the csr firstly.
            assertLastCommentContains(pr, "The CSR requirement cannot be removed as CSR issues already exist.");
            assertLastCommentContains(pr, "and then use the command `/csr unneeded` again.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(csr)));

            // withdraw the csr
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Withdrawn"));

            TestBotRunner.runPeriodicItems(csrIssueBot);
            // Indicate the PR doesn't require CSR, now it works
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is no longer needed
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");
            assertFalse(pr.store().labelNames().contains("csr"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(csr)));
        }
    }

    @Test
    void csrInPrBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR and require CSR in PR body
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch", List.of("/csr"));

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
        }
    }

    @Test
    void csrLabelShouldBlockReadyLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Approve the PR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good");

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // PR should be ready
            var prAsAuthor = author.pullRequest(pr.id());
            assertTrue(prAsAuthor.labelNames().contains("ready"));
            // The PR body shouldn't contain the progress about CSR request
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Require CSR
            prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                                          "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                                          "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));

            // PR should not be ready
            prAsAuthor = author.pullRequest(pr.id());
            assertFalse(prAsAuthor.labelNames().contains("ready"));

            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
        }
    }

    @Test
    void testEnableCsrConfig(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addCommitter(author.forge().currentUser().id());

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Just a patch");

            // Test the pull request bot with csr disable
            var disableCsrBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .enableCsr(false)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(new HashMap<>())
                    .build();
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(disableCsrBot);
            assertLastCommentContains(pr, "This repository has not been configured to use the `csr` command.");
            assertFalse(pr.store().labelNames().contains("csr"));
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Test the pull request bot with csr enable
            var enableCsrBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues)
                    .enableCsr(true).censusRepo(censusBuilder.build()).build();
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(enableCsrBot);
            assertLastCommentContains(pr, "has indicated that a " +
                    "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                    "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            assertTrue(pr.store().body().contains("Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
        }
    }

    @Test
    void testBackportCsr(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var bot = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());

            var issue = issueProject.createIssue("This is the primary issue", List.of(), Map.of());
            issue.setState(Issue.State.CLOSED);
            issue.setProperty("issuetype", JSON.of("Bug"));
            issue.setProperty("fixVersions", JSON.array().add("18"));

            var csr = issueProject.createIssue("This is the primary CSR", List.of(), Map.of());
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setProperty("fixVersions", JSON.array().add("18"));
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));
            issue.addLink(Link.create(csr, "csr for").build());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .enableCsr(true)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issueProject)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue prBot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Push a commit to the jdk18 branch
            var jdk18Branch = localRepo.branch(masterHash, "jdk18");
            localRepo.checkout(jdk18Branch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a_new_file");
            localRepo.add(newFile);
            var issueNumber = issue.id().split("-")[1];
            var commitMessage = issueNumber + ": This is the primary issue\n\nReviewed-by: integrationreviewer2";
            var commitHash = localRepo.commit(commitMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(commitHash, author.authenticatedUrl(), "jdk18", true);

            // Remove `version=0.1` from `.jcheck/conf`, set the version as null
            localRepo.checkout(localRepo.defaultBranch());
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            var newConf = defaultConf.replace("version=0.1", "");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set version as null", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);
            createBackport(localRepo, author, confHash, "edit1");
            var pr = credentials.createPullRequest(author, "master", "edit1", "Backport " + commitHash);
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // "csr" label should be added automatically because the main issue has a resolved CSR
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(3 ,pr.store().comments().size());
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion (No fixVersion in .jcheck/conf) to be approved (needs to be created)"));
            assertLastCommentContains(pr, "this backport may also need a CSR");
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(3 ,pr.store().comments().size());

            // Run prBot. Request a CSR.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion (No fixVersion in .jcheck/conf) to be approved (needs to be created)"));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "@user1 an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");

            // Use `/csr unneeded` to revert the change.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion (No fixVersion in .jcheck/conf) to be approved (needs to be created)"));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");

            // Run pr bot again, "csr" label should not be added because reviewer issued "/csr unneeded"
            assertEquals(7 ,pr.store().comments().size());
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(7 ,pr.store().comments().size());

            // Add `version=bla` to `.jcheck/conf`, set the version as a wrong value
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            newConf = defaultConf.replace("project=test", "project=test\nversion=bla");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as a wrong value", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);
            createBackport(localRepo, author, confHash, "edit2");
            pr = credentials.createPullRequest(author, "master", "edit2", "Backport " + commitHash);
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Run prBot. Request a CSR.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion (No fixVersion in .jcheck/conf) to be approved (needs to be created)"));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "@user1 an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");

            // Use `/csr unneeded` to revert the change.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion (No fixVersion in .jcheck/conf) to be approved (needs to be created)"));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");

            // Set the `version` in `.jcheck/conf` as 17 which is an available version.
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            newConf = defaultConf.replace("version=bla", "version=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);
            createBackport(localRepo, author, confHash, "edit3");
            pr = credentials.createPullRequest(author, "master", "edit3", "Backport " + commitHash);
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Run prBot. Request a CSR.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 17 to be approved (needs to be created)"));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "@user1 an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");

            // Use `/csr unneeded` to revert the change.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 17 to be approved (needs to be created)"));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");

            // Set the fix versions of the primary CSR to 17 and 18.
            csr.setProperty("fixVersions", JSON.array().add("17").add("18"));
            // Run csrIssueBot to update the pr body
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // Run prBot. Request a CSR.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [x] " + generateCSRProgressMessage(csr)));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "This pull request already associated with these approved CSRs:");
            // Use `/csr unneeded`.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [x] " + generateCSRProgressMessage(csr)));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "The CSR requirement cannot be removed as CSR issues already exist.");
            assertLastCommentContains(pr, "and then use the command `/csr unneeded` again");

            // Revert the fix versions of the primary CSR to 18.
            csr.setProperty("fixVersions", JSON.array().add("18"));
            // Create a backport issue whose fix version is 17
            var backportIssue = issueProject.createIssue("This is the backport issue", List.of(), Map.of());
            backportIssue.setProperty("issuetype", JSON.of("Backport"));
            backportIssue.setProperty("fixVersions", JSON.array().add("17"));
            backportIssue.setState(Issue.State.OPEN);
            issue.addLink(Link.create(backportIssue, "backported by").build());
            // Run csrIssueBot to update the pr body
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // Run prBot. Request a CSR.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 17 to be approved (needs to be created)"));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "@user1 an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");

            // Use `/csr unneeded` to revert the change.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().body().contains("Change requires a CSR request matching fixVersion 17 to be approved (needs to be created)"));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");

            // Create a backport CSR whose fix version is 17.
            var backportCsr = issueProject.createIssue("This is the backport CSR", List.of(), Map.of());
            backportCsr.setProperty("issuetype", JSON.of("CSR"));
            backportCsr.setProperty("fixVersions", JSON.array().add("17"));
            backportCsr.setState(Issue.State.OPEN);
            backportIssue.addLink(Link.create(backportCsr, "csr for").build());
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Run prBot. Request a CSR.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(backportCsr)));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");
            // Use `/csr unneeded` to revert the change.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(backportCsr)));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "The CSR requirement cannot be removed as CSR issues already exist.");
            assertLastCommentContains(pr, "and then use the command `/csr unneeded` again.");

            // Now we have a primary issue, a primary CSR, a backport issue, a backport CSR.
            // Set the backport CSR to have multiple fix versions, included 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("11").add("8"));
            // Set the `version` in `.jcheck/conf` as 11.
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            newConf = defaultConf.replace("version=17", "version=11");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 11", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);
            createBackport(localRepo, author, confHash, "edit4");
            pr = credentials.createPullRequest(author, "master", "edit4", "Backport " + commitHash);
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Run prBot.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(backportCsr)));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "an approved [CSR](" + CSR_PROCESS_LINK + ") request is already required for this pull request.");
            assertLastCommentContains(pr, "<!-- csr: 'needed' -->");
            // Set the backport CSR to have multiple fix versions, excluded 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("8"));
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // Use `/csr unneeded` to revert the change.
            pr.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(backportCsr)));
            assertFalse(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is not needed for this pull request.");

            // re-run prBot.
            pr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 11 to be approved (needs to be created)"));
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "has indicated that a " +
                    "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                    "is needed for this pull request.");
            assertLastCommentContains(pr, "please create a [CSR](" + CSR_PROCESS_LINK + ") request");
            assertLastCommentContains(pr, "with the correct fix version");
            assertLastCommentContains(pr, "This pull request cannot be integrated until the CSR request is approved.");
        }
    }

    private void createBackport(Repository localRepo, HostedRepository author, Hash masterHash, String branchName) throws IOException {
        localRepo.checkout(localRepo.defaultBranch());
        var editBranch = localRepo.branch(masterHash, branchName);
        localRepo.checkout(editBranch);
        var newFile2 = localRepo.root().resolve("a_new_file.txt");
        Files.writeString(newFile2, "a_new_file");
        localRepo.add(newFile2);
        var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
        localRepo.push(editHash, author.authenticatedUrl(), branchName, true);
    }

    @Test
    void prSolvesMultipleIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issues, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                    "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                    "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            var issue2 = issues.createIssue("This is an issue2", List.of(), Map.of());
            issue2.setProperty("issuetype", JSON.of("Bug"));
            // create a csr for issue2
            var csr2 = issues.createIssue("This is a CSR2", List.of(), Map.of("resolution",
                    JSON.object().put("name", "Unresolved")));
            csr2.setProperty("issuetype", JSON.of("CSR"));
            csr2.setState(Issue.State.OPEN);
            issue2.addLink(Link.create(csr2, "csr for").build());
            PullRequestUtils.postPullRequestLinkComment(issue2, pr);
            TestBotRunner.runPeriodicItems(csrIssueBot);

            pr.addComment("/issue TEST-2");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));
            assertTrue(pr.store().body().contains("- [ ] " + generateCSRProgressMessage(csr2)));

            // Try /csr unneeded, it should fail
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "The CSR requirement cannot be removed as CSR issues already exist.");

            // Withdraw the csr linked with issue2
            csr2.setState(Issue.State.CLOSED);
            csr2.setProperty("resolution", JSON.object().put("name", "Withdrawn"));
            TestBotRunner.runPeriodicItems(csrIssueBot);
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("csr"));

            // Require CSR again
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a message that a CSR is needed
            assertLastCommentContains(pr, "has indicated that a " +
                    "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                    "is needed for this pull request.");
            assertTrue(pr.store().labelNames().contains("csr"));
            // The PR body should contain the progress about CSR request
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));

            // Create a csr for main issue
            var csr1 = issues.createIssue("This is a CSR1", List.of(), Map.of("resolution",
                    JSON.object().put("name", "Unresolved")));
            csr1.setProperty("issuetype", JSON.of("CSR"));
            csr1.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr1, "csr for").build());
            TestBotRunner.runPeriodicItems(csrIssueBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }


    @Test
    void prSolvesMultipleIssuesWithApprovedCSRIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());
            var csr = issues.createIssue("This is a CSR", List.of(), Map.of());
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            var issue2 = issues.createIssue("This is an issue2", List.of(), Map.of());
            // create a csr for issue2
            var csr2 = issues.createIssue("This is a CSR2", List.of(), Map.of());
            csr2.setState(Issue.State.CLOSED);
            csr2.setProperty("resolution", JSON.object().put("name", "Approved"));
            csr2.setProperty("issuetype", JSON.of("CSR"));
            issue2.addLink(Link.create(csr2, "csr for").build());

            pr.addComment("/issue TEST-3");
            TestBotRunner.runPeriodicItems(prBot);
            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("csr"));
            assertTrue(pr.store().body().contains("- [x] " + generateCSRProgressMessage(csr)));
            assertTrue(pr.store().body().contains("- [x] " + generateCSRProgressMessage(csr2)));
        }
    }

    @Test
    void prSolvesMultipleIssuesWithWithdrawnCSRIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());
            var csr = issues.createIssue("This is a CSR", List.of(), Map.of());
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Withdrawn"));
            issue.addLink(Link.create(csr, "csr for").build());

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
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
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            var issue2 = issues.createIssue("This is an issue2", List.of(), Map.of());
            // create a csr for issue2
            var csr2 = issues.createIssue("This is a CSR2", List.of(), Map.of());
            csr2.setState(Issue.State.CLOSED);
            csr2.setProperty("resolution", JSON.object().put("name", "Withdrawn"));
            csr2.setProperty("issuetype", JSON.of("CSR"));
            issue2.addLink(Link.create(csr2, "csr for").build());

            pr.addComment("/issue TEST-3");
            TestBotRunner.runPeriodicItems(prBot);
            // Require CSR
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));
            assertLastCommentContains(pr, "has indicated that a " +
                    "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                    "is needed for this pull request.");
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 0.1 to be approved (needs to be created)"));
            assertFalse(pr.store().body().contains(generateCSRProgressMessage(csr)));
            assertFalse(pr.store().body().contains(generateCSRProgressMessage(csr2)));
        }
    }

    @Test
    void testBackportCsrLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var bot = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());

            var issue = issueProject.createIssue("This is the primary issue", List.of(), Map.of());
            issue.setState(Issue.State.CLOSED);
            issue.setProperty("issuetype", JSON.of("Bug"));
            issue.setProperty("fixVersions", JSON.array().add("18"));

            var csr = issueProject.createIssue("This is the primary CSR", List.of(), Map.of());
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setProperty("fixVersions", JSON.array().add("18"));
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));
            issue.addLink(Link.create(csr, "csr for").build());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .enableCsr(true)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issueProject)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue prBot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Push a commit to the jdk18 branch
            var jdk18Branch = localRepo.branch(masterHash, "jdk18");
            localRepo.checkout(jdk18Branch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a_new_file");
            localRepo.add(newFile);
            var issueNumber = issue.id().split("-")[1];
            var commitMessage = issueNumber + ": This is the primary issue\n\nReviewed-by: integrationreviewer2";
            var commitHash = localRepo.commit(commitMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(commitHash, author.authenticatedUrl(), "jdk18", true);

            // Create a backport issue whose fix version is 17
            var backportIssue = issueProject.createIssue("This is the backport issue", List.of(), Map.of());
            backportIssue.setProperty("issuetype", JSON.of("Backport"));
            backportIssue.setProperty("fixVersions", JSON.array().add("17"));
            backportIssue.setState(Issue.State.OPEN);
            issue.addLink(Link.create(backportIssue, "backported by").build());

            // Create a backport CSR whose fix version is 17.
            var backportCsr = issueProject.createIssue("This is the backport CSR", List.of(), Map.of());
            backportCsr.setProperty("issuetype", JSON.of("CSR"));
            backportCsr.setProperty("fixVersions", JSON.array().add("17"));
            backportCsr.setState(Issue.State.OPEN);
            backportIssue.addLink(Link.create(backportCsr, "csr for").build());

            // Set the `version` in `.jcheck/conf` as 17 which is an available version.
            localRepo.checkout(localRepo.defaultBranch());
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            var newConf = defaultConf.replace("version=0.1", "version=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set the version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);
            createBackport(localRepo, author, confHash, "edit1");
            var pr = credentials.createPullRequest(author, "master", "edit1", "Backport " + commitHash);
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires CSR request [TEST-4](http://localhost/project/testTEST-4) to be approved"));
            assertTrue(pr.store().labelNames().contains("csr"));
            // The bot shouldn't post backport csr comment because there is a backport csr
            assertTrue(pr.store().comments().stream().noneMatch(comment -> comment.body().contains("this backport may also need a CSR")));

            // Change the fixVersion of the backportCSR
            backportCsr.setProperty("fixVersions", JSON.array().add("19"));
            TestBotRunner.runPeriodicItems(csrIssueBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains("- [ ] Change requires a CSR request matching fixVersion 17 to be approved (needs to be created)"));
            assertTrue(pr.store().labelNames().contains("csr"));
            // The bot shouldn't post backport csr comment because csr label is still there
            assertTrue(pr.store().comments().stream().noneMatch(comment -> comment.body().contains("this backport may also need a CSR")));

            // Use '/csr unneeded'
            var prAsReviewer = reviewer.pullRequest(pr.id());
            prAsReviewer.addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("csr"));
            // The bot shouldn't post backport csr comment because csr label has been removed by command
            assertTrue(pr.store().comments().stream().noneMatch(comment -> comment.body().contains("this backport may also need a CSR")));

            // Require CSR again
            prAsReviewer.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));
            // The bot shouldn't post backport csr comment because csr label has been added by command
            assertTrue(pr.store().comments().stream().noneMatch(comment -> comment.body().contains("this backport may also need a CSR")));
        }
    }
}
