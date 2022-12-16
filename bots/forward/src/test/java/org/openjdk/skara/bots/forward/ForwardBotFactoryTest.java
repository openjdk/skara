/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.forward;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import java.util.Comparator;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ForwardBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": {
                        "repo1": {
                          "from": "from1:master",
                          "to": "to1:master"
                        },
                        "repo2": {
                          "from": "from2:dev",
                          "to": "to2:test"
                        }
                      }
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("from2", new TestHostedRepository("from2"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .addHostedRepository("to2", new TestHostedRepository("to2"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(ForwardBotFactory.NAME, jsonConfig);
            bots = bots.stream().sorted(Comparator.comparing(Objects::toString)).toList();
            //A forwardBot for every configured repo
            assertEquals(2, bots.size());

            assertEquals("ForwardBot@(from1:master-> to1:master)", bots.get(0).toString());
            assertEquals("ForwardBot@(from2:dev-> to2:test)", bots.get(1).toString());
        }
    }
}