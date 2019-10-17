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
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.vcs.Hash;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ShellExecutorTests {
    @Test
    void shellEnvironmentSet() throws IOException {
        try (var tempFolder = new TemporaryDirectory()) {
            var executor = new ShellExecutor("test", List.of("bash", "-c", "test $hello"), Duration.ofDays(1),
                                             Map.of("hello", "1"));
            var checkBuilder = CheckBuilder.create("test", new Hash("abcd"));
            executor.run(tempFolder.path(), checkBuilder, () -> {});
            var result = checkBuilder.build();
            assertEquals(CheckStatus.SUCCESS, result.status());
        }
    }

    @Test
    void shellEnvironmentUnset() throws IOException {
        try (var tempFolder = new TemporaryDirectory()) {
            var executor = new ShellExecutor("test", List.of("bash", "-c", "test $hello"), Duration.ofDays(1),
                                             Map.of());
            var checkBuilder = CheckBuilder.create("test", new Hash("abcd"));
            executor.run(tempFolder.path(), checkBuilder, () -> {});
            var result = checkBuilder.build();
            assertEquals(CheckStatus.FAILURE, result.status());
        }
    }

    @Test
    void unprintable() throws IOException {
        try (var tempFolder = new TemporaryDirectory()) {
            var executor = new ShellExecutor("test", List.of("echo", "Grüße\tr"), Duration.ofDays(1),
                                             Map.of());
            var checkBuilder = CheckBuilder.create("test", new Hash("abcd"));
            var updates = new ArrayList<String>();
            executor.run(tempFolder.path(), checkBuilder, () -> { checkBuilder.build().summary().ifPresent(updates::add); });
            var result = checkBuilder.build();
            assertEquals(CheckStatus.SUCCESS, result.status());
            assertEquals(1, updates.size());
            assertTrue(updates.get(0).contains("Grer"));
        }
    }
}
