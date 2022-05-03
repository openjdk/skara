/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

public class JEPCommandTests {
    @Test
    void testNormal(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issueProject)
                    .censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            var jepIssue = issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Submitted"), JEP_NUMBER, JSON.of("123")));
            var jepIssueTargeted = issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Targeted"), JEP_NUMBER, JSON.of("234")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", mainIssue.id() + ": " + mainIssue.title());
            var prAsReviewer = reviewer.pullRequest(pr.id());

            // PR should not have the `jep` label
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));

            // Require jep by using `JEP-<id>`
            pr.addComment("/jep JEP-123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Not require jep
            prAsReviewer.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep by using `jep-<id>`
            pr.addComment("/jep jep-123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Not require jep
            prAsReviewer.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep by using `Jep-<id>`
            pr.addComment("/jep Jep-123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Not require jep
            prAsReviewer.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with strange jep prefix
            pr.addComment("/jep jEP-123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Not require jep
            prAsReviewer.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep by using `issue-id`(<ProjectName>-<id>)
            pr.addComment("/jep TEST-3");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "The JEP for this pull request, [JEP-");
            assertLastCommentContains(pr, "has already been targeted.");
            assertTrue(pr.body().contains("- [x] Change requires a JEP request to be targeted"));

            // Not require jep by using `uneeded`
            prAsReviewer.addComment("/jep uneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep by using `issue-id` which doesn't have the project name
            pr.addComment("/jep 3");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "The JEP issue of the JEP argument `3` was not found.");
            assertLastCommentContains(pr, "We will treat the argument `3` as an issue ID.");
            assertLastCommentContains(pr, "The JEP for this pull request, [JEP-");
            assertLastCommentContains(pr, "has already been targeted.");
            assertTrue(pr.body().contains("- [x] Change requires a JEP request to be targeted"));

            // Not require jep
            prAsReviewer.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with right JEP ID without prefix
            pr.addComment("/jep 123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));
        }
    }

    @Test
    void testAuthorization(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var committer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addCommitter(committer.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issueProject)
                    .censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            var jepIssue = issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Submitted"), JEP_NUMBER, JSON.of("123")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", mainIssue.id() + ": " + mainIssue.title());
            var prAsReviewer = reviewer.pullRequest(pr.id());
            var prAsCommitter = committer.pullRequest(pr.id());

            // PR should not have the `jep` label
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));

            // Require jep by a committer who is not the PR author
            prAsCommitter.addComment("/jep JEP-123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "Only the pull request author and [Reviewers]" +
                    "(https://openjdk.java.net/bylaws#reviewer) are allowed to use the `jep` command.");

            // Require jep by the PR author
            pr.addComment("/jep TEST-2");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Require jep by a reviewer
            prAsReviewer.addComment("/jep TEST-2");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
            assertLastCommentContains(pr, "has been targeted.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Not require jep by a committer
            prAsCommitter.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "Only the pull request author and [Reviewers]" +
                    "(https://openjdk.java.net/bylaws#reviewer) are allowed to use the `jep` command.");
            assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));

            // Not require jep by a reviewer
            prAsReviewer.addComment("/jep unneeded");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertLastCommentContains(pr, "determined that the JEP request is not needed for this pull request.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));
        }
    }

    @Test
    void testIssueTypo(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issueProject)
                    .censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            var jepIssue = issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Submitted"), JEP_NUMBER, JSON.of("123")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));

            // Require jep with blank value
            pr.addComment("/jep");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(3, pr.comments().size());
            assertLastCommentContains(pr, "Command syntax:");
            assertLastCommentContains(pr, "Some examples:");
            // Test the symbol `\` of the text block
            assertLastCommentContains(pr, "The prefix (i.e. `JDK-`, `JEP-` or `jep-`) is optional. If the argument is given without prefix, "
                    + "it will be tried first as a JEP ID and second as an issue ID. The issue type must be `JEP`.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with blank space
            pr.addComment("/jep   ");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(5, pr.comments().size());
            assertLastCommentContains(pr, "Command syntax:");
            assertLastCommentContains(pr, "Some examples:");
            // Test the symbol `\` of the text block
            assertLastCommentContains(pr, "The prefix (i.e. `JDK-`, `JEP-` or `jep-`) is optional. If the argument is given without prefix, "
                    + "it will be tried first as a JEP ID and second as an issue ID. The issue type must be `JEP`.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with wrong jep prefix
            pr.addComment("/jep je-123");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(7, pr.comments().size());
            assertLastCommentContains(pr, "The JEP issue was not found. Please make sure you have entered it correctly.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with wrong jep id without prefix
            pr.addComment("/jep 1");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(9, pr.comments().size());
            assertLastCommentContains(pr, "The JEP issue of the JEP argument `1` was not found.");
            assertLastCommentContains(pr, "We will treat the argument `1` as an issue ID.");
            assertLastCommentContains(pr, "The issue `TEST-1` is not a JEP. Please make sure you have entered it correctly.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with wrong project prefix
            pr.addComment("/jep TESt-2");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(11, pr.comments().size());
            assertLastCommentContains(pr, "The JEP issue was not found. Please make sure you have entered it correctly.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with wrong `jep-id`
            pr.addComment("/jep jep-1");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(13, pr.comments().size());
            assertLastCommentContains(pr, "The JEP issue was not found. Please make sure you have entered it correctly.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));

            // Require jep with wrong issue type
            pr.addComment("/jep TEST-1");

            // Verify the behavior
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
            assertEquals(15, pr.comments().size());
            assertLastCommentContains(pr, "The issue `TEST-1` is not a JEP. Please make sure you have entered it correctly.");
            assertFalse(pr.body().contains("Change requires a JEP request to be targeted"));
        }
    }

    @Test
    void testJepIssueStatus(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(bot).issueProject(issueProject)
                    .censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            var statusList = List.of("Draft", "Submitted", "Candidate", "Proposed to Target",
                    "Proposed to Drop", "Closed", "Targeted", "Integrated", "Completed");
            for (int i = 1; i <= 9; i++) {
                issueProject.createIssue(statusList.get(i - 1) + " jep", List.of("Jep body"), Map.of("issuetype", JSON.of("JEP"),
                        "status", JSON.object().put("name", statusList.get(i - 1)), JEP_NUMBER, JSON.of(String.valueOf(i))));
            }
            issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Closed"),
                           "resolution", JSON.object().put("name", "Delivered"), JEP_NUMBER, JSON.of("10")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));

            // Test draft/submitted/candidate/proposedToTarget/proposedToDrop/closedWithoutDelivered JEPs
            for (int i = 1; i <= 6; i++) {
                pr.addComment("/jep jep-" + i);
                TestBotRunner.runPeriodicItems(prBot);
                assertTrue(pr.labelNames().contains(JEPCommand.JEP_LABEL));
                assertEquals(i * 2 + 1, pr.comments().size());
                assertLastCommentContains(pr, "This pull request will not be integrated until the [JEP-");
                assertLastCommentContains(pr, "has been targeted.");
                assertTrue(pr.body().contains("- [ ] Change requires a JEP request to be targeted"));
            }

            // Test targeted/integrated/completed/closedWithDelivered JEPs
            for (int i = 7; i <= 10; i++) {
                pr.addComment("/jep jep-" + i);
                TestBotRunner.runPeriodicItems(prBot);
                assertFalse(pr.labelNames().contains(JEPCommand.JEP_LABEL));
                assertEquals(i * 2 + 1, pr.comments().size());
                assertLastCommentContains(pr, "The JEP for this pull request, [JEP-");
                assertLastCommentContains(pr, "has already been targeted.");
                assertTrue(pr.body().contains("- [x] Change requires a JEP request to be targeted"));
            }
        }
    }
}
