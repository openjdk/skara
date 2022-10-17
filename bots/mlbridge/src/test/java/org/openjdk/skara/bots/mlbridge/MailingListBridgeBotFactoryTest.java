package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
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
                        "interval": "PT5S"
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
                          ]
                        }
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                    .addHostedRepository("repo3", new TestHostedRepository("repo3"))
                    .addHostedRepository("repo4", new TestHostedRepository("repo4"))
                    .addHostedRepository("repo5", new TestHostedRepository("repo5"))
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

            assertEquals("MailingListArchiveReaderBot@repo3", mailingListArchiveReaderBots.get(0).toString());
            assertEquals("MailingListArchiveReaderBot@repo5", mailingListArchiveReaderBots.get(1).toString());

            MailingListBridgeBot mailingListBridgeBot1 = (MailingListBridgeBot) mailingListBridgeBots.get(0);
            assertEquals("MailingListBridgeBot@repo3", mailingListBridgeBot1.toString());
            assertEquals("repo3", mailingListBridgeBot1.codeRepo().name());
            assertEquals("archive", mailingListBridgeBot1.archiveRepo().name());
            assertEquals("master", mailingListBridgeBot1.archiveRef());
            assertEquals("https://mail.test.org/test/", mailingListBridgeBot1.listArchive().toString());
            assertEquals("master", mailingListBridgeBot1.censusRef());
            assertEquals("census", mailingListBridgeBot1.censusRepo().name());
            assertEquals("<test_email1@test.org>", mailingListBridgeBot1.lists().get(0).list().toString());
            assertEquals("[ignore1[bot], ignore2[bot], ignore4[bot], ignore3[bot]]", mailingListBridgeBot1.ignoredUsers().toString());
            assertEquals("[<!-- It's a test comment!-->]", mailingListBridgeBot1.ignoredComments().toString());
            assertEquals("0.0.0.0", mailingListBridgeBot1.smtpServer());
            assertEquals("[rfr]", mailingListBridgeBot1.readyLabels().toString());
            assertEquals("{test_user[bot]=<!-- Welcome message -->}", mailingListBridgeBot1.readyComments().toString());
            assertEquals("{Approved=test}", mailingListBridgeBot1.headers().toString());
            assertEquals("https://bugs.test.org/browse/", mailingListBridgeBot1.issueTracker().toString());
            assertEquals(Duration.ofSeconds(5), mailingListBridgeBot1.sendInterval());
            assertEquals(Duration.ofMinutes(2), mailingListBridgeBot1.cooldown());
            assertFalse(mailingListBridgeBot1.repoInSubject());
            assertEquals("dev", mailingListBridgeBot1.branchInSubject().toString());


            MailingListBridgeBot mailingListBridgeBot2 = (MailingListBridgeBot) mailingListBridgeBots.get(1);
            assertEquals("MailingListBridgeBot@repo4", mailingListBridgeBot2.toString());
            assertEquals("repo4", mailingListBridgeBot2.codeRepo().name());
            assertEquals("archive", mailingListBridgeBot2.archiveRepo().name());
            assertEquals("master", mailingListBridgeBot2.archiveRef());
            assertEquals("https://mail.test.org/test/", mailingListBridgeBot2.listArchive().toString());
            assertEquals("master", mailingListBridgeBot2.censusRef());
            assertEquals("census", mailingListBridgeBot2.censusRepo().name());
            assertEquals("<test_email2@test.com>", mailingListBridgeBot2.lists().get(0).list().toString());
            assertEquals("[ignore1[bot], ignore2[bot], ignore4[bot], ignore3[bot]]", mailingListBridgeBot2.ignoredUsers().toString());
            assertEquals("[<!-- It's a test comment!-->]", mailingListBridgeBot2.ignoredComments().toString());
            assertEquals("0.0.0.0", mailingListBridgeBot2.smtpServer());
            assertEquals("[rfr]", mailingListBridgeBot2.readyLabels().toString());
            assertEquals("{test_user[bot]=<!-- Welcome message -->}", mailingListBridgeBot2.readyComments().toString());
            assertEquals(0, mailingListBridgeBot2.headers().size());
            assertEquals("https://bugs.test.org/browse/", mailingListBridgeBot2.issueTracker().toString());
            assertEquals(Duration.ofSeconds(5), mailingListBridgeBot2.sendInterval());
            assertEquals(Duration.ofMinutes(2), mailingListBridgeBot2.cooldown());
            assertTrue(mailingListBridgeBot2.repoInSubject());

            MailingListBridgeBot mailingListBridgeBot3 = (MailingListBridgeBot) mailingListBridgeBots.get(2);
            assertEquals("MailingListBridgeBot@repo5", mailingListBridgeBot3.toString());
            assertEquals("repo5", mailingListBridgeBot3.codeRepo().name());
            assertEquals("archive", mailingListBridgeBot3.archiveRepo().name());
            assertEquals("master", mailingListBridgeBot3.archiveRef());
            assertEquals("https://mail.test.org/test/", mailingListBridgeBot3.listArchive().toString());
            assertEquals("master", mailingListBridgeBot3.censusRef());
            assertEquals("census", mailingListBridgeBot3.censusRepo().name());
            assertEquals("<test_email3@test.org>", mailingListBridgeBot3.lists().get(0).list().toString());
            assertEquals("[label1, label2, label3]", mailingListBridgeBot3.lists().get(0).labels().toString());
            assertEquals("[ignore1[bot], ignore2[bot], ignore4[bot], ignore3[bot]]", mailingListBridgeBot3.ignoredUsers().toString());
            assertEquals("[<!-- It's a test comment!-->]", mailingListBridgeBot3.ignoredComments().toString());
            assertEquals("0.0.0.0", mailingListBridgeBot3.smtpServer());
            assertEquals("[rfr]", mailingListBridgeBot3.readyLabels().toString());
            assertEquals("{test_user[bot]=<!-- Welcome message -->}", mailingListBridgeBot3.readyComments().toString());
            assertEquals("{Approved=test5}", mailingListBridgeBot3.headers().toString());
            assertEquals("https://bugs.test.org/browse/", mailingListBridgeBot3.issueTracker().toString());
            assertEquals(Duration.ofSeconds(5), mailingListBridgeBot3.sendInterval());
            assertEquals(Duration.ofMinutes(2), mailingListBridgeBot3.cooldown());
            assertTrue(mailingListBridgeBot3.repoInSubject());
            assertEquals("master", mailingListBridgeBot3.branchInSubject().toString());
        }
    }
}