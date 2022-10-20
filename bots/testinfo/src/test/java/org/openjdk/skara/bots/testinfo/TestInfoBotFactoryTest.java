package org.openjdk.skara.bots.testinfo;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
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

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                .build();

        var bots = testBotFactory.createBots(TestInfoBotFactory.NAME, jsonConfig);
        // A testInfoBot for every configured repo
        assertEquals(2, bots.size());

        assertEquals("TestInfoBot@repo1", bots.get(0).toString());
        assertEquals("TestInfoBot@repo2", bots.get(1).toString());
    }
}