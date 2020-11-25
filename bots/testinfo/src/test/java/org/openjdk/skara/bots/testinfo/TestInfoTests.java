package org.openjdk.skara.bots.testinfo;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.CheckBuilder;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestInfoTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var checkBot = new TestInfoBot(author);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), Path.of("appendable.txt"),
                                                     Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a draft PR where we can add some checks
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "preedit", true);
            var draftPr = credentials.createPullRequest(author, "master", "preedit", "This is a pull request", true);
            var check1 = CheckBuilder.create("ps1", editHash).title("PS1");
            draftPr.createCheck(check1.build());
            draftPr.updateCheck(check1.complete(true).build());
            var check2 = CheckBuilder.create("ps2", editHash).title("PS2");
            draftPr.createCheck(check2.build());
            draftPr.updateCheck(check2.complete(false).build());
            var check3 = CheckBuilder.create("ps3", editHash).title("PS3");
            draftPr.createCheck(check3.build());
            draftPr.updateCheck(check3.details(URI.create("https://www.example.com")).complete(false).build());

            // Now make an actual PR
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Passing jobs are summarized
            assertEquals(3, pr.checks(editHash).size());
            assertEquals("1/1 passed", pr.checks(editHash).get("ps1 - Build / test").title().orElseThrow());
            assertEquals("✔️ ps1", pr.checks(editHash).get("ps1 - Build / test").summary().orElseThrow());

            // Failing jobs are reported individually
            assertEquals("Failure", pr.checks(editHash).get("ps2").title().orElseThrow());
            assertNull(pr.checks(editHash).get("ps2").details().orElse(null));
            assertEquals("Failure", pr.checks(editHash).get("ps3").title().orElseThrow());
            assertEquals(URI.create("https://www.example.com"), pr.checks(editHash).get("ps3").details().orElseThrow());
        }
    }
}
