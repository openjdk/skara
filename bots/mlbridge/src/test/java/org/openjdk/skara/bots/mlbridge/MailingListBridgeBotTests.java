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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.host.Review;
import org.openjdk.skara.host.network.URIBuilder;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MailingListBridgeBotTests {
    private boolean archiveContains(Path archive, String text) {
        return archiveContainsCount(archive, text) > 0;
    }

    private int archiveContainsCount(Path archive, String text) {
        try {
            var mbox = Files.find(archive, 50, (path, attrs) -> path.toString().endsWith(".mbox")).findAny();
            if (mbox.isEmpty()) {
                return 0;
            }
            var lines = Files.readString(mbox.get(), StandardCharsets.UTF_8);
            var pattern = Pattern.compile(text);
            int count = 0;
            for (var line : lines.split("\\R")) {
                var matcher = pattern.matcher(line);
                if (matcher.find()) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean webrevContains(Path webrev, String text) {
        try {
            var index = Files.find(webrev, 5, (path, attrs) -> path.toString().endsWith("index.html")).findAny();
            if (index.isEmpty()) {
                return false;
            }
            var lines = Files.readString(index.get(), StandardCharsets.UTF_8);
            return lines.contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    private long countSubstrings(String string, String substring) {
        return Pattern.compile(substring).matcher(string).results().count();
    }

    @Test
    void simpleArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master", listAddress,
                                                 Set.of(ignored.host().getCurrentUserDetails().userName()),
                                                 Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of("rfr"), Map.of(ignored.host().getCurrentUserDetails().userName(),
                                                                       Pattern.compile("ready")));

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This should not be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Flag it as ready for review
            pr.setBody("This should now be ready");
            pr.addLabel("rfr");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // But it should still not be archived
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a general comment - not a ready marker
            var ignoredPr = ignored.getPullRequest(pr.getId());
            ignoredPr.addComment("hello there");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // It should still not be archived
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a ready comment
            ignoredPr.addComment("ready");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This should now be ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch:"));
            assertTrue(archiveContains(archiveFolder.path(), "Pull request:"));
            assertTrue(archiveContains(archiveFolder.path(), "Webrev:"));
            assertTrue(archiveContains(archiveFolder.path(), "http://www.test.test/"));
            assertTrue(archiveContains(archiveFolder.path(), "webrev.00"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch command:"));
            assertTrue(archiveContains(archiveFolder.path(), "^ - " + editHash.abbreviate() + ":\tChange msg"));
            assertTrue(archiveContains(archiveFolder.path(), "^\t\t\tWith several lines"));

            // The mailing list as well
            listServer.processIncoming();
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(pr.getAuthor().fullName() + " via " + pr.repository().getUrl().getHost(), mail.author().fullName().orElseThrow());
            assertEquals(from.address(), mail.author().address());
            assertEquals(from, mail.sender());

            // And there should be a webrev
            Repository.materialize(webrevFolder.path(), archive.getUrl(), "webrev");
            assertTrue(webrevContains(webrevFolder.path(), "1 lines changed"));
            var comments = pr.getComments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.host().getCurrentUserDetails()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Add a comment
            pr.addComment("This is a comment :smile:");

            // Add a comment from an ignored user as well
            ignoredPr.addComment("Don't mind me");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain the comment, but not the ignored one
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a comment"));
            assertTrue(archiveContains(archiveFolder.path(), "> This should now be ready"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(2, conversations.get(0).allMessages().size());

            // Remove the rfr flag and post another comment
            pr.addLabel("rfr");
            pr.addComment("This is another comment");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain the additional comment
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is another comment"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This should now be ready"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(from, newMail.sender());
            }
            assertTrue(conversations.get(0).allMessages().get(2).body().contains("This is a comment 😄"));
        }
    }

    @Test
    void reviewComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master", listAddress,
                                                 Set.of(ignored.host().getCurrentUserDetails().userName()),
                                                 Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // And make a file specific comment
            var currentMaster = localRepo.resolve("master").orElseThrow();
            var comment = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");

            // Add one from an ignored user as well
            var ignoredPr = ignored.getPullRequest(pr.getId());
            ignoredPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Don't mind me");

            // Process comments
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Review comment"));
            assertTrue(archiveContains(archiveFolder.path(), "> This is now ready"));
            assertTrue(archiveContains(archiveFolder.path(), reviewFile.toString()));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));

            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());

            // Comment on the comment
            pr.addReviewCommentReply(comment, "This is a review reply");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain the additional comment
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a review reply"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This is now ready"));

            // As well as the mailing list
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(from, newMail.sender());
            }
        }
    }

    @Test
    void combineComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(),
                                                 listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make two file specific comments
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Another review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a combined entry
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // As well as the mailing list
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(2, conversations.get(0).allMessages().size());

            var reply = conversations.get(0).replies(mail).get(0);
            assertEquals(2, reply.body().split("^On.*wrote:").length);
            assertEquals(2, reply.body().split("> This is now ready").length, reply.body());
            assertEquals("Re: RFR: This is a pull request", reply.subject());
            assertTrue(reply.body().contains("Review comment\n\n"), reply.body());
            assertTrue(reply.body().contains("Another review comment"), reply.body());
        }
    }

    @Test
    void commentThreading(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id())
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(),
                                                 listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a file specific comment
            var reviewPr = reviewer.getPullRequest(pr.getId());
            var comment1 = reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            pr.addReviewCommentReply(comment1, "I agree");
            reviewPr.addReviewCommentReply(comment1, "Great");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            // And a second one by ourselves
            var comment2 = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Another review comment");
            reviewPr.addReviewCommentReply(comment2, "Sounds good");
            pr.addReviewCommentReply(comment2, "Thanks");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            // Sanity check the archive
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertEquals(6, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // Check the mailing list
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(7, conversations.get(0).allMessages().size());

            // There should be two separate threads
            var thread1 = conversations.get(0).replies(mail).get(0);
            assertEquals(2, thread1.body().split("^On.*wrote:").length);
            assertEquals(2, thread1.body().split("> This is now ready").length, thread1.body());
            assertEquals("Re: RFR: This is a pull request", thread1.subject());
            assertTrue(thread1.body().contains("Review comment\n\n"), thread1.body());
            assertFalse(thread1.body().contains("Another review comment"), thread1.body());
            var thread1reply1 = conversations.get(0).replies(thread1).get(0);
            assertTrue(thread1reply1.body().contains("I agree"));
            var thread1reply2 = conversations.get(0).replies(thread1reply1).get(0);
            assertTrue(thread1reply2.body().contains("Great"));

            var thread2 = conversations.get(0).replies(mail).get(1);
            assertEquals(2, thread2.body().split("^On.*wrote:").length);
            assertEquals(2, thread2.body().split("> This is now ready").length, thread2.body());
            assertEquals("Re: RFR: This is a pull request", thread2.subject());
            assertFalse(thread2.body().contains("Review comment\n\n"), thread2.body());
            assertTrue(thread2.body().contains("Another review comment"), thread2.body());
            var thread2reply1 = conversations.get(0).replies(thread2).get(0);
            assertTrue(thread2reply1.body().contains("Sounds good"));
            var thread2reply2 = conversations.get(0).replies(thread2reply1).get(0);
            assertTrue(thread2reply2.body().contains("Thanks"));
        }
    }

    @Test
    void reviewContext(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(),
                                                 listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a file specific comment
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should only contain context around line 2
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "^> 2: Line 1$"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 3: Line 2$"));
            assertFalse(archiveContains(archiveFolder.path(), "^> 4: Line 3$"));
        }
    }

    @Test
    void multipleReviewContexts(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(),
                                                 listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);
            var initialHash = CheckableRepository.appendAndCommit(localRepo,
                                                                  "Line 0.1\nLine 0.2\nLine 0.3\nLine 0.4\n" +
                                                                          "Line 1\nLine 2\nLine 3\nLine 4\n" +
                                                                          "Line 5\nLine 6\nLine 7\nLine 8\n" +
                                                                          "Line 8.1\nLine 8.2\nLine 8.3\nLine 8.4\n" +
                                                                          "Line 9\nLine 10\nLine 11\nLine 12\n" +
                                                                          "Line 13\nLine 14\nLine 15\nLine 16\n");
            localRepo.push(initialHash, author.getUrl(), "master");

            // Make a change with a corresponding PR
            var current = Files.readString(localRepo.root().resolve(reviewFile), StandardCharsets.UTF_8);
            var updated = current.replaceAll("Line 2", "Line 2 edit\nLine 2.5");
            updated = updated.replaceAll("Line 13", "Line 12.5\nLine 13 edit");
            Files.writeString(localRepo.root().resolve(reviewFile), updated, StandardCharsets.UTF_8);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make file specific comments
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 7, "Review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 24, "Another review comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should only contain context around line 2 and 20
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "reviewfile.txt line 7"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 6: Line 1$"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 7: Line 2 edit$"));
            assertFalse(archiveContains(archiveFolder.path(), "Line 3"));

            assertTrue(archiveContains(archiveFolder.path(), "reviewfile.txt line 24"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 23: Line 12.5$"));
            assertTrue(archiveContains(archiveFolder.path(), "^> 24: Line 13 edit$"));
            assertFalse(archiveContains(archiveFolder.path(), "^Line 15"));
        }
    }

    @Test
    void filterComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready\n<!-- this is a comment -->\nAnd this is not\n" +
                               "<!-- Anything below this marker will be hidden -->\nStatus stuff");

            // Make a bunch of comments
            pr.addComment("Plain comment\n<!-- this is a comment -->");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment <!-- this is a comment -->\n");
            pr.addComment("/integrate stuff");
            TestBotRunner.runPeriodicItems(mlBot);

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should not contain the comment
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "this is a comment"));
            assertFalse(archiveContains(archiveFolder.path(), "Status stuff"));
            assertTrue(archiveContains(archiveFolder.path(), "And this is not"));
            assertFalse(archiveContains(archiveFolder.path(), "<!--"));
            assertFalse(archiveContains(archiveFolder.path(), "-->"));
            assertTrue(archiveContains(archiveFolder.path(), "Plain comment"));
            assertTrue(archiveContains(archiveFolder.path(), "Review comment"));
            assertFalse(archiveContains(archiveFolder.path(), "/integrate"));
        }
    }

    @Test
    void incrementalChanges(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            var nextHash = CheckableRepository.appendAndCommit(localRepo, "Yet one more line", "Fixing");
            localRepo.push(nextHash, author.getUrl(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.getHeadHash();
            var refreshCount = 0;
            do {
                pr = author.getPullRequest(pr.getId());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.getHeadHash().equals(lastHeadHash));

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the updated push
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "additional changes"));
            assertTrue(archiveContains(archiveFolder.path(), "full.*/" + pr.getId() + "/webrev.01"));
            assertTrue(archiveContains(archiveFolder.path(), "inc.*/" + pr.getId() + "/webrev.00-01"));
            assertTrue(archiveContains(archiveFolder.path(), "Updated full patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fixing"));

            // The webrev comment should be updated
            var comments = pr.getComments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.host().getCurrentUserDetails()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(nextHash.hex()))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(from, newMail.sender());
            }

            // Ensure that additional updates are only reported once
            for (int i = 0; i < 3; ++i) {
                var anotherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fixing");
                localRepo.push(anotherHash, author.getUrl(), "edit");

                // Make sure that the push registered
                lastHeadHash = pr.getHeadHash();
                refreshCount = 0;
                do {
                    pr = author.getPullRequest(pr.getId());
                    if (refreshCount++ > 100) {
                        fail("The PR did not update after the new push");
                    }
                } while (pr.getHeadHash().equals(lastHeadHash));

                TestBotRunner.runPeriodicItems(mlBot);
                TestBotRunner.runPeriodicItems(mlBot);
                listServer.processIncoming();
            }
            var updatedConversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, updatedConversations.size());
            var conversation = updatedConversations.get(0);
            assertEquals(5, conversation.allMessages().size());
        }
    }

    @Test
    void rebased(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("first"), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line", "Original msg");
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            var newLocalRepo = Repository.materialize(tempFolder.path().resolve("second"), author.getUrl(), "master");
            var newEditHash = CheckableRepository.appendAndCommit(newLocalRepo, "Another line", "Replaced msg");
            newLocalRepo.push(newEditHash, author.getUrl(), "edit", true);

            // Make sure that the push registered
            var lastHeadHash = pr.getHeadHash();
            var refreshCount = 0;
            do {
                pr = author.getPullRequest(pr.getId());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.getHeadHash().equals(lastHeadHash));

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the rebased push
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "complete new set of changes"));
            assertTrue(archiveContains(archiveFolder.path(), pr.getId() + "/webrev.01"));
            assertFalse(archiveContains(archiveFolder.path(), "Incremental"));
            assertTrue(archiveContains(archiveFolder.path(), "Updated full patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Original msg"));
            assertTrue(archiveContains(archiveFolder.path(), "Replaced msg"));

            // The webrev comment should be updated
            var comments = pr.getComments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.host().getCurrentUserDetails()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(newEditHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP());
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(from.address(), newMail.author().address());
                assertEquals(from, newMail.sender());
            }
        }
    }

    @Test
    void skipAddingExistingWebrev(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress,
                                                 Set.of(ignored.host().getCurrentUserDetails().userName()),
                                                 Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");

            // Flag it as ready for review
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            var archiveRepo = Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), editHash.abbreviate()));

            // And there should be a webrev comment
            var comments = pr.getComments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.host().getCurrentUserDetails()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());
            assertEquals(1, countSubstrings(webrevComments.get(0).body(), "webrev.00"));

            // Pretend the archive didn't work out
            archiveRepo.push(masterHash, archive.getUrl(), "master", true);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The webrev comment should not contain duplicate entries
            comments = pr.getComments();
            webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.host().getCurrentUserDetails()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());
            assertEquals(1, countSubstrings(webrevComments.get(0).body(), "webrev.00"));
        }
    }

    @Test
    void notifyReviewVerdicts(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var from = EmailAddress.from("test", "test@test.mail");
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.host().getCurrentUserDetails().id())
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress, Set.of(), Set.of(),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // First unapprove it
            var reviewedPr = reviewer.getPullRequest(pr.getId());
            reviewedPr.addReview(Review.Verdict.DISAPPROVED, "Reason 1");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain a note
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Review status set to Disapproved"));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), " by integrationreviewer1"));
            if (author.host().supportsReviewBody()) {
                assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 1"));
            }

            // Then approve it
            reviewedPr.addReview(Review.Verdict.APPROVED, "Reason 2");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain another note
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Review status set to Approved"));
            if (author.host().supportsReviewBody()) {
                assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 2"));
            }
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "This PR has been marked as Reviewed by integrationreviewer1."));

            // Yet another change
            reviewedPr.addReview(Review.Verdict.DISAPPROVED, "Reason 3");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain another note
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "Review status set to Disapproved"));
            if (author.host().supportsReviewBody()) {
                assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 3"));
            }
        }
    }

    @Test
    void ignoreComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer()) {
            var author = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = new MailingListBridgeBot(from, author, archive, censusBuilder.build(), "master",
                                                 listAddress,
                                                 Set.of(ignored.host().getCurrentUserDetails().userName()),
                                                 Set.of(Pattern.compile("ignore this comment", Pattern.MULTILINE | Pattern.DOTALL)),
                                                 listServer.getArchive(), listServer.getSMTP(),
                                                 archive, "webrev", Path.of("test"),
                                                 URIBuilder.base("http://www.test.test/").build(),
                                                 Set.of(), Map.of());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);
            localRepo.push(masterHash, archive.getUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Make a bunch of comments
            pr.addComment("Plain comment");
            pr.addComment("ignore this comment");
            pr.addComment("I think it is time to\nignore this comment!");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review ignore this comment");

            var ignoredPR = ignored.getPullRequest(pr.getId());
            ignoredPR.addComment("Don't mind me");

            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should not contain the ignored comments
            Repository.materialize(archiveFolder.path(), archive.getUrl(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "ignore this comment"));
            assertFalse(archiveContains(archiveFolder.path(), "it is time to"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));
            assertFalse(archiveContains(archiveFolder.path(), "Review ignore"));
        }
    }
}
