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
import static org.openjdk.skara.bots.approval.ApprovalPullRequestWorkItem.APPROVAL_UPDATE_MARKER;
import static org.openjdk.skara.bots.approval.ApprovalPullRequestWorkItem.PROGRESS_MARKER;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

public class ApprovalIssueBotTests {
    @Test
    void testTriggerPullRequestWorkItem(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var maintainer = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var bot = new ApprovalIssueBot(issueProject, List.of(author),
                    List.of(new ApprovalInfo(author, Pattern.compile("test"),
                                    "test-fix-request" , "test-fix-yes" , "test-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("master"),
                                    "master-fix-request" , "master-fix-yes" , "master-fix-no" , Set.of("integrationreviewer3")),
                            new ApprovalInfo(author, Pattern.compile("jdk18"),
                                    "jdk18-fix-request" , "jdk18-fix-yes" , "jdk18-fix-no" , Set.of("integrationreviewer3"))));

            var issue = issueProject.createIssue("This is update change issue" , List.of(), Map.of());
            issue.setProperty("issuetype" , JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master" , true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit" , true);

            // Create a pull request
            var pr = credentials.createPullRequest(author, "master" , "edit" ,
                    issue.id() + ": " + issue.title(), List.of("PR body" , PROGRESS_MARKER));
            PullRequestUtils.postPullRequestLinkComment(issue, pr);

            // run the approval issue bot
            TestBotRunner.runPeriodicItems(bot);

            // The existing issue won't trigger the ApprovalIssueWorkItem and ApprovalPullRequestWorkItem.
            assertFalse(pr.body().contains(APPROVAL_UPDATE_MARKER));

            // Update the issue
            issue.addLabel("master-fix-request");
            // Now the pull request body doesn't have the update marker.
            assertFalse(pr.body().contains(APPROVAL_UPDATE_MARKER));

            // Run the approval issue bot
            TestBotRunner.runPeriodicItems(bot);

            // The updated issue trigger the ApprovalIssueWorkItem and ApprovalPullRequestWorkItem,
            // so the update marker is added to the pull request body.
            assertTrue(pr.body().contains(APPROVAL_UPDATE_MARKER));

            // Update the issue again.
            issue.removeLabel("master-fix-request");
            issue.addLabel("master-fix-yes");
            // Now the issue doesn't have the fix request label.
            assertFalse(issue.labelNames().contains("master-fix-request"));

            // Run the approval issue bot
            TestBotRunner.runPeriodicItems(bot);

            // The updated issue trigger the ApprovalIssueWorkItem and ApprovalPullRequestWorkItem,
            // so the fix request label is added to the issue automatically.
            assertTrue(issue.labelNames().contains("master-fix-request"));
        }
    }
}
