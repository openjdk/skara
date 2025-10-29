/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Issue;
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
    private void addReply(Conversation conversation, EmailAddress recipient, MailingListServer mailingListServer, PullRequest pr, String reply) {
        var first = conversation.first();
        var references = first.id().toString();
        var email = Email.create(EmailAddress.from("Commenter", "c@test.test"), "Re: RFR: " + pr.title(), reply)
                         .recipient(recipient)
                         .id(EmailAddress.from(UUID.randomUUID() + "@id.id"))
                         .header("In-Reply-To", first.id().toString())
                         .header("References", references)
                         .build();
        mailingListServer.post(email);
    }

    private void addReply(Conversation conversation, EmailAddress recipient, MailingListServer mailingListServer, PullRequest pr) {
        addReply(conversation, recipient, mailingListServer, pr, "Looks good");
    }

    @Test
    void simpleArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = TestMailmanServer.createV3();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mailmanServer = MailingListServerFactory.createMailman3Server(listServer.getArchive(),
                    listServer.getSMTP(), Duration.ZERO);
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .mailingListServer(mailmanServer)
                                            .build();

            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var readerBot = new MailingListArchiveReaderBot(mailmanList, archive);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
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
            addReply(conversations.get(0), listAddress, mailmanServer, pr);
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
             var listServer = TestMailmanServer.createV3();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mailmanServer = MailingListServerFactory.createMailman3Server(listServer.getArchive(),
                    listServer.getSMTP(), Duration.ZERO);
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .repoInSubject(true)
                                            .mailingListServer(mailmanServer)
                                            .build();

            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var readerBot = new MailingListArchiveReaderBot(mailmanList, archive);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Post a reply directly to the list
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            addReply(conversations.get(0), listAddress, mailmanServer, pr);
            listServer.processIncoming();

            // Another archive reader pass - has to be done twice
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // The bridge should now have processed the reply
            var updated = pr.comments();
            assertEquals(2, updated.size());

            var newReaderBot = new MailingListArchiveReaderBot(mailmanList, archive);
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
             var listServer = TestMailmanServer.createV3();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailman3Server(listServer.getArchive(),
                    listServer.getSMTP(), Duration.ZERO);
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                                            .webrevStorageHTMLRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .mailingListServer(mailmanServer)
                                            .build();

            var mailmanList = mailmanServer.getListReader(listAddress.address());
            var readerBot = new MailingListArchiveReaderBot(mailmanList, archive);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
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
            addReply(conversations.get(0), listAddress, mailmanServer, pr, replyBody);
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

    /**
     * Verify that we don't throw exceptions if the target branch of a PR is missing after
     * being closed.
     */
    @Test
    void branchMissing(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = TestMailmanServer.createV3();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mailmanServer = MailingListServerFactory.createMailman3Server(listServer.getArchive(),
                    listServer.getSMTP(), Duration.ZERO);
            var mlBot = MailingListBridgeBot.newBuilder()
                    .from(from)
                    .repo(author)
                    .archive(archive)
                    .censusRepo(censusBuilder.build())
                    .lists(List.of(new MailingListConfiguration(listAddress, Set.of())))
                    .ignoredUsers(Set.of(ignored.forge().currentUser().username()))
                    .webrevStorageHTMLRepository(archive)
                    .webrevStorageRef("webrev")
                    .webrevStorageBase(Path.of("test"))
                    .webrevStorageBaseUri(webrevServer.uri())
                    .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                    .mailingListServer(mailmanServer)
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, author.authenticatedUrl(), "to_be_deleted", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                    "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "to_be_deleted", "edit", "This is a pull request");
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Delete the branch and close the PR
            author.deleteBranch("to_be_deleted");
            pr.setState(Issue.State.CLOSED);

            TestBotRunner.runPeriodicItems(mlBot);
        }
    }
}
