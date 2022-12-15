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
package org.openjdk.skara.bots.testinfo;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHost;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class TestInfoBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "repositories": [
                    "repo1",
                    "repo2"
                  ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testHost = TestHost.createNew(List.of());
        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository(testHost, "repo1"))
                .addHostedRepository("repo2", new TestHostedRepository(testHost, "repo2"))
                .build();

        var bots = testBotFactory.createBots(TestInfoBotFactory.NAME, jsonConfig);
        // A testInfoBot for every configured repo
        assertEquals(2, bots.size());

        assertEquals("TestInfoBot@repo1", bots.get(0).toString());
        assertEquals("TestInfoBot@repo2", bots.get(1).toString());
    }
}