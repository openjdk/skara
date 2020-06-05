/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.*;
import org.openjdk.skara.bots.notify.mailinglist.MailingListUpdater;
import org.openjdk.skara.email.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.notify.UpdaterTests.*;

public class MailingListUpdaterTests {
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            assertTrue(email.hasHeader("X-Git-URL"));
            assertEquals(repo.webUrl().toString(), email.headerValue("X-Git-URL"));
            assertTrue(email.hasHeader("X-Git-Changeset"));
            assertEquals(editHash.hex(), email.headerValue("X-Git-Changeset"));
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            assertTrue(email.hasHeader("X-Git-URL"));
            assertEquals(repo.webUrl().toString(), email.headerValue("X-Git-URL"));
            assertTrue(email.hasHeader("X-Git-Changeset"));
            assertEquals(editHash1.hex(), email.headerValue("X-Git-Changeset"));
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            assertTrue(email.hasHeader("X-Git-URL"));
            assertEquals(repo.webUrl().toString(), email.headerValue("X-Git-URL"));
            assertTrue(email.hasHeader("X-Git-Changeset"));
            assertEquals(editHash1.hex(), email.headerValue("X-Git-Changeset"));

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
            assertTrue(email.hasHeader("X-Git-URL"));
            assertEquals(repo.webUrl().toString(), email.headerValue("X-Git-URL"));
            assertTrue(email.hasHeader("X-Git-Changeset"));
            assertEquals(editHash3.hex(), email.headerValue("X-Git-Changeset"));
        }
    }

    @Test
    void testMailingListPROnlyMultipleBranches(TestInfo testInfo) throws IOException {
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                            .includeBranch(true)
                                            .mode(MailingListUpdater.Mode.PR)
                                            .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master|other"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Populate our known branches
            localRepo.push(masterHash, repo.url(), "master", true);
            localRepo.push(masterHash, repo.url(), "other", true);

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
            var rfr = Email.create(sender, "RFR: My PR", "PR: " + pr.webUrl().toString())
                           .recipient(listAddress)
                           .build();
            mailmanList.post(rfr);
            listServer.processIncoming();

            // And an integration (but it hasn't reached master just yet)
            pr.addComment("Pushed as commit " + editHash.hex() + ".");

            // Now push the same commit to another branch
            localRepo.push(editHash, repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);
            listServer.processIncoming();

            // This one should generate a plain integration mail
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(2, conversations.size());
            var secondEmail = conversations.get(0).first();
            if (secondEmail.subject().contains("RFR")) {
                secondEmail = conversations.get(1).first();
            }
            assertEquals("git: test: other: 23456789: More fixes", secondEmail.subject());
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            var rfr = Email.create("[repo/branch] RFR: My PR", "PR:\n" + pr.webUrl().toString())
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

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            conversations.sort(Comparator.comparing(conversation -> conversation.first().subject()));
            assertEquals(2, conversations.size());

            var prConversation = conversations.get(0);
            var pushConversation = conversations.get(1);
            assertEquals(1, prConversation.allMessages().size());

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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());

            var prConversation = conversations.get(0);
            assertEquals(1, prConversation.allMessages().size());

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
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var sender = EmailAddress.from("duke", "duke@duke.duke");
            var updater = MailingListUpdater.newBuilder()
                                            .list(mailmanList)
                                            .recipient(listAddress)
                                            .sender(sender)
                                            .reportNewBranches(false)
                                            .headers(Map.of("extra1", "value1", "extra2", "value2"))
                                            .build();
            var noTagsUpdater = MailingListUpdater.newBuilder()
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
                                     .prStateStorageBuilder(prStateStorage)
                                     .updaters(List.of(updater, noTagsUpdater))
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
            var noTagsUpdater = MailingListUpdater.newBuilder()
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
                                     .prStateStorageBuilder(prStateStorage)
                                     .updaters(List.of(updater, noTagsUpdater))
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
            var prStateStorage = createPullRequestStateStorage(repo);
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
                                     .prStateStorageBuilder(prStateStorage)
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
}
