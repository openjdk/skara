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
package org.openjdk.skara.forge;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;

public class PullRequestUtilsTests {

    @Test
    void pullRequestLinkComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            var pr1 = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");

            {
                assertEquals(0, issue.comments().size());

                PullRequestUtils.postPullRequestLinkComment(issue, pr1);
                assertEquals(1, issue.comments().size());

                var prLinks = PullRequestUtils.pullRequestCommentLink(issue);
                assertEquals(pr1.webUrl(), prLinks.get(0));

                PullRequestUtils.removePullRequestLinkComment(issue, pr1);
                assertEquals(0, issue.comments().size());
            }
            {
                var pr2 = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");

                PullRequestUtils.postPullRequestLinkComment(issue, pr1);
                PullRequestUtils.postPullRequestLinkComment(issue, pr2);
                assertEquals(2, issue.comments().size());

                var prLinks = PullRequestUtils.pullRequestCommentLink(issue);
                assertEquals(pr1.webUrl(), prLinks.get(0));
                assertEquals(pr2.webUrl(), prLinks.get(1));

                PullRequestUtils.removePullRequestLinkComment(issue, pr1);
                assertEquals(1, issue.comments().size());

                prLinks = PullRequestUtils.pullRequestCommentLink(issue);
                assertEquals(pr2.webUrl(), prLinks.get(0));
            }
        }
    }
}
