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
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Tag;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.issuetracker.Issue.State.*;

class UpdaterTests {
    private List<Path> findJsonFiles(Path folder, String partialName) throws IOException {
        return Files.walk(folder)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> path.toString().contains(partialName))
                    .collect(Collectors.toList());
    }

    private StorageBuilder<Tag> createTagStorage(HostedRepository repository) {
        return new StorageBuilder<Tag>("tags.txt")
                .remoteRepository(repository, "history", "Duke", "duke@openjdk.java.net", "Updated tags");
    }

    private StorageBuilder<ResolvedBranch> createBranchStorage(HostedRepository repository) {
        return new StorageBuilder<ResolvedBranch>("branches.txt")
                .remoteRepository(repository, "history", "Duke", "duke@openjdk.java.net", "Updated branches");
    }

    private StorageBuilder<PullRequestIssues> createPullRequestIssuesStorage(HostedRepository repository) {
        return new StorageBuilder<PullRequestIssues>("prissues.txt")
                .remoteRepository(repository, "history", "Duke", "duke@openjdk.java.net", "Updated prissues");
    }

    private Set<String> fixVersions(Issue issue) {
        if (!issue.properties().containsKey("fixVersions")) {
            return Set.of();
        }
        return issue.properties().get("fixVersions").stream()
                    .map(JSONValue::asString)
                    .collect(Collectors.toSet());
    }

    @Test
    void testJsonUpdaterBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var jsonFolder = tempFolder.path().resolve("json");
            Files.createDirectory(jsonFolder);
            var storageFolder = tempFolder.path().resolve("storage");

            var updater = new JsonUpdater(jsonFolder, "12", "team");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(List.of(), findJsonFiles(jsonFolder, ""));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "One more line", "12345678: Fixes");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(1, jsonFiles.size());
            var jsonData = Files.readString(jsonFiles.get(0), StandardCharsets.UTF_8);
            var json = JSON.parse(jsonData);
            assertEquals(1, json.asArray().size());
            assertEquals(repo.webUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
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
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.tag(masterHash, "jdk-12+1", "Added tag 1", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var jsonFolder = tempFolder.path().resolve("json");
            Files.createDirectory(jsonFolder);
            var storageFolder =tempFolder.path().resolve("storage");

            var updater = new JsonUpdater(jsonFolder, "12", "team");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(List.of(), findJsonFiles(jsonFolder, ""));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.fetch(repo.url(), "history:history");
            localRepo.tag(editHash, "jdk-12+2", "Added tag 2", "Duke", "duke@openjdk.java.net");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Another line", "34567890: Even more fixes");
            localRepo.tag(editHash2, "jdk-12+4", "Added tag 3", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.url());

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
                    assertEquals(repo.webUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
                    assertEquals("team", json.asArray().get(0).get("build").asString());
                    assertEquals(List.of("34567890"), json.asArray().get(1).get("issue").asArray().stream()
                                                          .map(JSONValue::asString)
                                                          .collect(Collectors.toList()));
                    assertEquals(repo.webUrl(editHash2).toString(), json.asArray().get(1).get("url").asString());
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
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .headers(Map.of("extra1", "value1", "extra2", "value2"))
                                            .allowedAuthorDomains(Pattern.compile("none"))
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(listAddress, email.sender());
            assertEquals(sender, email.author());
            assertEquals(email.recipients(), List.of(listAddress));
            assertTrue(email.subject().contains(": 23456789: More fixes"));
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertFalse(email.body().contains("Committer"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
            assertTrue(email.hasHeader("extra1"));
            assertEquals("value1", email.headerValue("extra1"));
            assertTrue(email.hasHeader("extra2"));
            assertEquals("value2", email.headerValue("extra2"));
        }
    }

    @Test
    void testMailingListMultiple(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash1 = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes",
                                                                "first_author", "first@author.example.com");
            localRepo.push(editHash1, repo.url(), "master");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "3456789A: Even more fixes",
                                                                "another_author", "another@author.example.com");
            localRepo.push(editHash2, repo.url(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(listAddress, email.sender());
            assertEquals(EmailAddress.from("another_author", "another@author.example.com"), email.author());
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
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes",
                                                               "author", "author@test.test",
                                                               "committer", "committer@test.test");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(listAddress, email.sender());
            assertEquals(EmailAddress.from("committer", "committer@test.test"), email.author());
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
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            var branch = localRepo.branch(masterHash, "another");
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var author = EmailAddress.from("author", "author@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .author(author)
                                            .includeBranch(true)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master|another"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash1 = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash1, repo.url(), "master");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "3456789A: Even more fixes");
            localRepo.push(editHash2, repo.url(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(listAddress, email.sender());
            assertEquals(author, email.author());
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
            localRepo.push(editHash3, repo.url(), "another");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            email = conversations.get(0).first();
            assertEquals(author, email.author());
            assertEquals(listAddress, email.sender());
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
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var author = EmailAddress.from("author", "author@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .author(author)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .mode(MailingListUpdater.Mode.PR_ONLY)
                                            .headers(Map.of("extra1", "value1"))
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "edit");
            var pr = credentials.createPullRequest(repo, "master", "edit", "RFR: My PR");

            // Create a potentially conflicting one
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(otherHash, repo.url(), "other");
            var otherPr = credentials.createPullRequest(repo, "master", "other", "RFR: My other PR");

            // PR hasn't been integrated yet, so there should be no mail
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Simulate an RFR email
            var rfr = Email.create(sender, "RFR: My PR", "PR: " + pr.webUrl().toString())
                    .recipient(listAddress)
                    .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var first = conversations.get(0).first();
            var email = conversations.get(0).replies(first).get(0);
            assertEquals(listAddress, email.sender());
            assertEquals(author, email.author());
            assertEquals(email.recipients(), List.of(listAddress));
            assertEquals("[Integrated] RFR: My PR", email.subject());
            assertFalse(email.subject().contains("master"));
            assertTrue(email.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(email.body().contains("23456789: More fixes"));
            assertFalse(email.body().contains("Committer"));
            assertFalse(email.body().contains(masterHash.abbreviate()));
            assertTrue(email.hasHeader("extra1"));
            assertEquals("value1", email.headerValue("extra1"));

            // Now push the other one without a matching PR - PR_ONLY will not generate a mail
            localRepo.push(otherHash, repo.url(), "master");
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
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .mode(MailingListUpdater.Mode.PR)
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "edit");
            var pr = credentials.createPullRequest(repo, "master", "edit", "RFR: My PR");

            // Create a potentially conflicting one
            var otherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(otherHash, repo.url(), "other");
            var otherPr = credentials.createPullRequest(repo, "master", "other", "RFR: My other PR");

            // PR hasn't been integrated yet, so there should be no mail
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Simulate an RFR email
            var rfr = Email.create("RFR: My PR", "PR:\n" + pr.webUrl().toString())
                           .author(EmailAddress.from("duke", "duke@duke.duke"))
                           .recipient(listAddress)
                           .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.url(), "master");

            // Push the other one without a matching PR
            localRepo.push(otherHash, repo.url(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            assertEquals(2, conversations.size());

            var prConversation = conversations.get(0);
            var pushConversation = conversations.get(1);

            var prEmail = prConversation.replies(prConversation.first()).get(0);
            assertEquals(listAddress, prEmail.sender());
            assertEquals(EmailAddress.from("testauthor", "ta@none.none"), prEmail.author());
            assertEquals(prEmail.recipients(), List.of(listAddress));
            assertEquals("[Integrated] RFR: My PR", prEmail.subject());
            assertFalse(prEmail.subject().contains("master"));
            assertTrue(prEmail.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(prEmail.body().contains("23456789: More fixes"));
            assertFalse(prEmail.body().contains("Committer"));
            assertFalse(prEmail.body().contains(masterHash.abbreviate()));

            var pushEmail = pushConversation.first();
            assertEquals(listAddress, pushEmail.sender());
            assertEquals(EmailAddress.from("testauthor", "ta@none.none"), pushEmail.author());
            assertEquals(pushEmail.recipients(), List.of(listAddress));
            assertTrue(pushEmail.subject().contains("23456789: More fixes"));
        }
    }

    @Test
    void testMailingListPROnce(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.branch(masterHash, "other");
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .author(null)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .mode(MailingListUpdater.Mode.PR)
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master|other"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            localRepo.checkout(masterHash, true);
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "edit");
            var pr = credentials.createPullRequest(repo, "master", "edit", "RFR: My PR");

            // PR hasn't been integrated yet, so there should be no mail
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Simulate an RFR email
            var rfr = Email.create("RFR: My PR", "PR:\n" + pr.webUrl().toString())
                           .author(EmailAddress.from("duke", "duke@duke.duke"))
                           .recipient(listAddress)
                           .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.url(), "master", true);

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());

            var prConversation = conversations.get(0);
            var prEmail = prConversation.replies(prConversation.first()).get(0);
            assertEquals(listAddress, prEmail.sender());
            assertEquals(EmailAddress.from("testauthor", "ta@none.none"), prEmail.author());
            assertEquals(prEmail.recipients(), List.of(listAddress));
            assertEquals("[Integrated] RFR: My PR", prEmail.subject());
            assertFalse(prEmail.subject().contains("master"));
            assertTrue(prEmail.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(prEmail.body().contains("23456789: More fixes"));
            assertFalse(prEmail.body().contains("Committer"));
            assertFalse(prEmail.body().contains(masterHash.abbreviate()));

            // Now push the change to another monitored branch
            localRepo.push(editHash, repo.url(), "other", true);
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            // The change should now end up as a separate notification thread
            conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            assertEquals(2, conversations.size());

            var pushConversation = conversations.get(1);
            var pushEmail = pushConversation.first();
            assertEquals(listAddress, pushEmail.sender());
            assertEquals(EmailAddress.from("testauthor", "ta@none.none"), pushEmail.author());
            assertEquals(pushEmail.recipients(), List.of(listAddress));
            assertTrue(pushEmail.subject().contains("23456789: More fixes"));
        }
    }

    @Test
    void testMailinglistTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.tag(masterHash, "jdk-12+1", "Added tag 1", "Duke Tagger", "tagger@openjdk.java.net");
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewBranches(false)
                                            .headers(Map.of("extra1", "value1", "extra2", "value2"))
                                            .build();
            var prOnlyUpdater = MailingListUpdater.newBuilder()
                                                  .list(mailmanList)
                                                  .recipient(listAddress)
                                                  .sender(sender)
                                                  .reportNewTags(false)
                                                  .reportNewBranches(false)
                                                  .reportNewBuilds(false)
                                                  .mode(MailingListUpdater.Mode.PR_ONLY)
                                                  .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater, prOnlyUpdater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.fetch(repo.url(), "history:history");
            localRepo.tag(editHash, "jdk-12+2", "Added tag 2", "Duke Tagger", "tagger@openjdk.java.net");
            CheckableRepository.appendAndCommit(localRepo, "Another line 1", "34567890: Even more fixes");
            CheckableRepository.appendAndCommit(localRepo, "Another line 2", "45678901: Yet even more fixes");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Another line 3", "56789012: Still even more fixes");
            localRepo.tag(editHash2, "jdk-12+4", "Added tag 3", "Duke Tagger", "tagger@openjdk.java.net");
            CheckableRepository.appendAndCommit(localRepo, "Another line 4", "67890123: Brand new fixes");
            var editHash3 = CheckableRepository.appendAndCommit(localRepo, "Another line 5", "78901234: More brand new fixes");
            localRepo.tag(editHash3, "jdk-13+0", "Added tag 4", "Duke Tagger", "tagger@openjdk.java.net");
            localRepo.pushAll(repo.url());

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(4, conversations.size());

            for (var conversation : conversations) {
                var email = conversation.first();
                if (email.subject().equals("git: test: Added tag jdk-12+2 for changeset " + editHash.abbreviate())) {
                    assertTrue(email.body().contains("23456789: More fixes"));
                    assertFalse(email.body().contains("34567890: Even more fixes"));
                    assertFalse(email.body().contains("45678901: Yet even more fixes"));
                    assertFalse(email.body().contains("56789012: Still even more fixes"));
                    assertFalse(email.body().contains("67890123: Brand new fixes"));
                    assertFalse(email.body().contains("78901234: More brand new fixes"));
                    assertEquals(EmailAddress.from("Duke Tagger", "tagger@openjdk.java.net"), email.author());
                } else if (email.subject().equals("git: test: Added tag jdk-12+4 for changeset " + editHash2.abbreviate())) {
                    assertFalse(email.body().contains("23456789: More fixes"));
                    assertTrue(email.body().contains("34567890: Even more fixes"));
                    assertTrue(email.body().contains("45678901: Yet even more fixes"));
                    assertTrue(email.body().contains("56789012: Still even more fixes"));
                    assertFalse(email.body().contains("67890123: Brand new fixes"));
                    assertFalse(email.body().contains("78901234: More brand new fixes"));
                    assertEquals(EmailAddress.from("Duke Tagger", "tagger@openjdk.java.net"), email.author());
                } else if (email.subject().equals("git: test: Added tag jdk-13+0 for changeset " + editHash3.abbreviate())) {
                    assertFalse(email.body().contains("23456789: More fixes"));
                    assertFalse(email.body().contains("34567890: Even more fixes"));
                    assertFalse(email.body().contains("45678901: Yet even more fixes"));
                    assertFalse(email.body().contains("56789012: Still even more fixes"));
                    assertFalse(email.body().contains("67890123: Brand new fixes"));
                    assertTrue(email.body().contains("78901234: More brand new fixes"));
                    assertEquals(EmailAddress.from("Duke Tagger", "tagger@openjdk.java.net"), email.author());
                } else if (email.subject().equals("git: test: 6 new changesets")) {
                    assertTrue(email.body().contains("23456789: More fixes"));
                    assertTrue(email.body().contains("34567890: Even more fixes"));
                    assertTrue(email.body().contains("45678901: Yet even more fixes"));
                    assertTrue(email.body().contains("56789012: Still even more fixes"));
                    assertTrue(email.body().contains("67890123: Brand new fixes"));
                    assertTrue(email.body().contains("78901234: More brand new fixes"));
                    assertEquals(EmailAddress.from("testauthor", "ta@none.none"), email.author());
                } else {
                    fail("Mismatched subject: " + email.subject());
                }
                assertTrue(email.hasHeader("extra1"));
                assertEquals("value1", email.headerValue("extra1"));
                assertTrue(email.hasHeader("extra2"));
                assertEquals("value2", email.headerValue("extra2"));
            }
        }
    }

    @Test
    void testMailinglistPlainTags(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.tag(masterHash, "jdk-12+1", "Added tag 1", "Duke Tagger", "tagger@openjdk.java.net");
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .headers(Map.of("extra1", "value1", "extra2", "value2"))
                                            .build();
            var prOnlyUpdater = MailingListUpdater.newBuilder()
                                                  .list(mailmanList)
                                                  .recipient(listAddress)
                                                  .sender(sender)
                                                  .reportNewTags(false)
                                                  .reportNewBranches(false)
                                                  .reportNewBuilds(false)
                                                  .mode(MailingListUpdater.Mode.PR_ONLY)
                                                  .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater, prOnlyUpdater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.fetch(repo.url(), "history:history");
            localRepo.tag(editHash, "jdk-12+2", "Added tag 2", "Duke Tagger", "tagger@openjdk.java.net");
            CheckableRepository.appendAndCommit(localRepo, "Another line 1", "34567890: Even more fixes");
            CheckableRepository.appendAndCommit(localRepo, "Another line 2", "45678901: Yet even more fixes");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Another line 3", "56789012: Still even more fixes");
            localRepo.tag(editHash2, "jdk-12+4", "Added tag 3", "Duke Tagger", "tagger@openjdk.java.net");
            CheckableRepository.appendAndCommit(localRepo, "Another line 4", "67890123: Brand new fixes");
            var editHash3 = CheckableRepository.appendAndCommit(localRepo, "Another line 5", "78901234: More brand new fixes");
            localRepo.tag(editHash3, "jdk-13+0", "Added tag 4", "Duke Tagger", "tagger@openjdk.java.net");
            localRepo.pushAll(repo.url());

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(4, conversations.size());

            for (var conversation : conversations) {
                var email = conversation.first();
                if (email.subject().equals("git: test: Added tag jdk-12+2 for changeset " + editHash.abbreviate())) {
                    assertEquals(EmailAddress.from("Duke Tagger", "tagger@openjdk.java.net"), email.author());
                } else if (email.subject().equals("git: test: Added tag jdk-12+4 for changeset " + editHash2.abbreviate())) {
                    assertEquals(EmailAddress.from("Duke Tagger", "tagger@openjdk.java.net"), email.author());
                } else if (email.subject().equals("git: test: Added tag jdk-13+0 for changeset " + editHash3.abbreviate())) {
                    assertEquals(EmailAddress.from("Duke Tagger", "tagger@openjdk.java.net"), email.author());
                } else if (email.subject().equals("git: test: 6 new changesets")) {
                    assertEquals(EmailAddress.from("testauthor", "ta@none.none"), email.author());
                } else {
                    fail("Mismatched subject: " + email.subject());
                }
                assertTrue(email.hasHeader("extra1"));
                assertEquals("value1", email.headerValue("extra1"));
                assertTrue(email.hasHeader("extra2"));
                assertEquals("value2", email.headerValue("extra2"));
            }
        }
    }

    @Test
    void testMailingListBranch(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBuilds(false)
                                            .headers(Map.of("extra1", "value1", "extra2", "value2"))
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master|newbranch."))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            CheckableRepository.appendAndCommit(localRepo, "Another line", "12345678: Some fixes");
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "newbranch1");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            var email = conversations.get(0).first();
            assertEquals(listAddress, email.sender());
            assertEquals(EmailAddress.from("testauthor", "ta@none.none"), email.author());
            assertEquals(email.recipients(), List.of(listAddress));
            assertEquals("git: test: created branch newbranch1 based on the branch master containing 2 unique commits", email.subject());
            assertTrue(email.body().contains("12345678: Some fixes"));
            assertTrue(email.hasHeader("extra1"));
            assertEquals("value1", email.headerValue("extra1"));
            assertTrue(email.hasHeader("extra2"));
            assertEquals("value2", email.headerValue("extra2"));

            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            localRepo.push(editHash, repo.url(), "newbranch2");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var newConversation = mailmanList.conversations(Duration.ofDays(1)).stream()
                                             .filter(c -> !c.equals(conversations.get(0)))
                                             .findFirst().orElseThrow();
            email = newConversation.first();
            assertEquals(listAddress, email.sender());
            assertEquals(sender, email.author());
            assertEquals(email.recipients(), List.of(listAddress));
            assertEquals("git: test: created branch newbranch2 based on the branch newbranch1 containing 0 unique commits", email.subject());
            assertEquals("The new branch newbranch2 is currently identical to the newbranch1 branch.", email.body());
        }
    }

    @Test
    void testMailingListBranchPrefix(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .mode(MailingListUpdater.Mode.PR)
                                            .repoInSubject(true)
                                            .branchInSubject(Pattern.compile(".*"))
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "edit");
            var pr = credentials.createPullRequest(repo, "master", "edit", "RFR: My PR");

            // PR hasn't been integrated yet, so there should be no mail
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Simulate an RFR email
            var rfr = Email.create("RFR: My PR", "PR:\n" + pr.webUrl().toString())
                           .author(EmailAddress.from("duke", "duke@duke.duke"))
                           .recipient(listAddress)
                           .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.url(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            assertEquals(1, conversations.size());

            var prConversation = conversations.get(0);

            var prEmail = prConversation.replies(prConversation.first()).get(0);
            assertEquals(listAddress, prEmail.sender());
            assertEquals(EmailAddress.from("testauthor", "ta@none.none"), prEmail.author());
            assertEquals(prEmail.recipients(), List.of(listAddress));
            assertEquals("[" + repo.name() + ":master] [Integrated] RFR: My PR", prEmail.subject());
            assertTrue(prEmail.body().contains("Changeset: " + editHash.abbreviate()));
            assertTrue(prEmail.body().contains("23456789: More fixes"));
            assertFalse(prEmail.body().contains("Committer"));
            assertFalse(prEmail.body().contains(masterHash.abbreviate()));
        }
    }

    @Test
    void testMailingListNoIdempotence(TestInfo testInfo) throws IOException {
        try (var listServer = new TestMailmanServer();
             var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewTags(false)
                                            .reportNewBranches(false)
                                            .reportNewBuilds(false)
                                            .headers(Map.of("extra1", "value1", "extra2", "value2"))
                                            .allowedAuthorDomains(Pattern.compile("none"))
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // No mail should be sent on the first run as there is no history
            TestBotRunner.runPeriodicItems(notifyBot);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // Save history state
            var historyHash = localRepo.fetch(repo.url(), "history");

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());

            // Reset the history
            localRepo.push(historyHash, repo.url(), "history", true);
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            // There should now be a duplicate mail
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(2, conversations.size());
        }
    }

    @Test
    void testIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var commitIcon = URI.create("http://www.example.com/commit.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitIcon(commitIcon)
                                      .setFixVersion(true)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().userName() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // And in a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            assertEquals(commitIcon, link.iconUrl().orElseThrow());
            assertEquals("Commit", link.title().orElseThrow());
            assertEquals(repo.webUrl(editHash), link.uri().orElseThrow());

            // As well as a fixVersion
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
        }
    }

    @Test
    void testIssueNoVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var commitIcon = URI.create("http://www.example.com/commit.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitIcon(commitIcon)
                                      .setFixVersion(true)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // But not in the fixVersion
            assertEquals(Set.of(), fixVersions(issue));
        }
    }

    @Test
    void testIssueConfiguredVersionNoCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var commitIcon = URI.create("http://www.example.com/commit.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitLink(false)
                                      .commitIcon(commitIcon)
                                      .setFixVersion(true)
                                      .fixVersions(Map.of("master", "2.0"))
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should not reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // As well as a fixVersion - but not the one from the repo
            assertEquals(Set.of("2.0"), fixVersions(issue));

            // And no commit link
            var links = issue.links();
            assertEquals(0, links.size());
        }
    }

    @Test
    void testIssueIdempotence(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var commitIcon = URI.create("http://www.example.com/commit.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitIcon(commitIcon)
                                      .setFixVersion(true)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.url(), "history");

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // And in a link
            var links = issue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            assertEquals(commitIcon, link.iconUrl().orElseThrow());
            assertEquals("Commit", link.title().orElseThrow());
            assertEquals(repo.webUrl(editHash), link.uri().orElseThrow());

            // As well as a fixVersion
            assertEquals(Set.of("0.1"), fixVersions(issue));

            // Wipe the history
            localRepo.push(historyState, repo.url(), "history", true);

            // Run it again
            TestBotRunner.runPeriodicItems(notifyBot);

            // There should be no new comments, links or fixVersions
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(1, updatedIssue.comments().size());
            assertEquals(1, updatedIssue.links().size());
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
        }
    }

    @Test
    void testIssuePoolVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitLink(false)
                                      .setFixVersion(true)
                                      .fixVersions(Map.of("master", "12u14"))
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("12-pool").add("tbd13").add("unknown"));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been updated
            assertEquals(Set.of("12u14"), fixVersions(issue));
        }
    }

    @Test
    void testIssuePoolOpenVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitLink(false)
                                      .setFixVersion(true)
                                      .fixVersions(Map.of("master", "12u14"))
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("12-pool").add("tbd13").add("unknown"));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been updated
            assertEquals(Set.of("12u14"), fixVersions(issue));
        }
    }

    @Test
    void testIssueBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitLink(false)
                                      .setFixVersion(true)
                                      .fixVersions(Map.of("master", "12.0.2"))
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                                                 Map.of("issuetype", JSON.of("Enhancement"),
                                                        "customfield_10008", JSON.object()
                                                                                 .put("id", 244)
                                                                                 .put("name", "java.io"),
                                                        "customfield_10005", JSON.array()
                                                                                 .add(JSON.object()
                                                                                          .put("id", "17010")
                                                                                          .put("value", "generic"))
                                                                                 .add(JSON.object()
                                                                                          .put("id", "17019")
                                                                                          .put("value", "other"))
                                                 ));
            issue.setProperty("fixVersions", JSON.array().add("13.0.1"));
            issue.setProperty("priority", JSON.of("1"));

            var authorEmailAddress = issueProject.issueTracker().currentUser().userName() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("13.0.1"), fixVersions(updatedIssue));
            assertEquals(OPEN, updatedIssue.state());
            assertEquals(List.of(), updatedIssue.assignees());

            // There should be a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The backport issue should have a correct fixVersion and assignee
            assertEquals(Set.of("12.0.2"), fixVersions(backport));
            assertEquals(RESOLVED, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

            // Custom properties should also propagate
            assertEquals("1", backport.properties().get("priority").asString());
            assertEquals(244, backport.properties().get("customfield_10008").get("id").asInt());
            assertEquals("java.io", backport.properties().get("customfield_10008").get("name").asString());
            assertEquals(2, backport.properties().get("customfield_10005").asArray().size());
        }
    }

    @Test
    void testPullRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .prUpdaters(List.of(updater))
                                     .readyLabels(Set.of("rfr"))
                                     .readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("This is now ready")))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should not yet contain a link to the PR
            var links = issue.links();
            assertEquals(0, links.size());

            // Just a label isn't enough
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(0, links.size());

            // Neither is just a comment
            pr.removeLabel("rfr");
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(0, links.size());

            // Both are needed
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR
            links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());

            // Add another issue
            var issue2 = issueProject.createIssue("This is another issue", List.of("Yes indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            pr.setBody("\n\n### Issues\n * [" + issue.id() + "](http://www.test.test/): The issue\n * [" + issue2.id() +
                               "](http://www.test2.test/): The second issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Both issues should contain a link to the PR
            var links1 = issue.links();
            assertEquals(1, links1.size());
            assertEquals(pr.webUrl(), links1.get(0).uri().orElseThrow());
            var links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());

            // Drop the first one
            pr.setBody("\n\n### Issues\n * [" + issue2.id() + "](http://www.test2.test/): That other issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Only the second issue should now contain a link to the PR
            links1 = issue.links();
            assertEquals(0, links1.size());
            links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());
        }
    }

    @Test
    void testPullRequestNoReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .prUpdaters(List.of(updater)).readyLabels(Set.of("rfr"))
                                     .readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("This is now ready")))
                                     .build();
            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Add required label
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // And the required comment
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should still not contain a link to the PR
            var links = issue.links();
            assertEquals(0, links.size());
        }
    }

    @Test
    void testPullRequestPROnly(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .prOnly(true)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile(".*"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .prUpdaters(List.of(updater))
                                     .build();

            // Initialize history
            localRepo.push(localRepo.resolve("master").orElseThrow(), repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "other", "edit", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR
            var links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());

            // Simulate integration
            localRepo.push(editHash, repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // Now simulate a merge to another branch
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // No additional comment should have been made
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            comments = updatedIssue.comments();
            assertEquals(1, comments.size());
        }
    }
}
