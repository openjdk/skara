package org.openjdk.skara.bots.synclabel;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestIssueProject;

import static org.junit.jupiter.api.Assertions.*;

class SyncLabelBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "issueprojects": [
                    {
                      "project": "test_bugs/TEST",
                      "inspect": ".*",
                      "ignore": "\\\\b\\\\B"
                    }
                  ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addIssueProject("test_bugs/TEST", new TestIssueProject(null, "TEST"))
                .build();

        var bots = testBotFactory.createBots(SyncLabelBotFactory.NAME, jsonConfig);
        // A syncLabelBot for every configured issueProject
        assertEquals(1, bots.size());

        SyncLabelBot syncLabelBot1 = (SyncLabelBot) bots.get(0);
        assertEquals("SyncLabelBot@TEST", syncLabelBot1.toString());
        assertEquals(".*", syncLabelBot1.inspect().toString());
        assertEquals("\\b\\B", syncLabelBot1.ignore().toString());
    }
}