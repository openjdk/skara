/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
 package org.openjdk.skara.bots.testinfo;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestInfoTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var checkBot = new TestInfoBot(author);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a draft PR where we can add some checks
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "preedit", true);
            var draftPr = credentials.createPullRequest(author, "master", "preedit", "This is a pull request", true);
            var check1 = CheckBuilder.create("ps1", editHash).title("PS1");
            draftPr.createCheck(check1.build());
            draftPr.updateCheck(check1.complete(true).build());
            var check2 = CheckBuilder.create("ps2", editHash).title("PS2");
            draftPr.createCheck(check2.build());
            draftPr.updateCheck(check2.complete(false).build());
            var check3 = CheckBuilder.create("ps3", editHash).title("PS3");
            draftPr.createCheck(check3.build());
            draftPr.updateCheck(check3.details(URI.create("https://www.example.com")).complete(false).build());
            var check4 = CheckBuilder.create("ps4", editHash).title("PS4");
            draftPr.createCheck(check4.build());
            draftPr.updateCheck(check4.details(URI.create("https://www.example.com")).build());

            // Now make an actual PR
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify summarized checks
            assertEquals(4, pr.checks(editHash).size());
            assertEquals("1/1 passed", pr.checks(editHash).get("Pre-submit tests - ps1 - Build / test").title().orElseThrow());
            assertEquals("✔️ ps1", pr.checks(editHash).get("Pre-submit tests - ps1 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.SUCCESS, pr.checks(editHash).get("Pre-submit tests - ps1 - Build / test").status());
            assertEquals("1/1 failed", pr.checks(editHash).get("Pre-submit tests - ps2 - Build / test").title().orElseThrow());
            assertEquals("❌ ps2", pr.checks(editHash).get("Pre-submit tests - ps2 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.FAILURE, pr.checks(editHash).get("Pre-submit tests - ps2 - Build / test").status());
            assertEquals("1/1 failed", pr.checks(editHash).get("Pre-submit tests - ps3 - Build / test").title().orElseThrow());
            assertEquals("❌ [ps3](https://www.example.com)", pr.checks(editHash).get("Pre-submit tests - ps3 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.FAILURE, pr.checks(editHash).get("Pre-submit tests - ps3 - Build / test").status());
            assertEquals("1/1 running", pr.checks(editHash).get("Pre-submit tests - ps4 - Build / test").title().orElseThrow());
            assertEquals("⏳ [ps4](https://www.example.com)", pr.checks(editHash).get("Pre-submit tests - ps4 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.IN_PROGRESS, pr.checks(editHash).get("Pre-submit tests - ps4 - Build / test").status());
        }
    }

    @Test
    void update(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var checkBot = new TestInfoBot(author);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a draft PR where we can add some checks
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "preedit", true);
            var draftPr1 = credentials.createPullRequest(author, "master", "preedit", "This is a pull request", true);
            var check1 = CheckBuilder.create("ps1", editHash).title("PS1");
            draftPr1.createCheck(check1.build());
            draftPr1.updateCheck(check1.complete(true).build());

            // Now make an actual PR
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify summarized checks
            assertEquals(1, pr.checks(pr.headHash()).size());
            assertEquals("1/1 passed", pr.checks(pr.headHash()).get("Pre-submit tests - ps1 - Build / test").title().orElseThrow());

            // And a second one
            var editHash2 = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash2, author.url(), "preedit");
            var draftPr2 = credentials.createPullRequest(author, "master", "preedit", "This is a pull request", true);
            var check2 = CheckBuilder.create("ps2", editHash2).title("PS2");
            draftPr2.createCheck(check2.build());
            draftPr2.updateCheck(check2.complete(false).build());

            // Push an update to the PR
            localRepo.push(editHash2, author.url(), "edit", true);

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify summarized checks again
            var updatedPr = pr.repository().pullRequest(pr.id());
            assertEquals(1, updatedPr.checks(updatedPr.headHash()).size());
            assertEquals("1/1 failed", updatedPr.checks(updatedPr.headHash()).get("Pre-submit tests - ps2 - Build / test").title().orElseThrow());
        }
    }
}
