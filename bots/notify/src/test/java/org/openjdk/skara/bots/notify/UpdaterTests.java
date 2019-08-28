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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.host.HostedRepository;
import org.openjdk.skara.json.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Tag;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
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

            var editHash = CheckableRepository.appendAndCommit(localRepo, "One more line", "1234567: Fixes");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(1, jsonFiles.size());
            var jsonData = Files.readString(jsonFiles.get(0), StandardCharsets.UTF_8);
            var json = JSON.parse(jsonData);
            assertEquals(1, json.asArray().size());
            assertEquals(repo.getWebUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
            assertEquals(List.of("1234567"), json.asArray().get(0).get("issue").asArray().stream()
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

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "2345678: More fixes");
            localRepo.fetch(repo.getUrl(), "history:history");
            localRepo.tag(editHash, "jdk-12+2", "Added tag 2", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.getUrl());

            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(2, jsonFiles.size());

            for (var file : jsonFiles) {
                var jsonData = Files.readString(file, StandardCharsets.UTF_8);
                var json = JSON.parse(jsonData);
                assertEquals(1, json.asArray().size());
                assertEquals(List.of("2345678"), json.asArray().get(0).get("issue").asArray().stream()
                                                      .map(JSONValue::asString)
                                                      .collect(Collectors.toList()));

                if (json.asArray().get(0).contains("date")) {
                    assertEquals(repo.getWebUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
                    assertEquals("team", json.asArray().get(0).get("build").asString());
                } else {
                    assertEquals("b02", json.asArray().get(0).get("build").asString());
                }
            }
        }
    }

    @Test
    void testMailingList(TestInfo testInfo) throws IOException {
        try (var smtpServer = new SMTPServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var recipient = EmailAddress.from("list", "list@list.list");
            var updater = new MailingListUpdater(smtpServer.address(), recipient, sender, false);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> smtpServer.receive(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "2345678: More fixes");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            var email = smtpServer.receive(Duration.ofSeconds(10));
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(recipient));
            assertTrue(email.subject().contains(": 2345678: More fixes"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("2345678: More fixes"));
            assertFalse(email.body().contains("Committer"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListMultiple(TestInfo testInfo) throws IOException {
        try (var smtpServer = new SMTPServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var recipient = EmailAddress.from("list", "list@list.list");
            var updater = new MailingListUpdater(smtpServer.address(), recipient, sender, false);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> smtpServer.receive(Duration.ofMillis(1)));

            var editHash1 = CheckableRepository.appendAndCommit(localRepo, "Another line", "2345678: More fixes");
            localRepo.push(editHash1, repo.getUrl(), "master");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "3456789A: Even more fixes");
            localRepo.push(editHash2, repo.getUrl(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            var email = smtpServer.receive(Duration.ofSeconds(10));
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(recipient));
            assertTrue(email.subject().contains(": 2 new changesets"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash1.abbreviate()));
            assertTrue(email.body().contains("2345678: More fixes"));
            assertTrue(email.body().contains("Changeset: " + editHash2.abbreviate()));
            assertTrue(email.body().contains("3456789A: Even more fixes"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListSponsored(TestInfo testInfo) throws IOException {
        try (var smtpServer = new SMTPServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.getUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var recipient = EmailAddress.from("list", "list@list.list");
            var updater = new MailingListUpdater(smtpServer.address(), recipient, sender, false);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> smtpServer.receive(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "2345678: More fixes",
                                                               "author", "author@test.test",
                                                               "committer", "committer@test.test");
            localRepo.push(editHash, repo.getUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            var email = smtpServer.receive(Duration.ofSeconds(10));
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(recipient));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("2345678: More fixes"));
            assertTrue(email.body().contains("Author:    author <author@test.test>"));
            assertTrue(email.body().contains("Committer: committer <committer@test.test>"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListMultipleBranches(TestInfo testInfo) throws IOException {
        try (var smtpServer = new SMTPServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            var branch = localRepo.branch(masterHash, "another");
            localRepo.pushAll(repo.getUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var recipient = EmailAddress.from("list", "list@list.list");
            var updater = new MailingListUpdater(smtpServer.address(), recipient, sender, true);
            var notifyBot = new JNotifyBot(repo, storageFolder, List.of("master", "another"), tagStorage, branchStorage, List.of(updater));

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> smtpServer.receive(Duration.ofMillis(1)));

            var editHash1 = CheckableRepository.appendAndCommit(localRepo, "Another line", "2345678: More fixes");
            localRepo.push(editHash1, repo.getUrl(), "master");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "3456789A: Even more fixes");
            localRepo.push(editHash2, repo.getUrl(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            var email = smtpServer.receive(Duration.ofSeconds(10));
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(recipient));
            assertFalse(email.subject().contains("another"));
            assertTrue(email.subject().contains(": master: 2 new changesets"));
            assertTrue(email.body().contains("Changeset: " + editHash1.abbreviate()));
            assertTrue(email.body().contains("2345678: More fixes"));
            assertTrue(email.body().contains("Changeset: " + editHash2.abbreviate()));
            assertTrue(email.body().contains("3456789A: Even more fixes"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
            assertFalse(email.body().contains("456789AB: Yet more fixes"));

            localRepo.checkout(branch, true);
            var editHash3 = CheckableRepository.appendAndCommit(localRepo, "Another branch", "456789AB: Yet more fixes");
            localRepo.push(editHash3, repo.getUrl(), "another");

            TestBotRunner.runPeriodicItems(notifyBot);
            email = smtpServer.receive(Duration.ofSeconds(10));
            assertEquals(email.sender(), sender);
            assertEquals(email.recipients(), List.of(recipient));
            assertTrue(email.subject().contains(": another: 456789AB: Yet more fixes"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash3.abbreviate()));
            assertTrue(email.body().contains("456789AB: Yet more fixes"));
            assertFalse(email.body().contains("Changeset: " + editHash2.abbreviate()));
            assertFalse(email.body().contains("3456789A: Even more fixes"));
        }
    }
}
