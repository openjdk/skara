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
package org.openjdk.skara.bots.mirror;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class MirrorBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "branches": "master"
                        },
                        {
                          "from": "from2",
                          "to": "to2",
                          "branches": [
                            "master",
                            "dev",
                            "test"
                          ]
                        },
                        {
                          "from": "from3",
                          "to": "to3"
                        },
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("from2", new TestHostedRepository("from2"))
                    .addHostedRepository("from3", new TestHostedRepository("from3"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .addHostedRepository("to2", new TestHostedRepository("to2"))
                    .addHostedRepository("to3", new TestHostedRepository("to3"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig);
            assertEquals(3, bots.size());

            MirrorBot mirrorBot1 = (MirrorBot) bots.get(0);
            assertEquals("MirrorBot@from1->to1 (master) [tags excluded]", mirrorBot1.toString());
            assertFalse(mirrorBot1.isIncludeTags());
            assertFalse(mirrorBot1.isOnlyTags());
            assertEquals("master", mirrorBot1.getBranchPatterns().get(0).toString());

            MirrorBot mirrorBot2 = (MirrorBot) bots.get(1);
            assertEquals("MirrorBot@from2->to2 (master,dev,test) [tags excluded]", mirrorBot2.toString());
            assertFalse(mirrorBot2.isIncludeTags());
            assertFalse(mirrorBot2.isOnlyTags());
            assertEquals("master", mirrorBot2.getBranchPatterns().get(0).toString());
            assertEquals("dev", mirrorBot2.getBranchPatterns().get(1).toString());
            assertEquals("test", mirrorBot2.getBranchPatterns().get(2).toString());

            MirrorBot mirrorBot3 = (MirrorBot) bots.get(2);
            assertEquals("MirrorBot@from3->to3 (*) [tags included]", mirrorBot3.toString());
            assertTrue(mirrorBot3.isIncludeTags());
            assertFalse(mirrorBot3.isOnlyTags());
            assertEquals(0, mirrorBot3.getBranchPatterns().size());
        }
    }

    @Test
    public void testThrowsWithUnsupportedTagsValue() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "branches": "master",
                          "tags": "foo"
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            assertThrows(IllegalStateException.class, () -> testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig));
        }
    }

    @Test
    public void testThrowsWithBranchesAndTagsOnly() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "branches": "master",
                          "tags": "only"
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            assertThrows(IllegalStateException.class, () -> testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig));
        }
    }

    @Test
    public void testCreateWithTags() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "branches": "master"
                        },
                        {
                          "from": "from2",
                          "to": "to2",
                          "tags": "include"
                        },
                        {
                          "from": "from3",
                          "to": "to3",
                        },
                        {
                          "from": "from4",
                          "to": "to4",
                          "tags": "only"
                        },
                        {
                          "from": "from5",
                          "to": "to5",
                          "branches": ["master", "dev"]
                        },
                        {
                          "from": "from6",
                          "to": "to6",
                          "branches": ["master", "dev"],
                          "tags": "include"
                        },
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("from2", new TestHostedRepository("from2"))
                    .addHostedRepository("from3", new TestHostedRepository("from3"))
                    .addHostedRepository("from4", new TestHostedRepository("from4"))
                    .addHostedRepository("from5", new TestHostedRepository("from5"))
                    .addHostedRepository("from6", new TestHostedRepository("from6"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .addHostedRepository("to2", new TestHostedRepository("to2"))
                    .addHostedRepository("to3", new TestHostedRepository("to3"))
                    .addHostedRepository("to4", new TestHostedRepository("to4"))
                    .addHostedRepository("to5", new TestHostedRepository("to5"))
                    .addHostedRepository("to6", new TestHostedRepository("to6"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig);
            assertEquals(6, bots.size());

            MirrorBot mirrorBot1 = (MirrorBot) bots.get(0);
            assertEquals("MirrorBot@from1->to1 (master) [tags excluded]", mirrorBot1.toString());
            assertFalse(mirrorBot1.isIncludeTags());
            assertFalse(mirrorBot1.isOnlyTags());
            assertEquals(List.of("master"),
                         mirrorBot1.getBranchPatterns().stream().map(Pattern::toString).toList());

            MirrorBot mirrorBot2 = (MirrorBot) bots.get(1);
            assertEquals("MirrorBot@from2->to2 (*) [tags included]", mirrorBot2.toString());
            assertTrue(mirrorBot2.isIncludeTags());
            assertFalse(mirrorBot2.isOnlyTags());
            assertEquals(List.of(), mirrorBot2.getBranchPatterns());

            MirrorBot mirrorBot3 = (MirrorBot) bots.get(2);
            assertEquals("MirrorBot@from3->to3 (*) [tags included]", mirrorBot3.toString());
            assertTrue(mirrorBot3.isIncludeTags());
            assertFalse(mirrorBot3.isOnlyTags());
            assertEquals(List.of(), mirrorBot3.getBranchPatterns());

            MirrorBot mirrorBot4 = (MirrorBot) bots.get(3);
            assertEquals("MirrorBot@from4->to4 () [tags only]", mirrorBot4.toString());
            assertTrue(mirrorBot4.isIncludeTags());
            assertTrue(mirrorBot4.isOnlyTags());
            assertEquals(List.of(), mirrorBot4.getBranchPatterns());

            MirrorBot mirrorBot5 = (MirrorBot) bots.get(4);
            assertEquals("MirrorBot@from5->to5 (master,dev) [tags excluded]", mirrorBot5.toString());
            assertFalse(mirrorBot5.isIncludeTags());
            assertFalse(mirrorBot5.isOnlyTags());
            assertEquals(List.of("master", "dev"),
                         mirrorBot5.getBranchPatterns().stream().map(Pattern::toString).toList());

            MirrorBot mirrorBot6 = (MirrorBot) bots.get(5);
            assertEquals("MirrorBot@from6->to6 (master,dev) [tags included]", mirrorBot6.toString());
            assertTrue(mirrorBot6.isIncludeTags());
            assertFalse(mirrorBot6.isOnlyTags());
            assertEquals(List.of("master", "dev"),
                         mirrorBot6.getBranchPatterns().stream().map(Pattern::toString).toList());
        }
    }

    @Test
    public void testThrowsWithRefspecsAndTags() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "refspecs": "refs/foo",
                          "tags": "only"
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            assertThrows(IllegalStateException.class, () -> testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig));
        }
    }

    @Test
    public void testThrowsWithRefspecsAndBranches() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "refspecs": "refs/foo",
                          "branches": "master"
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            assertThrows(IllegalStateException.class, () -> testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig));
        }
    }

    @Test
    public void testCreateWithRefspecs() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "refspecs": "refs/foo",
                        },
                        {
                          "from": "from2",
                          "to": "to2",
                          "refspecs": [
                            "refs/foo",
                            "refs/bar"
                          ]
                        }
                      ]
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

            var bots = testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig);
            assertEquals(2, bots.size());

            MirrorBot mirrorBot1 = (MirrorBot) bots.get(0);
            assertEquals("MirrorBot@from1->to1 (refs/foo)", mirrorBot1.toString());
            assertFalse(mirrorBot1.isIncludeTags());
            assertFalse(mirrorBot1.isOnlyTags());
            assertEquals(List.of(), mirrorBot1.getBranchPatterns());
            assertEquals(List.of("refs/foo"), mirrorBot1.getRefspecs());

            MirrorBot mirrorBot2 = (MirrorBot) bots.get(1);
            assertEquals("MirrorBot@from2->to2 (refs/foo,refs/bar)", mirrorBot2.toString());
            assertFalse(mirrorBot2.isIncludeTags());
            assertFalse(mirrorBot2.isOnlyTags());
            assertEquals(List.of(), mirrorBot2.getBranchPatterns());
            assertEquals(List.of("refs/foo", "refs/bar"), mirrorBot2.getRefspecs());
        }
    }
}
