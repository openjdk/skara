package org.openjdk.skara.bots.topological;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class TopologicalBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repo": "repo1",
                      "branches": [
                        "master",
                        "dev",
                        "test"
                      ],
                      "depsFile": "test"
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(TopologicalBotFactory.NAME, jsonConfig);
            // A topologicalBot for every configured repo
            assertEquals(1, bots.size());

            TopologicalBot topologicalBot1 = (TopologicalBot) bots.get(0);
            assertEquals("TopologicalBot@repo1", topologicalBot1.toString());
            assertEquals("master", topologicalBot1.getBranches().get(0).toString());
            assertEquals("dev", topologicalBot1.getBranches().get(1).toString());
            assertEquals("test", topologicalBot1.getBranches().get(2).toString());
        }
    }
}