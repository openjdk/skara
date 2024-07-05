/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CSRBotTests {
    @Test
    void removeLabelForApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            var csr = issueProject.createIssue("This is a CSR", List.of(), Map.of());
            csr.setState(Issue.State.OPEN);
            csr.setProperty("issuetype", JSON.of("CSR"));
            issue.addLink(Link.create(csr, "csr for").build());

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
            TestBotRunner.runPeriodicItems(prBot);

            // Use CSRIssueBot to add CSR label
            TestBotRunner.runPeriodicItems(csrIssueBot);
            assertTrue(pr.store().labelNames().contains("csr"));

            // Approve CSR issue
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));

            // Run bot
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // The bot should have removed the CSR label
            assertFalse(pr.store().labelNames().contains("csr"));
            assertTrue(pr.store().body().contains("- [x] Change requires CSR request"));
        }
    }

    @Test
    void keepLabelForNoIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issues).censusRepo(censusBuilder.build()).enableCsr(true).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is an issue");

            // Use csr command to add csr label
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);
            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void keepLabelForNoJBS(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issueProject).censusRepo(censusBuilder.build()).enableCsr(true).build();

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

            // Use csr command to add csr label
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("/csr");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void keepLabelForNotApprovedCSR(TestInfo testInfo) throws IOException {
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
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issues)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
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

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot added the csr label automatically
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void handleCSRWithNullResolution(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an CSR with null resolution", List.of(), Map.of("resolution", JSON.of()));
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

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot added the csr label automatically
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot, should *not* throw NPE
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void handleCSRWithNullName(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is a CSR with null resolution", List.of(),
                    Map.of("resolution", JSON.object().put("name", JSON.of())));
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

            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // The bot added the csr label automatically
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot, should *not* throw NPE
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void testBackportCsr(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            var issue = issueProject.createIssue("This is the primary issue", List.of(), Map.of());
            issue.setState(Issue.State.CLOSED);
            issue.setProperty("issuetype", JSON.of("Bug"));
            issue.setProperty("fixVersions", JSON.array().add("18"));

            var csr = issueProject.createIssue("This is the primary CSR", List.of(), Map.of());
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setProperty("fixVersions", JSON.array().add("18"));
            issue.addLink(Link.create(csr, "csr for").build());

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

            // "backport" the commit to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "a_new_file");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + commitHash.hex());

            // run bot to add backport label
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("backport"));

            // Remove `version=0.1` from `.jcheck/conf`, set the version as null in the edit branch
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            var newConf = defaultConf.replace("version=0.1", "");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set version as null", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "edit", true);
            assertFalse(pr.store().labelNames().contains("csr"));
            // Run bot. The bot won't get a CSR.
            TestBotRunner.runPeriodicItems(prBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));

            // Add `version=bla` to `.jcheck/conf`, set the version as a wrong value
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            newConf = defaultConf.replace("project=test", "project=test\nversion=bla");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as a wrong value", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "edit", true);
            // Run bot. The bot won't get a CSR.
            TestBotRunner.runPeriodicItems(prBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));

            // Test the method `TestPullRequest#diff`.
            assertEquals(1, pr.diff().patches().size());

            // Set the `version` in `.jcheck/conf` as 17 which is an available version.
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            newConf = defaultConf.replace("version=bla", "version=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "edit", true);
            // Run bot. The primary CSR doesn't have the fix version `17`, so the bot won't get a CSR.
            TestBotRunner.runPeriodicItems(prBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));

            // Set the fix versions of the primary CSR to 17 and 18.
            csr.setProperty("fixVersions", JSON.array().add("17").add("18"));
            // Run csr issue bot to trigger on updates to the CSR issue. The primary CSR has
            // the fix version `17`, so it would be used and the `csr` label would be added.
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // The bot should have added the `csr` label
            assertTrue(pr.store().labelNames().contains("csr"));

            // Revert the fix versions of the primary CSR to 18.
            csr.setProperty("fixVersions", JSON.array().add("18"));
            // Create a backport issue whose fix version is 17
            var backportIssue = issueProject.createIssue("This is the backport issue", List.of(), Map.of());
            backportIssue.setProperty("issuetype", JSON.of("Backport"));
            backportIssue.setProperty("fixVersions", JSON.array().add("17"));
            backportIssue.setState(Issue.State.OPEN);
            issue.addLink(Link.create(backportIssue, "backported by").build());
            assertTrue(pr.store().labelNames().contains("csr"));

            // remove the csr label with /csr command
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/csr unneeded");
            // Run csrIssueBot to update pr body
            TestBotRunner.runPeriodicItems(csrIssueBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("csr"));

            // Run bot. The bot can find a backport issue but can't find a backport CSR.
            TestBotRunner.runPeriodicItems(prBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));

            // Create a backport CSR whose fix version is 17.
            var backportCsr = issueProject.createIssue("This is the backport CSR", List.of(), Map.of());
            backportCsr.setProperty("issuetype", JSON.of("CSR"));
            backportCsr.setProperty("fixVersions", JSON.array().add("17"));
            backportCsr.setState(Issue.State.OPEN);
            backportIssue.addLink(Link.create(backportCsr, "csr for").build());
            // Run csr issue bot. The bot can find a backport issue and a backport CSR.
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // The bot should have added the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));

            // Now we have a primary issue, a primary CSR, a backport issue, a backport CSR.
            // Set the backport CSR to have multiple fix versions, included 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("11").add("8"));
            // Set the `version` in `.jcheck/conf` as 11.
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            newConf = defaultConf.replace("version=17", "version=11");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 11", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "edit", true);
            pr.removeLabel("csr");
            // Run bot.
            TestBotRunner.runPeriodicItems(prBot);
            // The bot should have added the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));

            // Set the backport CSR to have multiple fix versions, excluded 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("8"));
            reviewerPr.addComment("/csr unneeded");
            // Run csrIssueBot to update the pr body
            TestBotRunner.runPeriodicItems(csrIssueBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("csr"));

            // Run bot.
            TestBotRunner.runPeriodicItems(prBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void testPRWithMultipleIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot).issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

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
            // Run bot
            TestBotRunner.runPeriodicItems(prBot);

            // Add another issue to this pr
            var issue2 = issueProject.createIssue("This is an issue 2", List.of(), Map.of());
            issue2.setProperty("issuetype", JSON.of("Bug"));

            // Add issue2 to this pr
            pr.addComment("/issue " + issue2.id());
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().comments().getLast().body().contains("solves: '2'"));

            // Add a csr to issue2
            var csr2 = issueProject.createIssue("This is an CSR for issue2", List.of(), Map.of());
            csr2.setProperty("issuetype", JSON.of("CSR"));
            csr2.setState(Issue.State.OPEN);
            issue2.addLink(Link.create(csr2, "csr for").build());

            TestBotRunner.runPeriodicItems(csrIssueBot);
            // PR should contain csr label
            assertTrue(pr.store().labelNames().contains("csr"));
            assertTrue(pr.store().body().contains("This is an CSR for issue2"));

            // Add another issue to this pr
            var issue3 = issueProject.createIssue("This is an issue 3", List.of(), Map.of());
            issue3.setProperty("issuetype", JSON.of("Bug"));

            // Add issue3 to this pr
            pr.addComment("/issue " + issue3.id());
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().comments().getLast().body().contains("solves: '4'"));

            // Withdrawn the csr for issue2
            csr2.setState(Issue.State.CLOSED);
            csr2.setProperty("resolution", JSON.object().put("name", "Withdrawn"));
            TestBotRunner.runPeriodicItems(csrIssueBot);
            assertTrue(pr.store().body().contains("This is an CSR for issue2 (**CSR**) (Withdrawn)"));
            // PR should not contain csr label
            assertFalse(pr.store().labelNames().contains("csr"));

            // Add a csr to issue3
            var csr3 = issueProject.createIssue("This is an CSR for issue3", List.of(), Map.of());
            csr3.setProperty("issuetype", JSON.of("CSR"));
            csr3.setState(Issue.State.OPEN);
            issue3.addLink(Link.create(csr3, "csr for").build());

            TestBotRunner.runPeriodicItems(csrIssueBot);
            // PR should contain csr label
            assertTrue(pr.store().labelNames().contains("csr"));

            // Approve CSR3
            csr3.setState(Issue.State.CLOSED);
            csr3.setProperty("resolution", JSON.object().put("name", "Approved"));
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // PR should not contain csr label
            assertFalse(pr.store().labelNames().contains("csr"));

            // Approve CSR2
            csr2.setProperty("resolution", JSON.object().put("name", "Approved"));
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // PR should not contain csr label
            assertFalse(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void testFindCSRWithVersionInMergedBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .enableCsr(true)
                    .issuePRMap(issuePRMap)
                    .build();
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            var csr = issueProject.createIssue("This is a CSR", List.of(), Map.of());
            csr.setState(Issue.State.OPEN);
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setProperty("fixVersions", JSON.array().add("17"));
            issue.addLink(Link.create(csr, "csr for").build());

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
            TestBotRunner.runPeriodicItems(prBot);

            // Change .jcheck/conf in targetBranch
            localRepo.checkout(masterHash);
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"));
            var newConf = defaultConf.replace("version=0.1", "version=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, author.authenticatedUrl(), "master", true);

            // The bot will be able to find the csr although fixVersion in source branch is 0.1
            TestBotRunner.runPeriodicItems(csrIssueBot);
            assertTrue(pr.store().labelNames().contains("csr"));

            reviewer.pullRequest(pr.id()).addComment("/csr unneeded");
            TestBotRunner.runPeriodicItems(prBot);

            assertTrue(pr.store().comments().getLast().body()
                    .contains("@user2 The CSR requirement cannot be removed as CSR issues already exist. " +
                            "Please withdraw [TEST-2](http://localhost/project/testTEST-2) and then use the command `/csr unneeded` again."));
        }
    }
}
