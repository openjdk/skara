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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.bots.pr.PullRequestCommandWorkItem.VALID_BOT_COMMAND_MARKER;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

public class ApproveCommandTests {
    @Test
    void testNormal(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var census = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addReviewer(maintainer.forge().currentUser().id())
                    .build();

            var bot = PullRequestBot.newBuilder().repo(author)
                    .censusRepo(census).issueProject(issueProject)
                    .approvalInfos(List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request", "test-fix-yes", "test-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request", "master-fix-yes", "master-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request", "jdk18-fix-yes", "jdk18-fix-no", Set.of("integrationreviewer3"))))
                    .build();

            var issue = credentials.createIssue(issueProject, "This is update change issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // Create another two issues.
            var issue2 = credentials.createIssue(issueProject, "This is update change issue2");
            issue2.setProperty("issuetype", JSON.of("Bug"));
            var issue3 = credentials.createIssue(issueProject, "This is update change issue3");
            issue3.setProperty("issuetype", JSON.of("Bug"));
            pr.addComment("/issue add " + issue2.id() + "\n" + VALID_BOT_COMMAND_MARKER);
            pr.addComment("/issue add " + issue3.id() + "\n" + VALID_BOT_COMMAND_MARKER);

            // review the pr
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "LGTM");

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the approval should be added to the pr body.
            assertTrue(pr.store().body().contains("- [ ] All issues must be"));
            // The pr should contain the `approval` label because the pr is ready for approval
            assertTrue(pr.store().labelNames().contains("approval"));
            // These three issues should contain the `master-fix-request` label
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            assertTrue(issue2.store().labelNames().contains("master-fix-request"));
            assertTrue(issue3.store().labelNames().contains("master-fix-request"));
            // These three issues shouldn't contain the `master-fix-yes` and `master-fix-no` label
            assertFalse(issue.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue2.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue3.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue.store().labelNames().contains("master-fix-no"));
            assertFalse(issue2.store().labelNames().contains("master-fix-no"));
            assertFalse(issue3.store().labelNames().contains("master-fix-no"));

            // Approve the update change by using the command `approve`.
            var maintainerPr = maintainer.pullRequest(pr.id());
            maintainerPr.addComment("/approve yes\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added only once.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval should be checked.
            assertTrue(pr.store().body().contains("- [x] All issues must be"));
            // The pr shouldn't contain the `approval` label because the pr has been approved.
            assertFalse(pr.store().labelNames().contains("approval"));
            // These three issues should contain the `master-fix-request` and `master-fix-yes` label
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            assertTrue(issue2.store().labelNames().contains("master-fix-request"));
            assertTrue(issue3.store().labelNames().contains("master-fix-request"));
            assertTrue(issue.store().labelNames().contains("master-fix-yes"));
            assertTrue(issue2.store().labelNames().contains("master-fix-yes"));
            assertTrue(issue3.store().labelNames().contains("master-fix-yes"));
            // These three issues shouldn't contain the `master-fix-no` label
            assertFalse(issue.store().labelNames().contains("master-fix-no"));
            assertFalse(issue2.store().labelNames().contains("master-fix-no"));
            assertFalse(issue3.store().labelNames().contains("master-fix-no"));
            // The bot should add a comment to reply.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was approved by the maintainer"))
                    .count();
            assertEquals(1, commentSize);

            // Reject the update change by using the command `approve`.
            maintainerPr.addComment("/approve no\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added only once.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval shouldn't be checked.
            assertTrue(pr.store().body().contains("- [ ] All issues must be"));
            // The pr shouldn't contain the `approval` label because the pr has been rejected.
            assertFalse(pr.store().labelNames().contains("approval"));
            // These three issues should contain the `master-fix-request` and `master-fix-no` label
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            assertTrue(issue2.store().labelNames().contains("master-fix-request"));
            assertTrue(issue3.store().labelNames().contains("master-fix-request"));
            assertTrue(issue.store().labelNames().contains("master-fix-no"));
            assertTrue(issue2.store().labelNames().contains("master-fix-no"));
            assertTrue(issue3.store().labelNames().contains("master-fix-no"));
            // These three issues shouldn't contain the `master-fix-yes` label
            assertFalse(issue.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue2.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue3.store().labelNames().contains("master-fix-yes"));
            // The bot should add a comment to reply.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was rejected by the maintainer"))
                    .count();
            assertEquals(1, commentSize);

            // Approve the update change again.
            maintainerPr.addComment("/approve yes\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added only once.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval should be checked.
            assertTrue(pr.store().body().contains("- [x] All issues must be"));
            // The pr shouldn't contain the `approval` label because the pr has been approved.
            assertFalse(pr.store().labelNames().contains("approval"));
            // These three issues should contain the `master-fix-request` and `master-fix-yes` label
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            assertTrue(issue2.store().labelNames().contains("master-fix-request"));
            assertTrue(issue3.store().labelNames().contains("master-fix-request"));
            assertTrue(issue.store().labelNames().contains("master-fix-yes"));
            assertTrue(issue2.store().labelNames().contains("master-fix-yes"));
            assertTrue(issue3.store().labelNames().contains("master-fix-yes"));
            // These three issues shouldn't contain the `master-fix-no` label
            assertFalse(issue.store().labelNames().contains("master-fix-no"));
            assertFalse(issue2.store().labelNames().contains("master-fix-no"));
            assertFalse(issue3.store().labelNames().contains("master-fix-no"));
            // The bot should add a comment to reply, now it has two such comments.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was approved by the maintainer"))
                    .count();
            assertEquals(2, commentSize);
        }
    }

    @Test
    void testApprovalNotReady(TestInfo testInfo) throws IOException {
        // Approve or reject the update change when it is not ready for approval.
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var census = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addReviewer(maintainer.forge().currentUser().id())
                    .build();

            var bot = PullRequestBot.newBuilder().repo(author)
                    .censusRepo(census).issueProject(issueProject)
                    .approvalInfos(List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request", "test-fix-yes", "test-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request", "master-fix-yes", "master-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request", "jdk18-fix-yes", "jdk18-fix-no", Set.of("integrationreviewer3"))))
                    .build();

            // Create three issues.
            var issue = credentials.createIssue(issueProject, "This is update change issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var issue2 = credentials.createIssue(issueProject, "This is update change issue2");
            issue2.setProperty("issuetype", JSON.of("Bug"));
            var issue3 = credentials.createIssue(issueProject, "This is update change issue3");
            issue3.setProperty("issuetype", JSON.of("Bug"));

            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create pull request.
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // Add another two issues to the pull request.
            pr.addComment("/issue add " + issue2.id() + "\n" + VALID_BOT_COMMAND_MARKER);
            pr.addComment("/issue add " + issue3.id() + "\n" + VALID_BOT_COMMAND_MARKER);

            // Don't review the pr so that it is not ready for approval and the fix request label is not added to the issue.

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval should be added to the pr body.
            assertTrue(pr.store().body().contains("- [ ] All issues must be"));
            // The pr shouldn't contain the `approval` label because the pr is not ready for approval
            assertFalse(pr.store().labelNames().contains("approval"));
            // These three issues shouldn't contain the `master-fix-request`, `master-fix-yes` and `master-fix-no` label
            assertFalse(issue.store().labelNames().contains("master-fix-request"));
            assertFalse(issue2.store().labelNames().contains("master-fix-request"));
            assertFalse(issue3.store().labelNames().contains("master-fix-request"));
            assertFalse(issue.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue2.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue3.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue.store().labelNames().contains("master-fix-no"));
            assertFalse(issue2.store().labelNames().contains("master-fix-no"));
            assertFalse(issue3.store().labelNames().contains("master-fix-no"));

            // Approve the update change by using the command `approve`.
            var maintainerPr = maintainer.pullRequest(pr.id());
            maintainerPr.addComment("/approve yes\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added only once.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval should be checked.
            assertTrue(pr.store().body().contains("- [x] All issues must be"));
            // The pr shouldn't contain the `approval` label because the pr has been approved.
            assertFalse(pr.store().labelNames().contains("approval"));
            // These three issues should contain the `master-fix-request` and `master-fix-yes` label
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            assertTrue(issue2.store().labelNames().contains("master-fix-request"));
            assertTrue(issue3.store().labelNames().contains("master-fix-request"));
            assertTrue(issue.store().labelNames().contains("master-fix-yes"));
            assertTrue(issue2.store().labelNames().contains("master-fix-yes"));
            assertTrue(issue3.store().labelNames().contains("master-fix-yes"));
            // These three issues shouldn't contain the `master-fix-no` label
            assertFalse(issue.store().labelNames().contains("master-fix-no"));
            assertFalse(issue2.store().labelNames().contains("master-fix-no"));
            assertFalse(issue3.store().labelNames().contains("master-fix-no"));
            // The bot should add a comment to reply.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was approved by the maintainer"))
                    .count();
            assertEquals(1, commentSize);

            // <---- separator ---->

            // Clear the labels of the issues.
            issue.removeLabel("master-fix-request");
            issue2.removeLabel("master-fix-request");
            issue3.removeLabel("master-fix-request");
            issue.removeLabel("master-fix-yes");
            issue2.removeLabel("master-fix-yes");
            issue3.removeLabel("master-fix-yes");

            // Create another pull request.
            var anotherPr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // Add another two issues to the pull request.
            anotherPr.addComment("/issue add " + issue2.id() + "\n" + VALID_BOT_COMMAND_MARKER);
            anotherPr.addComment("/issue add " + issue3.id() + "\n" + VALID_BOT_COMMAND_MARKER);

            // Don't review the pr so that it is not ready for approval and the fix request label is not added to the issue.

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added.
            commentSize = anotherPr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval should be added to the pr body.
            assertTrue(anotherPr.store().body().contains("- [ ] All issues must be"));
            // The pr shouldn't contain the `approval` label because the pr is not ready for approval
            assertFalse(anotherPr.store().labelNames().contains("approval"));
            // These three issues shouldn't contain the `master-fix-request`, `master-fix-yes` and `master-fix-no` label
            assertFalse(issue.store().labelNames().contains("master-fix-request"));
            assertFalse(issue2.store().labelNames().contains("master-fix-request"));
            assertFalse(issue3.store().labelNames().contains("master-fix-request"));
            assertFalse(issue.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue2.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue3.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue.store().labelNames().contains("master-fix-no"));
            assertFalse(issue2.store().labelNames().contains("master-fix-no"));
            assertFalse(issue3.store().labelNames().contains("master-fix-no"));

            // Reject the update change by using the command `approve`.
            maintainerPr = maintainer.pullRequest(anotherPr.id());
            maintainerPr.addComment("/approve no\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The maintainer's approval suggestion should be added only once.
            commentSize = anotherPr.store().comments().stream()
                    .filter(comment -> comment.body().contains("<!-- Approval suggestion comment -->"))
                    .count();
            assertEquals(1, commentSize);
            // The progress about the maintainer's approval shouldn't be checked.
            assertTrue(anotherPr.store().body().contains("- [ ] All issues must be"));
            // The pr should contain the `approval` label because the pr has been rejected.
            assertFalse(anotherPr.store().labelNames().contains("approval"));
            // These three issues should contain the `master-fix-request` and `master-fix-no` label
            assertTrue(issue.store().labelNames().contains("master-fix-request"));
            assertTrue(issue2.store().labelNames().contains("master-fix-request"));
            assertTrue(issue3.store().labelNames().contains("master-fix-request"));
            assertTrue(issue.store().labelNames().contains("master-fix-no"));
            assertTrue(issue2.store().labelNames().contains("master-fix-no"));
            assertTrue(issue3.store().labelNames().contains("master-fix-no"));
            // These three issues shouldn't contain the `master-fix-yes` label
            assertFalse(issue.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue2.store().labelNames().contains("master-fix-yes"));
            assertFalse(issue3.store().labelNames().contains("master-fix-yes"));
            // The bot should add a comment to reply.
            commentSize = anotherPr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was rejected by the maintainer"))
                    .count();
            assertEquals(1, commentSize);
        }
    }

    @Test
    void testAuthorization(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var census = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addReviewer(maintainer.forge().currentUser().id())
                    .build();

            var bot = PullRequestBot.newBuilder().repo(author)
                    .censusRepo(census).issueProject(issueProject)
                    .approvalInfos(List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request", "test-fix-yes", "test-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request", "master-fix-yes", "master-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request", "jdk18-fix-yes", "jdk18-fix-no", Set.of("integrationreviewer3"))))
                    .build();

            var issue = credentials.createIssue(issueProject, "This is update change issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "main", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request targeted to the `main` branch which is not configured.
            var pr = credentials.createPullRequest(author, "main", "edit", issue.id() + ": " + issue.title());

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // Approve the update change.
            var maintainerPr = maintainer.pullRequest(pr.id());
            maintainerPr.addComment("/approve yes\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("the `approve` command can only be used on "
                            + "pull requests targeting branches and repositories that require approval."))
                    .count();
            assertEquals(1, commentSize);

            // Create another pull request targeted to the `master` branch.
            localRepo.push(masterHash, author.url(), "master", true);
            var anotherPr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // Reject the update change by using the reviewer role.
            var reviewerPr = reviewer.pullRequest(anotherPr.id());
            reviewerPr.addComment("/approve no\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            commentSize = anotherPr.store().comments().stream()
                    .filter(comment -> comment.body().contains("only the repository maintainers are allowed to use the `approve` command"))
                    .count();
            assertEquals(1, commentSize);
        }
    }

    @Test
    void testCommandTypo(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var census = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id())
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addReviewer(maintainer.forge().currentUser().id())
                    .build();

            var bot = PullRequestBot.newBuilder().repo(author)
                    .censusRepo(census).issueProject(issueProject)
                    .approvalInfos(List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request", "test-fix-yes", "test-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request", "master-fix-yes", "master-fix-no", Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request", "jdk18-fix-yes", "jdk18-fix-no", Set.of("integrationreviewer3"))))
                    .build();

            var issue = credentials.createIssue(issueProject, "This is update change issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);

            // Create a pull request.
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // Test command typo `yea`
            var maintainerPr = maintainer.pullRequest(pr.id());
            maintainerPr.addComment("/approve yea\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a help comment.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("usage: `/approve [yes|no|y|n]`"))
                    .count();
            assertEquals(1, commentSize);

            // Test command typo `ni`
            maintainerPr.addComment("/approve ni\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a help comment, now the pull request has two such comments.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("usage: `/approve [yes|no|y|n]`"))
                    .count();
            assertEquals(2, commentSize);

            // Note: the command is case-insensitive, the arguments `Yes`, `YeS`, `No`, `nO`, `Y`, `N` can be run successfully.

            // Test case-insensitive
            maintainerPr.addComment("/approve YeS\n" + VALID_BOT_COMMAND_MARKER);

            // The pull request now shouldn't have the approved comment.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was approved by the maintainer`"))
                    .count();
            assertEquals(0, commentSize);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply the approved comment.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this pull request was approved by the maintainer"))
                    .count();
            assertEquals(1, commentSize);
        }
    }
}
