package org.openjdk.skara.bots.bridgekeeper;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BridgekeeperBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "mirrors": [
                    "mirror1",
                    "mirror2",
                    "mirror3"
                  ],
                  "data": [
                    "data1",
                    "data2",
                    "data3"
                  ],
                  "pruned": {
                    "pruned1": {
                      "maxage": "P1D"
                    },
                    "pruned2": {
                      "maxage": "PT48H"
                    },
                    "pruned3": {
                      "maxage": "PT4320M"
                    }
                  }
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var pruned1 = new TestHostedRepository("pruned1");
        var pruned2 = new TestHostedRepository("pruned2");
        var pruned3 = new TestHostedRepository("pruned3");
        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("mirror1", new TestHostedRepository("mirror1"))
                .addHostedRepository("mirror2", new TestHostedRepository("mirror2"))
                .addHostedRepository("mirror3", new TestHostedRepository("mirror3"))
                .addHostedRepository("data1", new TestHostedRepository("data1"))
                .addHostedRepository("data2", new TestHostedRepository("data2"))
                .addHostedRepository("data3", new TestHostedRepository("data3"))
                .addHostedRepository("pruned1", pruned1)
                .addHostedRepository("pruned2", pruned2)
                .addHostedRepository("pruned3", pruned3)
                .build();

        var bots = testBotFactory.createBots(BridgekeeperBotFactory.NAME, jsonConfig);
        assertEquals(7, bots.size());

        var mirrorPullRequestCloserBots = bots.stream()
                .filter(e -> e.getClass().equals(PullRequestCloserBot.class))
                .filter(e -> ((PullRequestCloserBot) e).getType().equals(PullRequestCloserBot.Type.MIRROR))
                .toList();
        var dataPullRequestCloserBots = bots.stream()
                .filter(e -> e.getClass().equals(PullRequestCloserBot.class))
                .filter(e -> ((PullRequestCloserBot) e).getType().equals(PullRequestCloserBot.Type.DATA))
                .toList();
        var pullRequestPrunerBots = bots.stream()
                .filter(e -> e.getClass().equals(PullRequestPrunerBot.class))
                .toList();

        // A mirror pullRequestCloserBot for every configured mirror repository
        assertEquals(3, mirrorPullRequestCloserBots.size());
        // A data pullRequestCloserBot for every configured data repository
        assertEquals(3, dataPullRequestCloserBots.size());
        // One pullRequestPrunerBot for all configured pruned repository
        assertEquals(1, pullRequestPrunerBots.size());

        // Check whether each bot is combined with the correct repo
        assertEquals("PullRequestCloserBot@mirror1", mirrorPullRequestCloserBots.get(0).toString());
        assertEquals("PullRequestCloserBot@mirror2", mirrorPullRequestCloserBots.get(1).toString());
        assertEquals("PullRequestCloserBot@mirror3", mirrorPullRequestCloserBots.get(2).toString());
        assertEquals("PullRequestCloserBot@data1", dataPullRequestCloserBots.get(0).toString());
        assertEquals("PullRequestCloserBot@data2", dataPullRequestCloserBots.get(1).toString());
        assertEquals("PullRequestCloserBot@data3", dataPullRequestCloserBots.get(2).toString());

        var pullRequestPrunerBot = (PullRequestPrunerBot) pullRequestPrunerBots.get(0);
        assertEquals("PullRequestPrunerBot", pullRequestPrunerBot.toString());
        var maxAges = pullRequestPrunerBot.getMaxAges();
        assertEquals(Duration.ofDays(1), maxAges.get(pruned1));
        assertEquals(Duration.ofDays(2), maxAges.get(pruned2));
        assertEquals(Duration.ofDays(3), maxAges.get(pruned3));
    }
}