package org.openjdk.skara.bots.checkout;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "marks": {
                    "repo": "mark",
                    "author": "test_author <test_author@test.com>"
                  },
                  "repositories": [
                    {
                      "from": {
                        "repo": "from1",
                        "branch": "master"
                      },
                      "to": "to1"
                    },
                    {
                      "from": {
                        "repo": "from2",
                        "branch": "dev"
                      },
                      "to": "to2"
                    }
                  ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("mark", new TestHostedRepository("mark"))
                .addHostedRepository("from1", new TestHostedRepository("from1"))
                .addHostedRepository("from2", new TestHostedRepository("from2"))
                .build();

        var bots = testBotFactory.createBots(CheckoutBotFactory.NAME, jsonConfig);
        // A checkoutBot for every configured repository
        assertEquals(2, bots.size());

        assertEquals("CheckoutBot(from1:master, to1)", bots.get(0).toString());
        assertEquals("CheckoutBot(from2:dev, to2)", bots.get(1).toString());
    }
}