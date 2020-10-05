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
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestResultsTests {
    @Test
    void simple() {
        var check = CheckBuilder.create("Test", Hash.zero())
                                .complete(true)
                                .build();
        var summary = TestResults.summarize(List.of(check));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Test |\n" +
                             "| --- | ----- |\n" +
                             "| Build / test | ✔️ (1/1 passed) |", summary.get().strip());
    }

    @Test
    void multiPlatform() {
        var check1 = CheckBuilder.create("Linux x64 (test)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Windows x64 (test)", Hash.zero())
                                 .complete(true)
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 | Windows x64 |\n" +
                             "| --- | ----- | ----- |\n" +
                             "| Build / test | ✔️ (1/1 passed) | ✔️ (1/1 passed) |", summary.get().strip());
    }

    @Test
    void multiFlavor() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .complete(true)
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 |\n" +
                             "| --- | ----- |\n" +
                             "| Build | ✔️ (1/1 passed) |\n" +
                             "| Test (tier1) | ✔️ (1/1 passed) |", summary.get().strip());
    }

    @Test
    void multiEverything() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check3 = CheckBuilder.create("Windows x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check4 = CheckBuilder.create("Windows x64 (Test tier1)", Hash.zero())
                                 .complete(true)
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 | Windows x64 |\n" +
                             "| --- | ----- | ----- |\n" +
                             "| Build | ✔️ (1/1 passed) | ✔️ (1/1 passed) |\n" +
                             "| Test (tier1) | ✔️ (1/1 passed) | ✔️ (1/1 passed) |", summary.get().strip());
    }

    @Test
    void sparse() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check3 = CheckBuilder.create("Windows x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check4 = CheckBuilder.create("macOS x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 | Windows x64 | macOS x64 |\n" +
                             "| --- | ----- | ----- | ----- |\n" +
                             "| Build | ✔️ (1/1 passed) | ✔️ (1/1 passed) | ✔️ (1/1 passed) |\n" +
                             "| Test (tier1) | ✔️ (1/1 passed) |    |     |", summary.get().strip());
    }

    @Test
    void failure() {
        var check1 = CheckBuilder.create("Linux x64 (test)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Windows x64 (test)", Hash.zero())
                                 .complete(false)
                                 .details(URI.create("www.example.com"))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 | Windows x64 |\n" +
                             "| --- | ----- | ----- |\n" +
                             "| Build / test | ✔️ (1/1 passed) | ❌ (1/1 failed) |\n" +
                             "\n" +
                             "**Failed test task**\n" +
                             "- [Windows x64 (test)](www.example.com)", summary.get().strip());
    }

    @Test
    void inProgress() {
        var check1 = CheckBuilder.create("Linux x64 (test)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Windows x64 (test)", Hash.zero())
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 | Windows x64 |\n" +
                             "| --- | ----- | ----- |\n" +
                             "| Build / test | ✔️ (1/1 passed) | ⏳ (1/1 in progress) |", summary.get().strip());
    }

    @Test
    void ignored() {
        var check1 = CheckBuilder.create("jcheck", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Prerequisites", Hash.zero())
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertTrue(summary.isEmpty());
    }

    @Test
    void mixed() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .complete(true)
                                 .build();
        var check3 = CheckBuilder.create("Prerequisites", Hash.zero())
                                 .complete(true)
                                 .build();
        var check4 = CheckBuilder.create("Post-process", Hash.zero())
                                 .complete(true)
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals("### Successful test tasks\n" +
                             "\n" +
                             "|     | Linux x64 |\n" +
                             "| --- | ----- |\n" +
                             "| Build | ✔️ (1/1 passed) |\n" +
                             "| Test (tier1) | ✔️ (1/1 passed) |", summary.get().strip());
    }
}
