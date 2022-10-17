package org.openjdk.skara.bots.jep;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.JWCC;
import org.openjdk.skara.test.TestBotFactory;
import org.openjdk.skara.test.TestHostedRepository;
import org.openjdk.skara.test.TestIssueProject;

import static org.junit.jupiter.api.Assertions.*;

class JEPBotFactoryTest {
    @Test
    public void testCreate() {
        String jsonString = """
                {
                  "projects": [
                    {
                      "repository": "repo1",
                      "issues": "test_bugs/TEST"
                    },
                    {
                      "repository": "repo2",
                      "issues": "test_bugs/TEST"
                    }
                  ]
                }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                .addIssueProject("test_bugs/TEST", new TestIssueProject(null, "TEST"))
                .build();

        var bots = testBotFactory.createBots(JEPBotFactory.NAME, jsonConfig);
        // A JEPBot for every configured project
        assertEquals(2, bots.size());

        JEPBot jepBot1 = (JEPBot) bots.get(0);
        assertEquals("JEPBot@repo1", jepBot1.toString());
        assertEquals("TEST", jepBot1.getIssueProject().name());

        JEPBot jepBot2 = (JEPBot) bots.get(1);
        assertEquals("JEPBot@repo2", jepBot2.toString());
        assertEquals("TEST", jepBot2.getIssueProject().name());
    }
}