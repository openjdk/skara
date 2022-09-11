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

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;

public class UpdatedPullRequestPollerTests {
    @Test
    void testGetOpenUpdatedPullRequests(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();

            var issue = issueProject.createIssue("This is update change issue", List.of(), Map.of());
            issue.setProperty("issuetype", JSON.of("Bug"));
            var localRepo = CheckableRepository.init(tempFolder.path(), repo.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            // create four pull requests.
            var openPr1 = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": " + issue.title());
            var openPr2 = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": " + issue.title());
            var closedPr1 = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": " + issue.title());
            closedPr1.setState(Issue.State.CLOSED);
            var closedPr2 = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": " + issue.title());
            closedPr2.setState(Issue.State.CLOSED);

            // First time, the poller should get all the open pull requests.
            var poller = new UpdatedPullRequestPoller(repo);
            var list = poller.getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests);
            assertEquals(2, list.size());
            assertEquals(1, list.stream().filter(pr -> pr.id().equals(openPr1.id())).count());
            assertEquals(1, list.stream().filter(pr -> pr.id().equals(openPr2.id())).count());

            // Update the open pr 1 and the closed pr 1.
            openPr1.addLabel("test");
            closedPr1.addLabel("test");

            // The poller should get the open pr 1.
            list = poller.getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests);
            assertEquals(1, list.size());
            assertEquals(1, list.stream().filter(pr -> pr.id().equals(openPr1.id())).count());

            // Update the closed pr 1 and closed pr 2
            closedPr1.addLabel("test2");
            closedPr2.addLabel("test2");

            // The poller shouldn't get any pr.
            list = poller.getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests);
            assertEquals(0, list.size());

            // Update the open pr 1 and open pr 2
            openPr1.addLabel("test2");
            openPr2.addLabel("test2");

            // The poller should get the open pr 1 and open pr 2.
            list = poller.getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests);
            assertEquals(2, list.size());
            assertEquals(1, list.stream().filter(pr -> pr.id().equals(openPr1.id())).count());
            assertEquals(1, list.stream().filter(pr -> pr.id().equals(openPr2.id())).count());

            // No pr updates.
            list = poller.getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests);
            assertEquals(0, list.size());
        }
    }

    @Test
    void testGetAllUpdatedPullRequests(TestInfo testInfo) throws IOException {
        // TODO currently the class `HostedRepository` doesn't have a method to get all the pull requests.
        //  Want the the class `HostedRepository` and its sub-classes to be adjusted.
    }
}
