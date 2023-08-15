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
                    "-critical-rejected", "https://example.com", "https://command.com");
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
            assertLastCommentContains(pr, "Can only request approval for issues in TEST!");
            assertFalse(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval request My reason line1\nMy reason line2\nMy reason line3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The maintainer approval request has been created successfully! Please wait for maintainers to process this request.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(pr.store().labelNames().contains(APPROVAL_LABEL));

            pr.addComment("/approval 1 request new reason line1\nnew reason line2\nnew reason line3");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The maintainer approval request has been updated successfully! Please wait for maintainers to process this request.");
            TestBotRunner.runPeriodicItems(issueBot);
            assertTrue(issue.comments().stream().anyMatch(comment -> comment.body().contains("new reason")));

            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addReview(Review.Verdict.APPROVED, "LGTM");
            TestBotRunner.runPeriodicItems(prBot);
            assertFalse(pr.store().labelNames().contains("ready"));

            reviewerPr.addComment("/approve yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The maintainer approval request has been approved!");

            reviewerPr.addComment("/approve 1 yes");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The maintainer approval request has been approved!");
            assertTrue(pr.store().labelNames().contains(APPROVAL_LABEL));
            assertFalse(pr.store().labelNames().contains("ready"));

            TestBotRunner.runPeriodicItems(issueBot);
            assertFalse(pr.store().labelNames().contains(APPROVAL_LABEL));
            assertTrue(pr.store().labelNames().contains("ready"));

            pr.addComment("/approval cancel cancel it");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The request has been processed by maintainer! Could not cancel the request now.");
        }
    }
}
