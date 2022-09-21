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
import static org.openjdk.skara.bots.pr.PullRequestCommandWorkItem.VALID_BOT_COMMAND_MARKER;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

public class RequestApprovalCommandTests {
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

            // Create a pull request.
            var pr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // Use the command `request-approval`.
            pr.addComment("/request-approval request-approval-test\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("the text you provide has been successfully "
                            + "added to the main issue as a comment"))
                    .count();
            assertEquals(1, commentSize);
            // The issue should have one corresponding comment.
            commentSize = issue.store().comments().stream()
                    .filter(comment -> comment.body().contains("request-approval-test"))
                    .count();
            assertEquals(1, commentSize);

            // Use the command `request-approval` again.
            pr.addComment("/request-approval request-approval-test\nanother comment" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment, now the pull request has two such comments.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("the text you provide has been successfully "
                            + "added to the main issue as a comment"))
                    .count();
            assertEquals(2, commentSize);
            // The issue should have two corresponding comments.
            commentSize = issue.store().comments().stream()
                    .filter(comment -> comment.body().contains("request-approval-test"))
                    .count();
            assertEquals(2, commentSize);
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

            // User the command `request-approval`
            pr.addComment("/request-approval request-approval-test\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("this repository or the target branch of "
                            + "this pull request have not been configured to use the `request-approval` command"))
                    .count();
            assertEquals(1, commentSize);
            // The issue shouldn't have related comment.
            commentSize = issue.store().comments().stream()
                    .filter(comment -> comment.body().contains("request-approval-test"))
                    .count();
            assertEquals(0, commentSize);

            // Create another pull request targeted to the `master` branch.
            localRepo.push(masterHash, author.url(), "master", true);
            var anotherPr = credentials.createPullRequest(author, "master", "edit", issue.id() + ": " + issue.title());

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // Use the command `request-approval` by using non-author role.
            var reviewerPr = reviewer.pullRequest(anotherPr.id());
            reviewerPr.addComment("/request-approval request-approval-test\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            commentSize = anotherPr.store().comments().stream()
                    .filter(comment -> comment.body().contains("only the pull request author is allowed to use the `request-approval` command"))
                    .count();
            assertEquals(1, commentSize);
            // The issue shouldn't have related comment.
            commentSize = issue.store().comments().stream()
                    .filter(comment -> comment.body().contains("request-approval-test"))
                    .count();
            assertEquals(0, commentSize);
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
            var pr = credentials.createPullRequest(author, "master", "edit", issue.title());

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // Use the command `request-approval`.
            pr.addComment("/request-approval request-approval-test\n" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            var commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("the title of the pull request doesn't contain the main issue"))
                    .count();
            assertEquals(1, commentSize);
            // The issue shouldn't have related comment.
            commentSize = issue.store().comments().stream()
                    .filter(comment -> comment.body().contains("request-approval-test"))
                    .count();
            assertEquals(0, commentSize);

            // Change the pr title to wrong issue id
            pr.setTitle("2: " + issue.title());

            // Use the command `request-approval` again.
            pr.addComment("/request-approval request-approval-test" + VALID_BOT_COMMAND_MARKER);

            // run the pr bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply a comment.
            commentSize = pr.store().comments().stream()
                    .filter(comment -> comment.body().contains("the main issue of the pull request title is not found"))
                    .count();
            assertEquals(1, commentSize);
            // The issue shouldn't have related comment.
            commentSize = issue.store().comments().stream()
                    .filter(comment -> comment.body().contains("request-approval-test"))
                    .count();
            assertEquals(0, commentSize);
        }
    }
}
