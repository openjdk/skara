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
package org.openjdk.skara.bots.jep;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;
import org.openjdk.skara.test.TestIssueProject;

import static org.junit.jupiter.api.Assertions.*;

class JEPBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "projects": [
                    {
                      "repository": "repo1",
                      "issues": "test_bugs/TEST"
                    },
                    {
                      "repository": "repo2",
                      "issues": "test_bugs/TEST"
                    }
                  ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                .addIssueProject("test_bugs/TEST", new TestIssueProject(null, "TEST"))
                .build();

        var bots = testBotFactory.createBots(JEPBotFactory.NAME, jsonConfig);
        // A JEPBot for every configured project
        assertEquals(2, bots.size());

        JEPBot jepBot1 = (JEPBot) bots.get(0);
        assertEquals("JEPBot@repo1", jepBot1.toString());
        assertEquals("TEST", jepBot1.getIssueProject().name());

        JEPBot jepBot2 = (JEPBot) bots.get(1);
        assertEquals("JEPBot@repo2", jepBot2.toString());
        assertEquals("TEST", jepBot2.getIssueProject().name());
    }
}