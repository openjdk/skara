/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.common.PullRequestConstants.APPROVAL_LABEL;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class ApprovalAndApproveCommandTests {
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
            issue.setProperty("priority", JSON.of("4"));

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            Approval approval = new Approval("", "-critical-request", "-critical-approved",
                    "-critical-rejected", "https://example.com", true, "maintainer approval");
            approval.addBranchPrefix(Pattern.compile("jdk20.0.1"), "CPU23_04");
            approval.addBranchPrefix(Pattern.compile("jdk20.0.2"), "CPU23_05");

            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(issuePRMap)
                    .approval(approval)
                    .integrators(Set.of(reviewer.forge().currentUser().username()))
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "jdk20.0.1", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);

            var pr = credentials.createPullRequest(author, "jdk20.0.1", "edit", issue.id() + ": This is an issue");

            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("ready"));

            pr.addComment("/approval");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "usage: `/approval [<id>] (request|cancel) [<text>]`");
            assertFalse(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval JDK-1 request");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Approval can only be requested for issues in the TEST project.");
            assertFalse(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval request My reason line1\nMy reason line2\nMy reason line3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) has been created successfully.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval cancel");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The approval request has been cancelled successfully.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertFalse(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval 1 request");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) has been created successfully.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval 1 request new reason line1\nnew reason line2\nnew reason line3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) has been updated successfully.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(issue.comments().stream().anyMatch(comment -> comment.body().contains("new reason")));

            pr.addComment("/approval 1 request new reason line1\nnew reason line2\nnew reason line3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) was already up to date.");

            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "LGTM");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("ready"));

            reviewerPr.addComment("/approve yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "1: The approval request has been approved.");

            reviewerPr.addComment("/approve 1 yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "1: The approval request has been approved.");
            assertTrue(pr.store().labelNames().contains(APPROVAL_LABEL));
            assertFalse(pr.store().labelNames().contains("ready"));

            TestBotRunner.runPeriodicItems(issueBot);
            assertFalse(pr.store().labelNames().contains(APPROVAL_LABEL));
            assertTrue(pr.store().labelNames().contains("ready"));

            pr.addComment("/approval cancel cancel it");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The request has already been handled by a maintainer and can no longer be canceled.");
        }
    }

    @Test
    void multipleIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var bot = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var issue1 = issueProject.createIssue("This is an issue", List.of(), Map.of());
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setProperty("priority", JSON.of("4"));
            issue1.addLabel("CPU23_04-critical-request");
            var issue2 = issueProject.createIssue("This is an issue 2", List.of(), Map.of());
            issue2.setProperty("issuetype", JSON.of("Bug"));
            issue2.setProperty("priority", JSON.of("2"));
            issue2.addLabel("CPU23_04-critical-request");

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            Map<String, List<PRRecord>> issuePRMap = new HashMap<>();
            Approval approval = new Approval("", "-critical-request", "-critical-approved",
                    "-critical-rejected", "https://example.com", true, "maintainer approval");
            approval.addBranchPrefix(Pattern.compile("jdk20.0.1"), "CPU23_04");
            approval.addBranchPrefix(Pattern.compile("jdk20.0.2"), "CPU23_05");

            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .issueProject(issueProject)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(issuePRMap)
                    .approval(approval)
                    .integrators(Set.of(reviewer.forge().currentUser().username()))
                    .build();
            var issueBot = new IssueBot(issueProject, List.of(author), Map.of(bot.name(), prBot), issuePRMap);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "jdk20.0.1", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);

            var pr = credentials.createPullRequest(author, "jdk20.0.1", "edit", issue1.id() + ": This is an issue");

            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("ready"));

            pr.addComment("/issue " + issue2.id());
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Adding additional issue to issue list: `2: This is an issue 2`.");

            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "LGTM");
            TestBotRunner.runPeriodicItems(prBot);

            pr.addComment("/approval 1 cancel");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "This change is now ready for you to apply for [maintainer approval](https://example.com).");
            pr.addComment("/approval 2 cancel");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);

            reviewerPr.addComment("/approve yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "1: There is no maintainer approval request for this issue.");
            assertLastCommentContains(pr, "2: There is no maintainer approval request for this issue.");

            reviewerPr.addComment("/approve JDK-1 yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "JDK-1: Can only approve issues in the TEST project.");

            pr.addComment("/approval request my reason");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) has been created successfully.");
            assertLastCommentContains(pr, "2: The approval [request](http://localhost/project/testTEST-2?focusedCommentId=0) has been created successfully.");

            pr.addComment("/approval cancel");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval request has been cancelled successfully.");
            assertLastCommentContains(pr, "2: The approval request has been cancelled successfully.");

            pr.addComment("/approval 1 request my reason for 1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) has been created successfully.");

            pr.addComment("/approval request my reason");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval [request](http://localhost/project/testTEST-1?focusedCommentId=0) has been updated successfully.");
            assertLastCommentContains(pr, "2: The approval [request](http://localhost/project/testTEST-2?focusedCommentId=0) has been created successfully.");

            reviewerPr.addComment("/approve 1 no");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval request has been rejected.");

            reviewerPr.addComment("/approve no");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval request has been rejected.");
            assertLastCommentContains(pr, "2: The approval request has been rejected.");

            reviewerPr.addComment("/approve 1 yes");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(issueBot);
            assertLastCommentContains(pr, "1: The approval request has been approved.");

            reviewerPr.addComment("/approve yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "1: The approval request has been approved.");
            assertLastCommentContains(pr, "2: The approval request has been approved.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.labelNames().contains("ready"));
        }
    }
}
