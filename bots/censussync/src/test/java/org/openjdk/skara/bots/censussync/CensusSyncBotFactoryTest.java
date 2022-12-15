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
package org.openjdk.skara.bots.censussync;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class CensusSyncBotFactoryTest {
    @Test
    void testCreate() {
        String jsonString = """
                {
                    "sync": [
                      {
                        "method": "unify",
                        "from": "from1",
                        "to": "to1",
                        "version": 1
                      },
                      {
                        "method": "split",
                        "from": "https://test.org/test.xml",
                        "to": "to2",
                        "version": 2
                      }
                    ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("from1", new TestHostedRepository("from1"))
                .addHostedRepository("to1", new TestHostedRepository("to1"))
                .addHostedRepository("to2", new TestHostedRepository("to2"))
                .build();

        var bots = testBotFactory.createBots(CensusSyncBotFactory.NAME, jsonConfig);
        assertEquals(2, bots.size());

        var censusSyncUnifyBots = bots.stream().filter(e -> e.getClass().equals(CensusSyncUnifyBot.class)).toList();
        var censusSyncSplitBots = bots.stream().filter(e -> e.getClass().equals(CensusSyncSplitBot.class)).toList();

        assertEquals(1, censusSyncUnifyBots.size());
        assertEquals(1, censusSyncSplitBots.size());

        assertEquals("CensusSyncUnifyBot(from1->to1@1)", censusSyncUnifyBots.get(0).toString());
        assertEquals("CensusSyncSplitBot(https://test.org/test.xml->to2@2)", censusSyncSplitBots.get(0).toString());
    }
}