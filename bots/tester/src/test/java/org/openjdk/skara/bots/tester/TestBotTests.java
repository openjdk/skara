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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.test.*;

import java.io.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import static org.junit.jupiter.api.Assertions.*;

class TestBotTests {
    @Test
    void noTestCommentShouldDoNothing(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var upstreamHostedRepo = credentials.getHostedRepository();
            var personalHostedRepo = credentials.getHostedRepository();
            var pr = personalHostedRepo.createPullRequest(upstreamHostedRepo,
                                                          "master",
                                                          "master",
                                                          "Title",
                                                          List.of("body"));

            var comments = pr.comments();
            assertEquals(0, comments.size());

            var storage = tmp.path().resolve("storage");
            var ci = new InMemoryContinuousIntegration();
            var bot = new TestBot(ci, "0", List.of(), List.of(), "", storage, upstreamHostedRepo);
            var runner = new TestBotRunner();

            runner.runPeriodicItems(bot);

            comments = pr.comments();
            assertEquals(0, comments.size());
        }
    }
}
