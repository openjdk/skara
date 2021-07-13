package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LabelsUpdaterTests {

    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var listServer = new TestMailmanServer();) {
            var targetRepo = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mlBot = MailingListBridgeBot.newBuilder()
                    .repo(targetRepo)
                    .lists(List.of(new MailingListConfiguration(listAddress, Set.of("foo", "bar"))))
                    .build();

            // Check that the repo contains no labels
            assertTrue(targetRepo.labels().isEmpty(), "Repo has labels from the start: " + targetRepo.labels());

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            assertEquals(2, targetRepo.labels().size(), "Wrong number of labels");
            assertTrue(targetRepo.labels().stream()
                    .anyMatch(l -> l.name().equals("foo") && l.description().orElseThrow().equals(listAddress.address())),
                    "No label 'foo' found");
            assertTrue(targetRepo.labels().stream()
                            .anyMatch(l -> l.name().equals("bar") && l.description().orElseThrow().equals(listAddress.address())),
                    "No label 'bar' found");

            // Run again and expect no change
            TestBotRunner.runPeriodicItems(mlBot);

            assertEquals(2, targetRepo.labels().size(), "Wrong number of labels");
        }
    }

    @Test
    void update(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var listServer = new TestMailmanServer();) {
            var targetRepo = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var listAddress2 = EmailAddress.parse(listServer.createList("test2"));
            var mlBot = MailingListBridgeBot.newBuilder()
                    .repo(targetRepo)
                    .lists(List.of(new MailingListConfiguration(listAddress, Set.of("foo"))))
                    .build();

            // Check that the repo contains no labels
            assertTrue(targetRepo.labels().isEmpty(), "Repo has labels from the start: " + targetRepo.labels());

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            assertEquals(1, targetRepo.labels().size(), "Wrong number of labels");
            assertTrue(targetRepo.labels().stream()
                            .anyMatch(l -> l.name().equals("foo") && l.description().orElseThrow().equals(listAddress.address())),
                    "No label 'foo' found");

            var mlBot2 = MailingListBridgeBot.newBuilder()
                    .repo(targetRepo)
                    .lists(List.of(new MailingListConfiguration(listAddress2, Set.of("foo"))))
                    .build();

            // Run second bot and expect label to have updated
            TestBotRunner.runPeriodicItems(mlBot2);

            assertEquals(1, targetRepo.labels().size(), "Wrong number of labels");
            assertTrue(targetRepo.labels().stream()
                            .anyMatch(l -> l.name().equals("foo") && l.description().orElseThrow().equals(listAddress2.address())),
                    "No label 'foo' found");
        }
    }
}
