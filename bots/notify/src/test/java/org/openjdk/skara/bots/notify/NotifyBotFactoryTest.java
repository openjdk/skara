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
package org.openjdk.skara.bots.notify;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.*;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class NotifyBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "database": {
                        "repository": "notify:master",
                        "name": "test_notify",
                        "email": "test_notify@openjdk.org"
                      },
                      "ready": {
                        "labels": [
                          "rfr"
                        ],
                        "comments": [
                          {
                            "user": "test[bot]",
                            "pattern": "<!-- Welcome message -->"
                          }
                        ]
                      },
                      "integrator": "111",
                      "mailinglist": {
                        "archive": "https://test.openjdk.org/archive",
                        "smtp": "0.0.0.0",
                        "sender": "test <test@openjdk.org>",
                        "interval": "PT5S"
                      },
                      "issue": {
                        "reviews": {
                          "icon": "icon.png"
                        },
                        "commits": {
                          "icon": "commit.png"
                        },
                        "namespace": "test.org"
                      },
                      "repositories": {
                        "repo1": {
                          "basename": "test-repo",
                          "branches": "master",
                          "mailinglist": {
                            "recipient": "test@test.org",
                            "domains": "test.org|test.com",
                            "headers": {
                              "Approved": "0000000"
                            },
                            "branchnames": false,
                            "branches": false,
                            "tags": true,
                            "builds": false
                          },
                          "issue": {
                            "project": "test_bugs/TEST",
                            "pronly": true,
                            "resolve": false
                          },
                          "comment": {
                            "project": "test_bugs/TEST"
                          },
                          "prbranch": {
                          },
                          "notes": {
                            "project": "test_bugs/TEST"
                          }
                        },
                        "repo2": {
                          "basename": "test-repo2",
                          "branches": "dev",
                          "mailinglist": {
                            "recipient": "test@test.org",
                            "domains": "test.org|test.com",
                            "headers": {
                              "Approved": "0000000"
                            },
                            "branchnames": false,
                            "branches": false,
                            "tags": true,
                            "builds": false
                          },
                          "issue": {
                            "project": "test_bugs/TEST",
                            "pronly": true,
                            "resolve": false,
                            "multifixversions": true,
                          },
                          "comment": {
                            "project": "test_bugs/TEST"
                          },
                          "prbranch": {
                          },
                          "notes": {
                            "project": "test_bugs/TEST"
                          }
                        }
                      }
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testHost = TestHost.createNew(List.of());
            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("notify", new TestHostedRepository("notify"))
                    .addHostedRepository("repo1", new TestHostedRepository(testHost, "repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository(testHost, "repo2"))
                    .addIssueProject("test_bugs/TEST", new TestIssueProject(testHost, "TEST"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(NotifyBotFactory.NAME, jsonConfig);
            bots = bots.stream().sorted(Comparator.comparing(Objects::toString)).toList();
            //A notifyBot for every configured repository
            assertEquals(2, bots.size());

            NotifyBot notifyBot1 = (NotifyBot) bots.get(0);
            assertEquals("NotifyBot@repo1", notifyBot1.toString());
            assertEquals("master", notifyBot1.getBranches().toString());
            assertEquals("{test[bot]=<!-- Welcome message -->}", notifyBot1.getReadyComments().toString());

            NotifyBot notifyBot2 = (NotifyBot) bots.get(1);
            assertEquals("NotifyBot@repo2", notifyBot2.toString());
            assertEquals("dev", notifyBot2.getBranches().toString());
            assertEquals("{test[bot]=<!-- Welcome message -->}", notifyBot2.getReadyComments().toString());
        }
    }
}
