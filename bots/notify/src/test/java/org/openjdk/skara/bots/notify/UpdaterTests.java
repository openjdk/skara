/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.email.*;
import org.openjdk.skara.host.HostedRepository;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Tag;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UpdaterTests {
    private List<Path> findJsonFiles(Path folder, String partialName) throws IOException {
        return Files.walk(folder)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> path.toString().contains(partialName))
                    .collect(Collectors.toList());
    }

    private StorageBuilder<Tag> createTagStorage(HostedRepository repository) throws IOException {
        return new StorageBuilder<Tag>("tags.txt")
                .remoteRepository(repository, "refs/heads/history", "Duke", "duke@openjdk.java.net", "Updated tags");
    }

    private StorageBuilder<ResolvedBranch> createBranchStorage(HostedRepository repository) throws IOException {
        return new StorageBuilder<ResolvedBranch>("branches.txt")
                .remoteRepository(repository, "refs/heads/history", "Duke", "duke@openjdk.java.net", "Updated branches");
    }

    @Test
    void testJsonUpdaterBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.getRepositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var jsonFolder = tempFolder.path().resolve("json");
            Files.createDirectory(jsonFolder);
            var storageFolder = tempFolder.path().resolve("storage");

            var updater = new JsonUpdater(jsonFolder, "12", "team");
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(List.of(), findJsonFiles(jsonFolder, ""));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "One more line", "12345678: Fixes");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(1, jsonFiles.size());
            var jsonData = Files.readString(jsonFiles.get(0), StandardCharsets.UTF_8);
            var json = JSON.parse(jsonData);
            assertEquals(1, json.asArray().size());
            assertEquals(repo.getWebUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
            assertEquals(List.of("12345678"), json.asArray().get(0).get("issue").asArray().stream()
                                                  .map(JSONValue::asString)
                                                  .collect(Collectors.toList()));
        }
    }

    @Test
    void testJsonUpdaterTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.getRepositoryType());
            credentials.commitLock(localRepo);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.tag(masterHash, "jdk-12+1", "Added tag 1", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.getUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var jsonFolder = tempFolder.path().resolve("json");
            Files.createDirectory(jsonFolder);
            var storageFolder =tempFolder.path().resolve("storage");

            var updater = new JsonUpdater(jsonFolder, "12", "team");
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(List.of(), findJsonFiles(jsonFolder, ""));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.fetch(repo.getUrl(), "history:history");
            localRepo.tag(editHash, "jdk-12+2", "Added tag 2", "Duke", "duke@openjdk.java.net");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Another line", "34567890: Even more fixes");
            localRepo.tag(editHash2, "jdk-12+4", "Added tag 3", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.getUrl());

            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(3, jsonFiles.size());

            for (var file : jsonFiles) {
                var jsonData = Files.readString(file, StandardCharsets.UTF_8);
                var json = JSON.parse(jsonData);

                if (json.asArray().get(0).contains("date")) {
                    assertEquals(2, json.asArray().size());
                    assertEquals(List.of("23456789"), json.asArray().get(0).get("issue").asArray().stream()
                                                          .map(JSONValue::asString)
                                                          .collect(Collectors.toList()));
                    assertEquals(repo.getWebUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
                    assertEquals("team", json.asArray().get(0).get("build").asString());
                    assertEquals(List.of("34567890"), json.asArray().get(1).get("issue").asArray().stream()
                                                          .map(JSONValue::asString)
                                                          .collect(Collectors.toList()));
                    assertEquals(repo.getWebUrl(editHash2).toString(), json.asArray().get(1).get("url").asString());
                    assertEquals("team", json.asArray().get(1).get("build").asString());
                } else {
                    assertEquals(1, json.asArray().size());
                    if (json.asArray().get(0).get("build").asString().equals("b02")) {
                        assertEquals(List.of("23456789"), json.asArray().get(0).get("issue").asArray().stream()
                                                              .map(JSONValue::asString)
                                                              .collect(Collectors.toList()));
                    } else {
                        assertEquals("b04", json.asArray().get(0).get("build").asString());
                        assertEquals(List.of("34567890"), json.asArray().get(0).get("issue").asArray().stream()
                                                              .map(JSONValue::asString)
                                                              .collect(Collectors.toList()));
                    }
                }
            }
        }
    }

    @Test
    void testMailingList(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = new MailingListUpdater(mailmanList, listAddress, sender, false, MailingListUpdater.Mode.ALL);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(listAddress));
            assertTrue(email.subject().contains(": 23456789: More fixes"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertFalse(email.body().contains("Committer"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListMultiple(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = new MailingListUpdater(mailmanList, listAddress, sender, false, MailingListUpdater.Mode.ALL);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash1 = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash1, repo.getUrl(), "master");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "3456789A: Even more fixes");
            localRepo.push(editHash2, repo.getUrl(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(listAddress));
            assertTrue(email.subject().contains(": 2 new changesets"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash1.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertTrue(email.body().contains("Changeset: " + editHash2.abbreviate()));
            assertTrue(email.body().contains("3456789A: Even more fixes"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListSponsored(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = new MailingListUpdater(mailmanList, listAddress, sender, false, MailingListUpdater.Mode.ALL);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes",
                                                               "author", "author@test.test",
                                                               "committer", "committer@test.test");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(listAddress));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertTrue(email.body().contains("Author:    author <author@test.test>"));
            assertTrue(email.body().contains("Committer: committer <committer@test.test>"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListMultipleBranches(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            var branch = localRepo.branch(masterHash, "another");
            localRepo.pushAll(repo.getUrl());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = new MailingListUpdater(mailmanList, listAddress, sender, true, MailingListUpdater.Mode.ALL);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master", "another"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash1 = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash1, repo.getUrl(), "master");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "3456789A: Even more fixes");
            localRepo.push(editHash2, repo.getUrl(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(listAddress));
            assertFalse(email.subject().contains("another"));
            assertTrue(email.subject().contains(": master: 2 new changesets"));
            assertTrue(email.body().contains("Changeset: " + editHash1.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertTrue(email.body().contains("Changeset: " + editHash2.abbreviate()));
            assertTrue(email.body().contains("3456789A: Even more fixes"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
            assertFalse(email.body().contains("456789AB: Yet more fixes"));

            localRepo.checkout(branch, true);
            var editHash3 = CheckableRepository.appendAndCommit(localRepo, "Another branch", "456789AB: Yet more fixes");
            localRepo.push(editHash3, repo.getUrl(), "another");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            email = conversations.get(0).first();
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(listAddress));
            assertTrue(email.subject().contains(": another: 456789AB: Yet more fixes"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash3.abbreviate()));
            assertTrue(email.body().contains("456789AB: Yet more fixes"));
            assertFalse(email.body().contains("Changeset: " + editHash2.abbreviate()));
            assertFalse(email.body().contains("3456789A: Even more fixes"));
        }
    }

    @Test
    void testMailingListPROnly(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = new MailingListUpdater(mailmanList, listAddress, sender, false, MailingListUpdater.Mode.PR_ONLY);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.getUrl(), "edit");
            var pr = credentials.createPullRequest(repo, "master", "edit", "RFR: My PR");

            // Create a potentially conflicting one
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(otherHash, repo.getUrl(), "other");
            var otherPr = credentials.createPullRequest(repo, "master", "other", "RFR: My other PR");

            // PR hasn't been integrated yet, so there should be no mail
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Simulate an RFR email
            var rfr = Email.create(sender, "RFR: My PR", "PR: " + pr.getWebUrl().toString())
                    .recipient(listAddress)
                    .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var first = conversations.get(0).first();
            var email = conversations.get(0).replies(first).get(0);
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(listAddress));
            assertEquals("Re: [Integrated] RFR: My PR", email.subject());
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertFalse(email.body().contains("Committer"));
            assertFalse(email.body().contains(masterHash.abbreviate()));

            // Now push the other one without a matching PR - PR_ONLY will not generate a mail
            localRepo.push(otherHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofSeconds(1)));
        }
    }

    @Test
    void testMailingListPR(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = new MailingListUpdater(mailmanList, listAddress, sender, false, MailingListUpdater.Mode.PR);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.getUrl(), "edit");
            var pr = credentials.createPullRequest(repo, "master", "edit", "RFR: My PR");

            // Create a potentially conflicting one
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(otherHash, repo.getUrl(), "other");
            var otherPr = credentials.createPullRequest(repo, "master", "other", "RFR: My other PR");

            // PR hasn't been integrated yet, so there should be no mail
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Simulate an RFR email
            var rfr = Email.create(sender, "RFR: My PR", "PR:\n" + pr.getWebUrl().toString())
                           .recipient(listAddress)
                           .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.getUrl(), "master");

            // Push the other one without a matching PR
            localRepo.push(otherHash, repo.getUrl(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            assertEquals(2, conversations.size());

            var prConversation = conversations.get(0);
            var pushConverstaion = conversations.get(1);

            var prEmail = prConversation.replies(prConversation.first()).get(0);
            assertEquals(prEmail.sender(), sender);
            assertEquals(prEmail.recipients(), List.of(listAddress));
            assertEquals("Re: [Integrated] RFR: My PR", prEmail.subject());
            assertFalse(prEmail.subject().contains("master"));
            assertTrue(prEmail.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(prEmail.body().contains("23456789: More fixes"));
            assertFalse(prEmail.body().contains("Committer"));
            assertFalse(prEmail.body().contains(masterHash.abbreviate()));

            var pushEmail = pushConverstaion.first();
            assertEquals(pushEmail.sender(), sender);
            assertEquals(pushEmail.recipients(), List.of(listAddress));
            assertTrue(pushEmail.subject().contains("23456789: More fixes"));
        }
    }
}
