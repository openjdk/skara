/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.csr;

import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;
import org.openjdk.skara.json.JSON;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CSRBotTests {
    @Test
    void removeLabelForApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution",
                                                                                      JSON.object().put("name", "Approved")));
            csr.setState(Issue.State.CLOSED);
            issue.addLink(Link.create(csr, "csr for").build());

            var bot = new CSRBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have removed the CSR label
            assertFalse(pr.labels().contains("csr"));
        }
    }

    @Test
    void keepLabelForNoIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var bot = new CSRBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", "This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.labels().contains("csr"));
        }
    }

    @Test
    void keepLabelForNoJBS(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var bot = new CSRBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", "123: This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have kept the CSR label
            assertTrue(pr.labels().contains("csr"));
        }
    }

    @Test
    void keepLabelForNotApprovedCSR(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var issue = issues.createIssue("This is an issue", List.of(), Map.of());

            var csr = issues.createIssue("This is an approved CSR", List.of(), Map.of("resolution",
                                                                                      JSON.object().put("name", "Unresolved")));
            csr.setState(Issue.State.OPEN);
            issue.addLink(Link.create(csr, "csr for").build());

            var bot = new CSRBot(repo, issues);

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, repo.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", issue.id() + ": This is an issue");

            // Add CSR label
            pr.addLabel("csr");

            // Run bot
            TestBotRunner.runPeriodicItems(bot);

            // The bot should have removed the CSR label
            assertTrue(pr.labels().contains("csr"));
        }
    }
}
