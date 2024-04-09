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
import org.openjdk.skara.forge.CheckStatus;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;


import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;


public class IssueBotTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(issuePRMap)
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

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
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            var completedTime1 = check.completedAt().get();
            assertEquals(CheckStatus.SUCCESS, check.status());
            var substrings = check.metadata().get().split("#");
            var prMetadata1 = substrings[0];
            var issueMetadata1 = (substrings.length > 1) ? substrings[1] : "";
            assertNotEquals("", issueMetadata1);

            // Run issueBot, there is no update in the issue, so the metadata should not change
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime2 = check.completedAt().get();
            assertEquals(completedTime1, completedTime2);

            // Update the issue and run prBot first
            // The check should not be updated
            issue.setProperty("priority", JSON.of("4"));
            TestBotRunner.runPeriodicItems(prBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime3 = check.completedAt().get();
            assertEquals(completedTime2, completedTime3);

            // Run issueBot
            // The check should be updated
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime4 = check.completedAt().get();
            substrings = check.metadata().get().split("#");
            var prMetadata2 = substrings[0];
            var issueMetadata2 = (substrings.length > 1) ? substrings[1] : "";
            assertNotEquals(completedTime3, completedTime4);
            // PR body has been updated, so the metadata for pr is also changed
            assertNotEquals(prMetadata1, prMetadata2);
            assertNotEquals(issueMetadata1, issueMetadata2);
            assertTrue(pr.store().body().contains("(**Bug** - P4)"));

            // Update the PR and run issueBot first
            // There should be no update in the check
            pr.setBody("updated body");
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime5 = check.completedAt().get();
            assertEquals(completedTime4, completedTime5);

            // Run prBot
            TestBotRunner.runPeriodicItems(prBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime6 = check.completedAt().get();
            substrings = check.metadata().get().split("#");
            var prMetadata3 = substrings[0];
            var issueMetadata3 = (substrings.length > 1) ? substrings[1] : "";
            assertNotEquals(completedTime5, completedTime6);
            assertNotEquals(prMetadata2, prMetadata3);
            // issue metadata should not be updated because no update in the issue
            assertEquals(issueMetadata2, issueMetadata3);

            // Update issue title and run prBot first
            // There should be no update in the check
            issue.setTitle("This is an Issue");
            TestBotRunner.runPeriodicItems(prBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime7 = check.completedAt().get();
            assertEquals(completedTime6, completedTime7);

            // Run issueBot
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime8 = check.completedAt().get();
            assertNotEquals(completedTime7, completedTime8);
            assertEquals("1: This is an Issue", pr.store().title());

            // Extra run of prBot and issueBot
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime9 = check.completedAt().get();
            assertEquals(completedTime8, completedTime9);
        }
    }

    @Test
    void normalCommentInIssue(TestInfo testInfo) throws IOException {
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
                    .issuePRMap(issuePRMap)
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

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
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            var completedTime1 = check.completedAt().get();
            assertEquals(CheckStatus.SUCCESS, check.status());
            var substrings = check.metadata().get().split("#");
            var prMetadata1 = substrings[0];
            var issueMetadata1 = (substrings.length > 1) ? substrings[1] : "";
            assertNotEquals("", issueMetadata1);

            // Add a normal comment in the issue
            issue.addComment("The issue commment!");
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime2 = check.completedAt().get();
            assertEquals(completedTime1, completedTime2);

            // Extra run of prBot and issueBot
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime3 = check.completedAt().get();
            assertEquals(completedTime2, completedTime3);
        }
    }

    @Test
    void multipleIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var issue2 = issueProject.createIssue("This is an issue2", List.of(), Map.of());
            issue2.setProperty("issuetype", JSON.of("Bug"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(issuePRMap)
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

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
            pr.addComment("/issue " + issue2.id());

            TestBotRunner.runPeriodicItems(prBot);
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("jcheck");
            var completedTime1 = check.completedAt().get();
            assertEquals(CheckStatus.SUCCESS, check.status());
            var substrings = check.metadata().get().split("#");
            var prMetadata1 = substrings[0];
            var issueMetadata1 = (substrings.length > 1) ? substrings[1] : "";
            assertNotEquals("", issueMetadata1);

            // Run issueBot, check should not be updated
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime2 = check.completedAt().get();
            assertEquals(completedTime1, completedTime2);

            // Update issue2
            issue2.setProperty("priority", JSON.of("4"));
            // Run prBot first, check should not be updated
            TestBotRunner.runPeriodicItems(prBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime3 = check.completedAt().get();
            assertEquals(completedTime2, completedTime3);

            // Run issueBot, check should be updated
            TestBotRunner.runPeriodicItems(issueBot);
            check = pr.checks(editHash).get("jcheck");
            var completedTime4 = check.completedAt().get();
            assertNotEquals(completedTime3, completedTime4);
            substrings = check.metadata().get().split("#");
            var prMetadata2 = substrings[0];
            var issueMetadata2 = (substrings.length > 1) ? substrings[1] : "";
            assertNotEquals(prMetadata1, prMetadata2);
            assertNotEquals(issueMetadata1, issueMetadata2);
            assertTrue(pr.store().body().contains("This is an issue (**Bug** - P3)"));
            assertTrue(pr.store().body().contains("This is an issue2 (**Bug** - P4)"));

            // Update issue
            issue.setProperty("priority", JSON.of("1"));
            // Run prBot first
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().body().contains("This is an issue (**Bug** - P1)"));
            // Run issueBot
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().body().contains("This is an issue (**Bug** - P1)"));
            assertTrue(pr.store().body().contains("This is an issue2 (**Bug** - P4)"));
        }
    }

    @Test
    void maintainerApproval(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            issue.setProperty("priority", JSON.of("4"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(issuePRMap)
                    .approval(new Approval("", "jdk17u-fix-request", "jdk17u-fix-yes",
                            "jdk17u-fix-no", "https://example.com", true, "maintainer approval"))
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

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
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().labelNames().contains("rfr"));

            issue.addLabel("jdk17u-fix-request");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().body().contains("Requested"));

            issue.addLabel("jdk17u-fix-yes");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().body().contains("Approved"));
        }
    }

    @Test
    void maintainerApprovalWithBranchPattern(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            issue.setProperty("priority", JSON.of("4"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            Approval approval = new Approval("", "-critical-request", "-critical-approved",
                    "-critical-rejected", "https://example.com", true, "critical request");
            approval.addBranchPrefix(Pattern.compile("jdk20.0.1"), "CPU23_04");
            approval.addBranchPrefix(Pattern.compile("jdk20.0.2"), "CPU23_05");

            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(issuePRMap)
                    .approval(approval)
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var otherHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(otherHash, author.authenticatedUrl(), "jdk20.0.1", true);

            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": This is an issue");

            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().labelNames().contains("rfr"));
            assertFalse(pr.store().body().contains("[TEST-1](http://localhost/project/testTEST-1) needs critical request"));

            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().labelNames().contains("ready"));

            pr.setTargetRef("jdk20.0.1");
            reviewerPr.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("ready"));
            assertTrue(pr.store().body().contains("[TEST-1](http://localhost/project/testTEST-1) needs critical request"));
            assertEquals("⚠️  @user1 This change is now ready for you to apply for [critical request](https://example.com). " +
                            "This can be done directly in each associated issue or by using the " +
                            "[/approval](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/approval) command." +
                            "<!-- PullRequestBot approval needed comment -->"
                    , pr.store().comments().get(2).body());

            issue.addLabel("CPU23_04-critical-request");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().body().contains("Requested"));

            issue.addLabel("CPU23_04-critical-approved");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().body().contains("Approved"));
            assertTrue(pr.store().body().contains("[TEST-1](http://localhost/project/testTEST-1) needs critical request"));
            assertEquals("⚠️  @user1 This change is now ready for you to apply for [critical request](https://example.com). " +
                            "This can be done directly in each associated issue or by using the " +
                            "[/approval](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/approval) command." +
                            "<!-- PullRequestBot approval needed comment -->"
                    , pr.store().comments().get(2).body());
        }
    }
}
