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
package org.openjdk.skara.bots.testinfo;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestResultsTests {
    private static final ZonedDateTime baseStartedAt = ZonedDateTime.parse("2020-11-26T11:00:00+01:00", DateTimeFormatter.ISO_ZONED_DATE_TIME);

    private Set<String> checkAsString(List<Check> checks) {
        return checks.stream()
                     .map(check -> check.status() + "##" +
                             check.name().substring(19) + "##" +
                             check.title().orElse("") + "##" +
                             check.summary().orElse("") + "##" +
                             Duration.between(baseStartedAt, check.startedAt()).getSeconds() + "##" +
                             Duration.between(baseStartedAt, check.completedAt().orElse(baseStartedAt)).getSeconds())
                     .collect(Collectors.toSet());
    }

    @Test
    void simple() {
        var check = CheckBuilder.create("Test", Hash.zero())
                                .startedAt(baseStartedAt)
                                .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                .build();
        var summary = TestResults.summarize(List.of(check));
        assertEquals(Set.of("SUCCESS##Test - Build / test##1/1 passed##✔️ Test##0##10"), checkAsString(summary));
        assertTrue(TestResults.expiresIn(List.of(check)).isEmpty());
    }

    @Test
    void multiPlatform() {
        var check1 = CheckBuilder.create("Linux x64 (test)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Windows x64 (test)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals(Set.of("SUCCESS##Linux x64 - Build / test##1/1 passed##✔️ Linux x64 (test)##0##10",
                            "SUCCESS##Windows x64 - Build / test##1/1 passed##✔️ Windows x64 (test)##0##10"),
                     checkAsString(summary));
    }

    @Test
    void multiFlavor() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals(Set.of("SUCCESS##Linux x64 - Build##1/1 passed##✔️ Linux x64 (Build)##0##10",
                            "SUCCESS##Linux x64 - Test (tier1)##1/1 passed##✔️ Linux x64 (Test tier1)##0##10"),
                     checkAsString(summary));
    }

    @Test
    void multiEverything() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check3 = CheckBuilder.create("Windows x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check4 = CheckBuilder.create("Windows x64 (Test tier1)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals(Set.of("SUCCESS##Linux x64 - Build##1/1 passed##✔️ Linux x64 (Build)##0##10",
                            "SUCCESS##Windows x64 - Test (tier1)##1/1 passed##✔️ Windows x64 (Test tier1)##0##10",
                            "SUCCESS##Linux x64 - Test (tier1)##1/1 passed##✔️ Linux x64 (Test tier1)##0##10",
                            "SUCCESS##Windows x64 - Build##1/1 passed##✔️ Windows x64 (Build)##0##10"),
                     checkAsString(summary));
    }

    @Test
    void sparse() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check3 = CheckBuilder.create("Windows x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check4 = CheckBuilder.create("macOS x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals(Set.of("SUCCESS##Linux x64 - Build##1/1 passed##✔️ Linux x64 (Build)##0##10",
                            "SUCCESS##Linux x64 - Test (tier1)##1/1 passed##✔️ Linux x64 (Test tier1)##0##10",
                            "SUCCESS##macOS x64 - Build##1/1 passed##✔️ macOS x64 (Build)##0##10",
                            "SUCCESS##Windows x64 - Build##1/1 passed##✔️ Windows x64 (Build)##0##10"),
                     checkAsString(summary));
    }

    @Test
    void failure() {
        var check1 = CheckBuilder.create("Linux x64 (test)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Windows x64 (test)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(false, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .details(URI.create("www.example.com"))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals(Set.of("SUCCESS##Linux x64 - Build / test##1/1 passed##✔️ Linux x64 (test)##0##10",
                            "FAILURE##Windows x64 - Build / test##1/1 failed##❌ [Windows x64 (test)](www.example.com)##0##10"),
                     checkAsString(summary));
    }

    @Test
    void inProgress() {
        var check1 = CheckBuilder.create("Linux x64 (test)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Windows x64 (test)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2));
        assertEquals(Set.of("SUCCESS##Linux x64 - Build / test##1/1 passed##✔️ Linux x64 (test)##0##10",
                            "IN_PROGRESS##Windows x64 - Build / test##1/1 running##⏳ Windows x64 (test)##0##0"),
                     checkAsString(summary));
        assertTrue(TestResults.expiresIn(List.of(check1, check2)).isPresent());
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
    void ignoredAndCancelled() {
        var check1 = CheckBuilder.create("Prerequisites", Hash.zero())
                                 .complete(true)
                                 .build();
        var check2 = CheckBuilder.create("Post-process artifacts", Hash.zero())
                                 .build();
        var check3 = CheckBuilder.create("Linux x64", Hash.zero())
                                 .cancel()
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3));
        assertTrue(summary.isEmpty());
    }

    @Test
    void mixed() {
        var check1 = CheckBuilder.create("Linux x64 (Build)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (Test tier1)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check3 = CheckBuilder.create("Prerequisites", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var check4 = CheckBuilder.create("Post-process", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals(Set.of("SUCCESS##Linux x64 - Test (tier1)##1/1 passed##✔️ Linux x64 (Test tier1)##0##10",
                            "SUCCESS##Linux x64 - Build##1/1 passed##✔️ Linux x64 (Build)##0##10"), checkAsString(summary));
    }

    @Test
    void durations() {
        var check1 = CheckBuilder.create("Linux x64 (build release)", Hash.zero())
                                 .startedAt(baseStartedAt)
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(400)))
                                 .build();
        var check2 = CheckBuilder.create("Linux x64 (build debug)", Hash.zero())
                                 .startedAt(baseStartedAt.plus(Duration.ofSeconds(60)))
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(120)))
                                 .build();
        var check3 = CheckBuilder.create("Windows x64 (Build release)", Hash.zero())
                                 .startedAt(baseStartedAt.plus(Duration.ofSeconds(10)))
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(20)))
                                 .build();
        var check4 = CheckBuilder.create("Windows x64 (Build debug)", Hash.zero())
                                 .startedAt(baseStartedAt.plus(Duration.ofSeconds(15)))
                                 .complete(true, baseStartedAt.plus(Duration.ofSeconds(200)))
                                 .build();
        var summary = TestResults.summarize(List.of(check1, check2, check3, check4));
        assertEquals(Set.of("SUCCESS##Windows x64 - Build##2/2 passed##✔️ Windows x64 (Build release)\n" +
                                    "✔️ Windows x64 (Build debug)##10##200",
                            "SUCCESS##Linux x64 - Build##2/2 passed##✔️ Linux x64 (build release)\n" +
                                    "✔️ Linux x64 (build debug)##0##400"),
                     checkAsString(summary));
    }
}
