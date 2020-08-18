/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.bridgekeeper;

import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.issuetracker.Issue.State.*;

class PullRequestCloserBotTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = new PullRequestCloserBot(author, PullRequestCloserBot.Type.MIRROR);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");
            assertEquals(OPEN, pr.state());

            // Let the bot see it
            TestBotRunner.runPeriodicItems(bot);

            // There should now be no open PRs
            var prs = author.pullRequests();
            assertEquals(0, prs.size());

            var updatedPr = author.pullRequest(pr.id());
            assertEquals(CLOSED, updatedPr.state());
        }
    }

    @Test
    void keepClosing(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = new PullRequestCloserBot(author, PullRequestCloserBot.Type.MIRROR);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(bot);

            // There should now be no open PRs
            var prs = author.pullRequests();
            assertEquals(0, prs.size());

            // The author is persistent
            pr.setState(Issue.State.OPEN);
            prs = author.pullRequests();
            assertEquals(1, prs.size());

            // But so is the bot
            TestBotRunner.runPeriodicItems(bot);
            prs = author.pullRequests();
            assertEquals(0, prs.size());

            // There should still only be one welcome comment
            assertEquals(1, pr.comments().size());

            // The message should mention mirroring
            assertTrue(pr.comments().get(0).body().contains("This repository is currently a read-only git mirror"));
        }
    }

    @Test
    void dataMessage(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = new PullRequestCloserBot(author, PullRequestCloserBot.Type.DATA);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Let the bot see it
            TestBotRunner.runPeriodicItems(bot);

            // There should now be no open PRs
            var prs = author.pullRequests();
            assertEquals(0, prs.size());

            // The author is persistent
            pr.setState(Issue.State.OPEN);
            prs = author.pullRequests();
            assertEquals(1, prs.size());

            // But so is the bot
            TestBotRunner.runPeriodicItems(bot);
            prs = author.pullRequests();
            assertEquals(0, prs.size());

            // There should still only be one welcome comment
            assertEquals(1, pr.comments().size());

            // The message should mention automatically generated data
            assertTrue(pr.comments().get(0).body().contains("This repository currently holds only automatically generated data"));
        }
    }
}
