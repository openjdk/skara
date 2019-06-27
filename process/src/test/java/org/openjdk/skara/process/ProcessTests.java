/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.process;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTests {

    private final static String invalidDirectory = "/askldjfoiuycvbsdf8778";

    @BeforeAll
    static void setUp() {
        Logger log = Logger.getGlobal();
        log.setLevel(Level.FINER);
        log = Logger.getLogger("org.openjdk.skara.process");
        log.setLevel(Level.FINER);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINER);
        log.addHandler(handler);
    }


    @Test
    void reuseSetup() throws IOException {
        var tempFile = Files.createTempFile("reusesetup", "tmp");
        var setup = Process.capture("rm", tempFile.toString());

        // Ensure that the command was really executed twice
        try (var first = setup.execute()) {
            assertEquals(0, first.await().status());
        }
        try (var second = setup.execute()) {
            assertNotEquals(0, second.await().status());
        }
    }

    @Test
    void noOutput() {
        try (var p = Process.command("ls", "/").execute()) {
            var result = p.check();

            assertEquals(0, result.stdout().size());
            assertEquals(0, result.stderr().size());
        }
    }

    @Test
    void timeout() {
        try (var p = Process.capture("sleep", "10000")
                            .timeout(Duration.ofMillis(1))
                            .execute()) {
            var result = p.await();
            assertEquals(-1, result.status());
        }
    }

    @Test
    void workingDirectory() {
        try (var p = Process.capture("ls")
                            .workdir("/")
                            .execute()) {
            var result = p.await();
            assertEquals(0, result.status());
        }
        try (var p = Process.capture("ls")
                            .workdir(invalidDirectory)
                            .execute()) {
            var result = p.await();
            assertNotEquals(0, result.status());
        }
    }
}
