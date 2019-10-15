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
import org.openjdk.skara.host.PullRequest;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailingListArchiveReaderBotTests {
    private void addReply(Conversation conversation, MailingList mailingList, PullRequest pr) {
        var first = conversation.first();

        var reply = "Looks good";
        var references = first.id().toString();
        var email = Email.create(EmailAddress.from("Commenter", "<c@test.test>"), "Re: RFR: " + pr.title(), reply)
                         .recipient(first.author())
                         .id(EmailAddress.from(UUID.randomUUID() + "@id.id"))
                         .header("In-Reply-To", first.id().toString())
                         .header("References", references)
                         .build();
        mailingList.post(email);
    }

    @Test
    void simpleArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress,
                                                 Set.of(ignored.host().currentUser().userName()),
                                                 Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of(),
                                                 URIBuilder.base("http://issues.test/browse/").build(),
                                                 Map.of(), Duration.ZERO);

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
            addReply(conversations.get(0), mailmanList, pr);
            listServer.processIncoming();

            // Another archive reader pass - has to be done twice
            TestBotRunner.runPeriodicItems(readerBot);
            TestBotRunner.runPeriodicItems(readerBot);

            // The bridge should now have processed the reply
            var updated = pr.comments();
            assertEquals(2, updated.size());
        }
    }

    @Test
    void rememberBridged(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress,
                                                 Set.of(ignored.host().currentUser().userName()),
                                                 Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of(),
                                                 URIBuilder.base("http://issues.test/browse/").build(),
                                                 Map.of(), Duration.ZERO);

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
            addReply(conversations.get(0), mailmanList, pr);
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
}
