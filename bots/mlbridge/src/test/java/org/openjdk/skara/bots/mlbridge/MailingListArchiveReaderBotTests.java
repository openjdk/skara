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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MailingListArchiveReaderBotTests {
    private void addReply(Conversation conversation, EmailAddress recipient, MailingList mailingList, PullRequest pr, String reply) {
        var first = conversation.first();
        var references = first.id().toString();
        var email = Email.create(EmailAddress.from("Commenter", "c@test.test"), "Re: RFR: " + pr.title(), reply)
                         .recipient(recipient)
                         .id(EmailAddress.from(UUID.randomUUID() + "@id.id"))
                         .header("In-Reply-To", first.id().toString())
                         .header("References", references)
                         .build();
        mailingList.post(email);
    }

    private void addReply(Conversation conversation, EmailAddress recipient, MailingList mailingList, PullRequest pr) {
        addReply(conversation, recipient, mailingList, pr, "Looks good");
    }

    @Test
    void simpleArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var readerBot = new MailingListArchiveReaderBot(from, Set.of(mailmanList), Set.of(archive));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Run an archive pass
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // Post a reply directly to the list
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            addReply(conversations.get(0), listAddress, mailmanList, pr);
            listServer.processIncoming();

            // Another archive reader pass - has to be done twice
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // The bridge should now have processed the reply
            var updated = pr.comments();
            assertEquals(2, updated.size());
            assertTrue(updated.get(1).body().contains("Mailing list message from"));
            assertTrue(updated.get(1).body().contains("[Commenter](mailto:c@test.test)"));
            assertTrue(updated.get(1).body().contains("[test](mailto:test@" + listAddress.domain() + ")"));
        }
    }

    @Test
    void rememberBridged(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .repoInSubject(true)
                                            .build();

            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var readerBot = new MailingListArchiveReaderBot(from, Set.of(mailmanList), Set.of(archive));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Post a reply directly to the list
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            addReply(conversations.get(0), listAddress, mailmanList, pr);
            listServer.processIncoming();

            // Another archive reader pass - has to be done twice
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // The bridge should now have processed the reply
            var updated = pr.comments();
            assertEquals(2, updated.size());

            var newReaderBot = new MailingListArchiveReaderBot(from, Set.of(mailmanList), Set.of(archive));
            TestBotRunner.runPeriodicItems(newReaderBot);
            TestBotRunner.runPeriodicItems(newReaderBot);

            // The new bridge should not have made duplicate posts
            var notUpdated = pr.comments();
            assertEquals(2, notUpdated.size());
        }
    }

    @Test
    void largeEmail(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var readerBot = new MailingListArchiveReaderBot(from, Set.of(mailmanList), Set.of(archive));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Run an archive pass
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // Post a large reply directly to the list
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());

            var replyBody = "This line is about 30 bytes long\n".repeat(1000 * 10);
            addReply(conversations.get(0), listAddress, mailmanList, pr, replyBody);
            listServer.processIncoming();

            // Another archive reader pass - has to be done twice
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // The bridge should now have processed the reply
            var updated = pr.comments();
            assertEquals(2, updated.size());
            assertTrue(updated.get(1).body().contains("Mailing list message from"));
            assertTrue(updated.get(1).body().contains("[Commenter](mailto:c@test.test)"));
            assertTrue(updated.get(1).body().contains("[test](mailto:test@" + listAddress.domain() + ")"));
            assertTrue(updated.get(1).body().contains("This message was too large"));
        }
    }
}
