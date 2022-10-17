package org.openjdk.skara.bots.mirror;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;

import static org.junit.jupiter.api.Assertions.*;

class MirrorBotFactoryTest {
    @Test
    public void testCreate() {
        try (var tempFolder = new TemporaryDirectory()) {
            String jsonString = """
                    {
                      "repositories": [
                        {
                          "from": "from1",
                          "to": "to1",
                          "branches": "master"
                        },
                        {
                          "from": "from2",
                          "to": "to2",
                          "branches": [
                            "master",
                            "dev",
                            "test"
                          ]
                        },
                        {
                          "from": "from3",
                          "to": "to3"
                        },
                      ]
                    }
                    """;
            var jsonConfig = JWCC.parse(jsonString).asObject();

            var testBotFactory = TestBotFactory.newBuilder()
                    .addHostedRepository("from1", new TestHostedRepository("from1"))
                    .addHostedRepository("from2", new TestHostedRepository("from2"))
                    .addHostedRepository("from3", new TestHostedRepository("from3"))
                    .addHostedRepository("to1", new TestHostedRepository("to1"))
                    .addHostedRepository("to2", new TestHostedRepository("to2"))
                    .addHostedRepository("to3", new TestHostedRepository("to3"))
                    .storagePath(tempFolder.path().resolve("storage"))
                    .build();

            var bots = testBotFactory.createBots(MirrorBotFactory.NAME, jsonConfig);
            assertEquals(3, bots.size());

            MirrorBot mirrorBot1 = (MirrorBot) bots.get(0);
            assertEquals("MirrorBot@from1->to1 (master)", mirrorBot1.toString());
            assertFalse(mirrorBot1.isShouldMirrorEverything());
            assertFalse(mirrorBot1.isIncludeTags());
            assertEquals("master", mirrorBot1.getBranchPatterns().get(0).toString());

            MirrorBot mirrorBot2 = (MirrorBot) bots.get(1);
            assertEquals("MirrorBot@from2->to2 (master,dev,test)", mirrorBot2.toString());
            assertFalse(mirrorBot2.isShouldMirrorEverything());
            assertFalse(mirrorBot2.isIncludeTags());
            assertEquals("master", mirrorBot2.getBranchPatterns().get(0).toString());
            assertEquals("dev", mirrorBot2.getBranchPatterns().get(1).toString());
            assertEquals("test", mirrorBot2.getBranchPatterns().get(2).toString());

            MirrorBot mirrorBot3 = (MirrorBot) bots.get(2);
            assertEquals("MirrorBot@from3->to3", mirrorBot3.toString());
            assertTrue(mirrorBot3.isShouldMirrorEverything());
            assertTrue(mirrorBot3.isIncludeTags());
            assertEquals(0, mirrorBot3.getBranchPatterns().size());
        }
    }
}