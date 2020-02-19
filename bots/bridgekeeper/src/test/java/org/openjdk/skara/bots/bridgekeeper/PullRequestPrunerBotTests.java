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

import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestPrunerBotTests {
    @Test
    void close(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = new PullRequestPrunerBot(Map.of(author, Duration.ofMillis(1)));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Make sure the timeout expires
            Thread.sleep(100);

            // Let the bot see it - it should give a notice
            TestBotRunner.runPeriodicItems(bot);

            assertEquals(1, pr.comments().size());
            assertTrue(pr.comments().get(0).body().contains("will be automatically closed if"));

            pr.addComment("I'm still working on it!");

            // Make sure the timeout expires again
            Thread.sleep(100);

            // Let the bot see it - it should post a second notice
            TestBotRunner.runPeriodicItems(bot);

            assertEquals(3, pr.comments().size());
            assertTrue(pr.comments().get(2).body().contains("will be automatically closed if"));

            // Make sure the timeout expires again
            Thread.sleep(100);

            // The bot should now close it
            TestBotRunner.runPeriodicItems(bot);

            // There should now be no open PRs
            var prs = author.pullRequests();
            assertEquals(0, prs.size());
        }
    }

    @Test
    void dontClose(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var bot = new PullRequestPrunerBot(Map.of(author, Duration.ofDays(3)));

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

            // There should still be an open PR
            var prs = author.pullRequests();
            assertEquals(1, prs.size());
        }
    }
}
