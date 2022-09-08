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
package org.openjdk.skara.bots.csr;

import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;
import org.openjdk.skara.json.JSON;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CSRBotTests {
    @Test
    void removeLabelForApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of());
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));
            issue.addLink(Link.create(csr, "csr for").build());

            var bot = new CSRPullRequestBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have removed the CSR label
            assertFalse(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void keepLabelForNoIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var bot = new CSRPullRequestBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", "This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void keepLabelForNoJBS(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var bot = new CSRPullRequestBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", "123: This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void keepLabelForNotApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution",
                                                                                      JSON.object().put("name", "Unresolved")));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var bot = new CSRPullRequestBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot added the csr label automatically
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void handleCSRWithNullResolution(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an CSR with null resolution", List.of(), Map.of("resolution", JSON.of()));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var bot = new CSRPullRequestBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot added the csr label automatically
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot, should *not* throw NPE
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void handleCSRWithNullName(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an CSR with null resolution", List.of(),
                                         Map.of("resolution", JSON.object().put("name", JSON.of())));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var bot = new CSRPullRequestBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot added the csr label automatically
            assertTrue(pr.store().labelNames().contains("csr"));

            // Run bot, should *not* throw NPE
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void testBackportCsr(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var csrPullRequestBot = new CSRPullRequestBot(repo, issueProject);
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(repo));

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
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);

            // Push a commit to the jdk18 branch
            var jdk18Branch = localRepo.branch(masterHash, "jdk18");
            localRepo.checkout(jdk18Branch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "a_new_file");
            localRepo.add(newFile);
            var issueNumber = issue.id().split("-")[1];
            var commitMessage = issueNumber + ": This is the primary issue\n\nReviewed-by: integrationreviewer2";
            var commitHash = localRepo.commit(commitMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(commitHash, repo.url(), "jdk18", true);

            // "backport" the commit to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "a_new_file");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issueNumber + ": This is the primary issue");
            pr.addLabel("backport");
            // Add the notification link to the PR in the issue. This is needed for the CSRIssueBot to
            // be able to trigger on CSR issue updates
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Remove `version=0.1` from `.jcheck/conf`, set the version as null
            localRepo.checkout(localRepo.defaultBranch());
            var defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            var newConf = defaultConf.replace("version=0.1", "");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            var confHash = localRepo.commit("Set version as null", "duke", "duke@openjdk.org");
            localRepo.push(confHash, repo.url(), "master", true);
            assertFalse(pr.store().labelNames().contains("csr"));
            // Run bot. The bot won't get a CSR.
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));

            // Test the method `TestPullRequest#diff`.
            assertEquals(1, pr.diff().patches().size());

            // Add `version=bla` to `.jcheck/conf`, set the version as a wrong value
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            newConf = defaultConf.replace("project=test", "project=test\nversion=bla");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as a wrong value", "duke", "duke@openjdk.org");
            localRepo.push(confHash, repo.url(), "master", true);
            // Run bot. The bot won't get a CSR.
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));

            // Test the method `TestPullRequest#diff`.
            assertEquals(1, pr.diff().patches().size());

            // Set the `version` in `.jcheck/conf` as 17 which is an available version.
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            newConf = defaultConf.replace("version=bla", "version=17");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 17", "duke", "duke@openjdk.org");
            localRepo.push(confHash, repo.url(), "master", true);
            // Run bot. The primary CSR doesn't have the fix version `17`, so the bot won't get a CSR.
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
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
            pr.removeLabel("csr");
            // Run bot. The bot can find a backport issue but can't find a backport CSR.
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
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
            localRepo.checkout(localRepo.defaultBranch());
            defaultConf = Files.readString(localRepo.root().resolve(".jcheck/conf"), StandardCharsets.UTF_8);
            newConf = defaultConf.replace("version=17", "version=11");
            Files.writeString(localRepo.root().resolve(".jcheck/conf"), newConf, StandardCharsets.UTF_8);
            localRepo.add(localRepo.root().resolve(".jcheck/conf"));
            confHash = localRepo.commit("Set the version as 11", "duke", "duke@openjdk.org");
            localRepo.push(confHash, repo.url(), "master", true);
            pr.removeLabel("csr");
            // Run bot.
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot should have added the CSR label
            assertTrue(pr.store().labelNames().contains("csr"));

            // Set the backport CSR to have multiple fix versions, excluded 11.
            backportCsr.setProperty("fixVersions", JSON.array().add("17").add("8"));
            pr.removeLabel("csr");
            // Run bot.
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the `csr` label.
            assertFalse(pr.store().labelNames().contains("csr"));
        }
    }

    @Test
    void testCsrUpdateMarker(TestInfo testInfo) throws IOException {
        String csrUpdateMarker = "<!-- csr: 'update' -->";
        String progressMarker = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var csrPullRequestBot = new CSRPullRequestBot(repo, issueProject);
            var csrIssueBot = new CSRIssueBot(issueProject, List.of(repo));

            // Run issue bot once to initialize lastUpdatedAt
            TestBotRunner.runPeriodicItems(csrIssueBot);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");
            // Add the notification link to the PR in the issue. This is needed for the CSRIssueBot to
            // be able to trigger on CSR issue updates
            PullRequestUtils.postPullRequestLinkComment(issue, pr);
            // Run bot
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the csr update marker
            assertFalse(pr.body().contains(csrUpdateMarker));

            // Add the csr issue.
            var csr = issueProject.createIssue("This is an CSR", List.of(), Map.of());
            csr.setProperty("issuetype", JSON.of("CSR"));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());
            // Run just the pull request bot
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // Nothing should have happened
            assertFalse(pr.body().contains(csrUpdateMarker));
            // Run csr issue bot to trigger updates on the CSR issue
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // The bot should add the csr update marker
            assertTrue(pr.body().contains(csrUpdateMarker));

            // Add csr issue and progress to the PR body
            pr.setBody("PR body\n" + progressMarker + csr.id() + csr.webUrl().toString() + csr.title() + " (**CSR**)"
                    + "- [ ] Change requires a CSR request to be approved");
            // Run bot
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the csr update marker
            assertFalse(pr.body().contains(csrUpdateMarker));

            // Set csr status to closed and approved.
            csr.setState(Issue.State.CLOSED);
            csr.setProperty("resolution", JSON.object().put("name", "Approved"));
            // un csr issue bot to trigger updates on the CSR issue
            TestBotRunner.runPeriodicItems(csrIssueBot);
            // The bot should add the csr update marker
            assertTrue(pr.body().contains(csrUpdateMarker));

            // Add csr issue and selected progress to the PR body
            pr.setBody("PR body\n" + progressMarker + csr.id() + csr.webUrl().toString() + csr.title() + " (**CSR**)"
                    + "- [x] Change requires a CSR request to be approved");
            // Run bot
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the csr update marker
            assertFalse(pr.body().contains(csrUpdateMarker));

            // Add csr update marker to the pull request body manually.
            pr.setBody("PR body\n" + progressMarker + csr.id() + csr.webUrl().toString() + csr.title() + " (**CSR**)"
                    + "- [ ] Change requires a CSR request to be approved" + csrUpdateMarker);
            // Run bot
            TestBotRunner.runPeriodicItems(csrPullRequestBot);
            // The bot shouldn't add the csr update marker again. The PR should have only one csr update marker.
            assertTrue(pr.body().contains(csrUpdateMarker));
            assertEquals(pr.body().indexOf(csrUpdateMarker), pr.body().lastIndexOf(csrUpdateMarker));
        }
    }
}
