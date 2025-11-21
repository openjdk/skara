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
package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.mailinglist.MailingListReader;
import org.openjdk.skara.mailinglist.MailingListServer;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHost;
import org.openjdk.skara.test.TestHostedRepository;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailingListBridgeBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "name": "test",
                      "mail": "test@openjdk.org",
                      "ignored": {
                        "users": [
                          "ignore1[bot]",
                          "ignore2[bot]",
                          "ignore3[bot]",
                          "ignore4[bot]"
                        ],
                        "comments": [
                          "<!-- It's a test comment!-->"
                        ]
                      },
                      "ready": {
                        "labels": [
                          "rfr"
                        ],
                        "comments": [
                          {
                            "user": "test_user[bot]",
                            "pattern": "<!-- Welcome message -->"
                          }
                        ]
                      },
                      "server": {
                        "archive": "https://mail.test.org/test/",
                        "smtp": "0.0.0.0",
                        "interval": "PT5S",
                        "etag": true,
                      },
                      "webrevs": {
                        "repository": {
                          "html": "repo1",
                          "json": "repo2"
                        },
                        "ref": "master",
                        "web": "https://test.openjdk.org/"
                      },
                      "archive": "archive:master",
                      "issues": "https://bugs.test.org/browse/",
                      "cooldown": "PT2M",
                      "repositories": [
                        {
                          "repository": "repo3",
                          "census": "census:master",
                          "webrevs": {
                            "html": false,
                            "json": true
                          },
                          "headers": {
                            "Approved": "test"
                          },
                          "lists": [
                            {
                              "email": "test_email1@test.org"
                            }
                          ],
                          "branchname":"dev"
                        },
                        {
                          "repository": "repo4",
                          "census": "census:master",
                          "webrevs": {
                            "html": false,
                            "json": true
                          },
                          "lists": {
                            "email": "test_email2@test.com"
                          },
                          "bidirectional": false,
                          "reponame": true
                        },
                        {
                          "repository": "repo5",
                          "census": "census:master",
                          "webrevs": {
                            "html": false,
                            "json": true
                          },
                          "headers": {
                            "Approved": "test5"
                          },
                          "reponame": true,
                          "branchname": "master",
                          "lists": [
                            {
                              "email": "test_email3@test.org",
                              "labels": [
                                "label1",
                                "label2",
                                "label3"
                              ]
                            }
                          ],
                          "issues": "https://test.test.com/issueProject"
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testHost = TestHost.createNew(List.of());
            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("repo1", new TestHostedRepository(testHost, "repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository(testHost, "repo2"))
                    .addHostedRepository("repo3", new TestHostedRepository(testHost, "repo3"))
                    .addHostedRepository("repo4", new TestHostedRepository(testHost, "repo4"))
                    .addHostedRepository("repo5", new TestHostedRepository(testHost, "repo5"))
                    .addHostedRepository("archive", new TestHostedRepository("archive"))
                    .addHostedRepository("census", new TestHostedRepository("census"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(MailingListBridgeBotFactory.NAME, jsonConfig);
            assertEquals(5, bots.size());

            //A mailingListArchiveReaderBot for every configured repository which is bidirectional
            List<Bot> mailingListArchiveReaderBots = bots.stream().filter(e -> e.getClass().equals(MailingListArchiveReaderBot.class)).toList();
            //A mailingListBridgeBot for every configured repository
            List<Bot> mailingListBridgeBots = bots.stream().filter(e -> e.getClass().equals(MailingListBridgeBot.class)).toList();

            assertEquals(2, mailingListArchiveReaderBots.size());
            assertEquals(3, mailingListBridgeBots.size());

            MailingListArchiveReaderBot mailingListArchiveReaderBot1 =
                    (MailingListArchiveReaderBot) mailingListArchiveReaderBots.get(0);
            assertEquals("MailingListArchiveReaderBot@repo3", mailingListArchiveReaderBot1.toString());
            MailingListReader readerBot1MailingListReader = mailingListArchiveReaderBot1.mailingListReader();
            assertTrue(readerBot1MailingListReader.getClass().getName().contains("Mailman2"),
                    readerBot1MailingListReader.getClass().getName());

            MailingListArchiveReaderBot mailingListArchiveReaderBot2 =
                    (MailingListArchiveReaderBot) mailingListArchiveReaderBots.get(1);
            assertEquals("MailingListArchiveReaderBot@repo5", mailingListArchiveReaderBot2.toString());
            MailingListReader readerBot2MailingListReader = mailingListArchiveReaderBot2.mailingListReader();
            assertTrue(readerBot2MailingListReader.getClass().getName().contains("Mailman2"),
                    readerBot2MailingListReader.getClass().getName());

            MailingListBridgeBot mailingListBridgeBot1 = (MailingListBridgeBot) mailingListBridgeBots.get(0);
            assertEquals("MailingListBridgeBot@repo3", mailingListBridgeBot1.toString());
            assertEquals("repo3", mailingListBridgeBot1.codeRepo().name());
            assertEquals("archive", mailingListBridgeBot1.archiveRepo().name());
            assertEquals("master", mailingListBridgeBot1.archiveRef());
            assertEquals("master", mailingListBridgeBot1.censusRef());
            assertEquals("census", mailingListBridgeBot1.censusRepo().name());
            assertEquals("<test_email1@test.org>", mailingListBridgeBot1.lists().get(0).list().toString());
            assertEquals("[ignore1[bot], ignore2[bot], ignore4[bot], ignore3[bot]]", mailingListBridgeBot1.ignoredUsers().toString());
            assertEquals("[<!-- It's a test comment!-->]", mailingListBridgeBot1.ignoredComments().toString());
            assertEquals("[rfr]", mailingListBridgeBot1.readyLabels().toString());
            assertEquals("{test_user[bot]=<!-- Welcome message -->}", mailingListBridgeBot1.readyComments().toString());
            assertEquals("{Approved=test}", mailingListBridgeBot1.headers().toString());
            assertEquals("https://bugs.test.org/browse/", mailingListBridgeBot1.issueTracker().toString());
            assertEquals(Duration.ofMinutes(2), mailingListBridgeBot1.cooldown());
            assertFalse(mailingListBridgeBot1.repoInSubject());
            assertEquals("dev", mailingListBridgeBot1.branchInSubject().toString());
            MailingListServer bridgeBot1MailingListServer = mailingListBridgeBot1.mailingListServer();
            assertTrue(bridgeBot1MailingListServer.getClass().getName().contains("Mailman2"));

            MailingListBridgeBot mailingListBridgeBot2 = (MailingListBridgeBot) mailingListBridgeBots.get(1);
            assertEquals("MailingListBridgeBot@repo4", mailingListBridgeBot2.toString());
            assertEquals("repo4", mailingListBridgeBot2.codeRepo().name());
            assertEquals("archive", mailingListBridgeBot2.archiveRepo().name());
            assertEquals("master", mailingListBridgeBot2.archiveRef());
            assertEquals("master", mailingListBridgeBot2.censusRef());
            assertEquals("census", mailingListBridgeBot2.censusRepo().name());
            assertEquals("<test_email2@test.com>", mailingListBridgeBot2.lists().get(0).list().toString());
            assertEquals("[ignore1[bot], ignore2[bot], ignore4[bot], ignore3[bot]]", mailingListBridgeBot2.ignoredUsers().toString());
            assertEquals("[<!-- It's a test comment!-->]", mailingListBridgeBot2.ignoredComments().toString());
            assertEquals("[rfr]", mailingListBridgeBot2.readyLabels().toString());
            assertEquals("{test_user[bot]=<!-- Welcome message -->}", mailingListBridgeBot2.readyComments().toString());
            assertEquals(0, mailingListBridgeBot2.headers().size());
            assertEquals("https://bugs.test.org/browse/", mailingListBridgeBot2.issueTracker().toString());
            assertEquals(Duration.ofMinutes(2), mailingListBridgeBot2.cooldown());
            assertTrue(mailingListBridgeBot2.repoInSubject());
            MailingListServer bridgeBot2MailingListServer = mailingListBridgeBot2.mailingListServer();
            assertTrue(bridgeBot2MailingListServer.getClass().getName().contains("Mailman2"));

            MailingListBridgeBot mailingListBridgeBot3 = (MailingListBridgeBot) mailingListBridgeBots.get(2);
            assertEquals("MailingListBridgeBot@repo5", mailingListBridgeBot3.toString());
            assertEquals("repo5", mailingListBridgeBot3.codeRepo().name());
            assertEquals("archive", mailingListBridgeBot3.archiveRepo().name());
            assertEquals("master", mailingListBridgeBot3.archiveRef());
            assertEquals("master", mailingListBridgeBot3.censusRef());
            assertEquals("census", mailingListBridgeBot3.censusRepo().name());
            assertEquals("<test_email3@test.org>", mailingListBridgeBot3.lists().get(0).list().toString());
            assertEquals("[label1, label2, label3]", mailingListBridgeBot3.lists().get(0).labels().toString());
            assertEquals("[ignore1[bot], ignore2[bot], ignore4[bot], ignore3[bot]]", mailingListBridgeBot3.ignoredUsers().toString());
            assertEquals("[<!-- It's a test comment!-->]", mailingListBridgeBot3.ignoredComments().toString());
            assertEquals("[rfr]", mailingListBridgeBot3.readyLabels().toString());
            assertEquals("{test_user[bot]=<!-- Welcome message -->}", mailingListBridgeBot3.readyComments().toString());
            assertEquals("{Approved=test5}", mailingListBridgeBot3.headers().toString());
            assertEquals("https://test.test.com/issueProject", mailingListBridgeBot3.issueTracker().toString());
            assertEquals(Duration.ofMinutes(2), mailingListBridgeBot3.cooldown());
            assertTrue(mailingListBridgeBot3.repoInSubject());
            assertEquals("master", mailingListBridgeBot3.branchInSubject().toString());
            MailingListServer bridgeBot3MailingListServer = mailingListBridgeBot3.mailingListServer();
            assertTrue(bridgeBot3MailingListServer.getClass().getName().contains("Mailman2"));
        }
    }
}
