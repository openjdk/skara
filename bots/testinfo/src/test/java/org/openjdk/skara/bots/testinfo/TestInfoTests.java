package org.openjdk.skara.bots.testinfo;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            var check4 = CheckBuilder.create("ps4", editHash).title("PS4");
            draftPr.createCheck(check4.build());
            draftPr.updateCheck(check4.details(URI.create("https://www.example.com")).build());

            // Now make an actual PR
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status
            TestBotRunner.runPeriodicItems(checkBot);

            // Verify summarized checks
            assertEquals(4, pr.checks(editHash).size());
            assertEquals("1/1 passed", pr.checks(editHash).get("Pre-submit tests - ps1 - Build / test").title().orElseThrow());
            assertEquals("✔️ ps1", pr.checks(editHash).get("Pre-submit tests - ps1 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.SUCCESS, pr.checks(editHash).get("Pre-submit tests - ps1 - Build / test").status());
            assertEquals("1/1 failed", pr.checks(editHash).get("Pre-submit tests - ps2 - Build / test").title().orElseThrow());
            assertEquals("❌ ps2", pr.checks(editHash).get("Pre-submit tests - ps2 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.FAILURE, pr.checks(editHash).get("Pre-submit tests - ps2 - Build / test").status());
            assertEquals("1/1 failed", pr.checks(editHash).get("Pre-submit tests - ps3 - Build / test").title().orElseThrow());
            assertEquals("❌ [ps3](https://www.example.com)", pr.checks(editHash).get("Pre-submit tests - ps3 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.FAILURE, pr.checks(editHash).get("Pre-submit tests - ps3 - Build / test").status());
            assertEquals("1/1 running", pr.checks(editHash).get("Pre-submit tests - ps4 - Build / test").title().orElseThrow());
            assertEquals("⏳ [ps4](https://www.example.com)", pr.checks(editHash).get("Pre-submit tests - ps4 - Build / test").summary().orElseThrow());
            assertEquals(CheckStatus.IN_PROGRESS, pr.checks(editHash).get("Pre-submit tests - ps4 - Build / test").status());
        }
    }
}
