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
package org.openjdk.skara.bots.submit;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckUpdaterTests {
    @Test
    void rateLimit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            var builder = CheckBuilder.create("test", editHash);
            pr.createCheck(builder.build());

            var updater = new CheckUpdater(pr, builder);
            updater.setMaxUpdateRate(Duration.ofDays(1));
            builder.summary("In progress");
            updater.run();

            // Verify that the check is in progress
            var checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            var check = checks.get("test");
            assertEquals(CheckStatus.IN_PROGRESS, check.status());
            assertEquals("In progress", check.summary().orElseThrow());

            builder.summary("Quick update");
            updater.run();

            // Verify that the check still is in progress and has not been updated due to the rate limiter
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("test");
            assertEquals(CheckStatus.IN_PROGRESS, check.status());
            assertEquals("In progress", check.summary().orElseThrow());

            // Now lower the limit
            updater.setMaxUpdateRate(Duration.ZERO);

            builder.summary("Final update");
            updater.run();

            // The summary should now have been updated
            checks = pr.checks(editHash);
            assertEquals(1, checks.size());
            check = checks.get("test");
            assertEquals(CheckStatus.IN_PROGRESS, check.status());
            assertEquals("Final update", check.summary().orElseThrow());
        }
    }
}
