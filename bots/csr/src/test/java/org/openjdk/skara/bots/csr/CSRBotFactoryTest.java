package org.openjdk.skara.bots.csr;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.json.*;
import org.openjdk.skara.test.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CSRBotFactoryTest {
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
                     },
                     {
                       "repository": "repo3",
                       "issues": "test_bugs/TEST2"
                     }
                   ]
                 }
                """;
        var jsonConfig = JWCC.parse(jsonString).asObject();

        var testBotFactory = TestBotFactory.newBuilder()
                .addHostedRepository("repo1", new TestHostedRepository("repo1"))
                .addHostedRepository("repo2", new TestHostedRepository("repo2"))
                .addHostedRepository("repo3", new TestHostedRepository("repo3"))
                .addIssueProject("test_bugs/TEST", new TestIssueProject(null, "TEST"))
                .addIssueProject("test_bugs/TEST2", new TestIssueProject(null, "TEST2"))
                .build();

        var bots = testBotFactory.createBots(CSRBotFactory.NAME, jsonConfig);
        assertEquals(5, bots.size());

        var csrPullRequestBots = bots.stream().filter(e -> e.getClass().equals(CSRPullRequestBot.class)).toList();
        var csrIssueBots = bots.stream().filter(e -> e.getClass().equals(CSRIssueBot.class)).toList();

        // A CSRPullRequestBot for every configured repository
        assertEquals(3, csrPullRequestBots.size());
        // A CSRIssueBot for each unique IssueProject
        assertEquals(2, csrIssueBots.size());

        var CSRPullRequestBot1 = (CSRPullRequestBot) csrPullRequestBots.get(0);
        var CSRPullRequestBot2 = (CSRPullRequestBot) csrPullRequestBots.get(1);
        var CSRPullRequestBot3 = (CSRPullRequestBot) csrPullRequestBots.get(2);
        assertEquals("CSRPullRequestBot@repo1", CSRPullRequestBot1.toString());
        assertEquals("CSRPullRequestBot@repo2", CSRPullRequestBot2.toString());
        assertEquals("CSRPullRequestBot@repo3", CSRPullRequestBot3.toString());
        assertEquals("TEST", CSRPullRequestBot1.getProject().name());
        assertEquals("TEST", CSRPullRequestBot2.getProject().name());
        assertEquals("TEST2", CSRPullRequestBot3.getProject().name());

        for (var bot : csrIssueBots) {
            CSRIssueBot csrIssueBot = (CSRIssueBot) bot;
            if (csrIssueBot.toString().equals("CSRIssueBot@TEST")) {
                assertEquals(2, csrIssueBot.repositories().size());
            } else if (csrIssueBot.toString().equals("CSRIssueBot@TEST2")) {
                assertEquals(1, csrIssueBot.repositories().size());
            } else {
                throw new RuntimeException("This CSRIssueBot is not expected");
            }
        }
    }
}
