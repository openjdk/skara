package org.openjdk.skara.bots.submit;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class SubmitBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "executors": {
                    "executor1": {
                      "type": "shell",
                      "timeout": "P3D",
                      "config": {
                        "cmd": [
                        ],
                        "name": "name1",
                        "env": {
                          "key1": "val1",
                          "key2": "val2"
                        }
                      }
                    }
                  },
                  "repositories": {
                    "repo1": "executor1"
                  }
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                .build();

        var bots = testBotFactory.createBots(SubmitBotFactory.NAME, jsonConfig);
        //A submitBot for every configured repository
        assertEquals(1, bots.size());

        assertEquals("SubmitBot@repo1", bots.get(0).toString());
    }
}