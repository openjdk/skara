package org.openjdk.skara.bots.tester;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class TestBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "census": "census",
                      "approvers": "approver",
                      "allowlist": [
                        "allow1",
                        "allow2"
                      ],
                      "availableJobs": [
                        "availableJob1",
                        "availableJob2"
                      ],
                      "defaultJobs":[
                        "defaultJob1",
                        "defaultJob2"
                      ],
                      "ci": "ci_test",
                      "name": "name",
                      "repositories": [
                        "repo1",
                        "repo2"
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                    .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                    .addHostedRepository("census", new TestHostedRepository("census"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(org.openjdk.skara.bots.tester.TestBotFactory.NAME, jsonConfig);
            //A TestBot for every configured repo
            assertEquals(2, bots.size());

            assertEquals("TestBot@repo1", bots.get(0).toString());
            assertEquals("TestBot@repo2", bots.get(1).toString());
        }
    }
}