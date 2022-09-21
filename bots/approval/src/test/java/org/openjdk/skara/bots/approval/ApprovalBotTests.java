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
package org.openjdk.skara.bots.approval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.bots.approval.ApprovalWorkItem.APPROVAL_UPDATE_MARKER;
import static org.openjdk.skara.bots.approval.ApprovalWorkItem.PROGRESS_MARKER;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

public class ApprovalBotTests {
    @Test
    void testApproval(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var bot = new ApprovalBot(issueProject, List.of(author),
                    List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request" , "test-fix-yes" , "test-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request" , "master-fix-yes" , "master-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request" , "jdk18-fix-yes" , "jdk18-fix-no" , Set.of("integrationreviewer3"))));

            var issue = issueProject.createIssue("This is update change issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request
            var pr = credentials.createPullRequest(author, "master", "edit",
                    issue.id() + ": " + issue.title(), List.of("PR body", PROGRESS_MARKER));
            pr.setBody(pr.body() + "\n- [ ] All issues must be");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // review the pr
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "LGTM");

            // Simulate the PRBot. Add the `approval` label to the pull request.
            pr.addLabel("approval");
            // Simulate the PRBot. Add the `master-fix-request` label to the issue.
            issue.addLabel("master-fix-request");

            // Approve the update change.
            issue.addLabel("master-fix-yes");

            // run the approval pull request bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should remove the `approval` label of the pull request.
            assertFalse(pr.store().labelNames().contains("approval"));
            // The bot should add the approval update marker.
            assertTrue(pr.store().body().contains(APPROVAL_UPDATE_MARKER));
        }
    }

    @Test
    void testDisapproval(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var bot = new ApprovalBot(issueProject, List.of(author),
                    List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request" , "test-fix-yes" , "test-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request" , "master-fix-yes" , "master-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request" , "jdk18-fix-yes" , "jdk18-fix-no" , Set.of("integrationreviewer3"))));

            var issue = issueProject.createIssue("This is update change issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request
            var pr = credentials.createPullRequest(author, "master", "edit",
                    issue.id() + ": " + issue.title(), List.of("PR body", PROGRESS_MARKER));
            pr.setBody(pr.body() + "\n- [ ] All issues must be");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // review the pr
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "LGTM");

            // Simulate the PRBot. Add the `approval` label to the pull request.
            pr.addLabel("approval");
            // Simulate the PRBot. Add the `master-fix-request` label to the issue.
            issue.addLabel("master-fix-request");

            // Reject the update change.
            issue.addLabel("master-fix-no");

            // run the approval pull request bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should remove the `approval` label of the pull request.
            assertFalse(pr.store().labelNames().contains("approval"));
            // The bot should add the approval update marker.
            assertTrue(pr.store().body().contains(APPROVAL_UPDATE_MARKER));
        }
    }

    @Test
    void testNoFixRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var bot = new ApprovalBot(issueProject, List.of(author),
                    List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request" , "test-fix-yes" , "test-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request" , "master-fix-yes" , "master-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request" , "jdk18-fix-yes" , "jdk18-fix-no" , Set.of("integrationreviewer3"))));

            var issue = credentials.createIssue(issueProject, "This is update change issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request
            var pr = credentials.createPullRequest(author, "master", "edit",
                    issue.id() + ": " + issue.title(), List.of("PR body", PROGRESS_MARKER));
            pr.setBody(pr.body() + "\n- [ ] All issues must be");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // Don't review the pr, so the update change is not ready for approval.
            // Don't add the `approval` label to the pull request.
            // Don't add the `master-fix-request` label to the issue.

            // Approve the update change.
            issue.addLabel("master-fix-yes");

            // run the approval pull request bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should add the fix request label to the issue.
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            // The pull request shouldn't have the `approval` label.
            assertFalse(pr.store().labelNames().contains("approval"));
            // The bot should add the approval update marker.
            assertTrue(pr.store().body().contains(APPROVAL_UPDATE_MARKER));
        }
    }

    @Test
    void testWrongCheckedProgress(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var bot = new ApprovalBot(issueProject, List.of(author),
                    List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request" , "test-fix-yes" , "test-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request" , "master-fix-yes" , "master-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request" , "jdk18-fix-yes" , "jdk18-fix-no" , Set.of("integrationreviewer3"))));

            var issue = issueProject.createIssue("This is update change issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request
            var pr = credentials.createPullRequest(author, "master", "edit",
                    issue.id() + ": " + issue.title(), List.of("PR body", PROGRESS_MARKER));
            PullRequestUtils.postPullRequestLinkComment(issue, pr);
            // review the pr
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "LGTM");

            // Simulate the PRBot. Add the `approval` label to the pull request.
            pr.addLabel("approval");
            // Simulate the PRBot. Add the `master-fix-request` label to the issue.
            issue.addLabel("master-fix-request");

            // The progress has been checked because of previous approval.
            pr.setBody(pr.body() + "\n- [x] All issues must be");

            // Reject the update change.
            issue.addLabel("master-fix-no");

            // run the approval pull request bot
            TestBotRunner.runPeriodicItems(bot);

            // The pull request shouldn't have the `approval` label.
            assertFalse(pr.store().labelNames().contains("approval"));
            // The bot should add the approval update marker.
            assertTrue(pr.store().body().contains(APPROVAL_UPDATE_MARKER));
        }
    }

    @Test
    void testWrongIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var bot = new ApprovalBot(issueProject, List.of(author),
                    List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request" , "test-fix-yes" , "test-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request" , "master-fix-yes" , "master-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request" , "jdk18-fix-yes" , "jdk18-fix-no" , Set.of("integrationreviewer3"))));

            var issue = issueProject.createIssue("This is update change issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request which has the wrong title.
            var pr = credentials.createPullRequest(author, "master", "edit", issue.title(), List.of("PR body", PROGRESS_MARKER));
            pr.setBody(pr.body() + "\n- [ ] All issues must be");
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // review the pr
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "LGTM");

            // Simulate the PRBot. Add the `approval` label to the pull request.
            pr.addLabel("approval");
            // Simulate the PRBot. Add the `master-fix-request` label to the issue.
            issue.addLabel("master-fix-request");

            // Approve the update change.
            issue.addLabel("master-fix-yes");

            // run the approval pull request bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot shouldn't remove the `approval` label of the pull request.
            assertTrue(pr.store().labelNames().contains("approval"));
            // The bot shouldn't add the approval update marker.
            assertFalse(pr.store().body().contains(APPROVAL_UPDATE_MARKER));

            // Change the pr title to wrong issue id
            pr.setTitle("2: " + issue.title());

            // run the approval pull request bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot shouldn't remove the `approval` label of the pull request.
            assertTrue(pr.store().labelNames().contains("approval"));
            // The bot shouldn't add the approval update marker.
            assertFalse(pr.store().body().contains(APPROVAL_UPDATE_MARKER));
        }
    }
}
