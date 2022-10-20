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
                            "resolve": false
                          },
                          "comment": {
                            "project": "test_bugs/TEST"
                          },
                          "prbranch": {
                          }
                        }
                      }
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("notify", new TestHostedRepository("notify"))
                    .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                    .addIssueProject("test_bugs/TEST", new TestIssueProject(TestHost.createNew(List.of()), "TEST"))
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