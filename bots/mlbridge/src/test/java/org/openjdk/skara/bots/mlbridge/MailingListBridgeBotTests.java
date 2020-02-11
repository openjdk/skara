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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MailingListBridgeBotTests {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge.test");

    private Optional<String> archiveContents(Path archive) {
        try {
            var mbox = Files.find(archive, 50, (path, attrs) -> path.toString().endsWith(".mbox")).findAny();
            if (mbox.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(mbox.get(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }

    }

    private boolean archiveContains(Path archive, String text) {
        return archiveContainsCount(archive, text) > 0;
    }

    private int archiveContainsCount(Path archive, String text) {
        var lines = archiveContents(archive);
        if (lines.isEmpty()) {
            return 0;
        }
        var pattern = Pattern.compile(text);
        int count = 0;
        for (var line : lines.get().split("\\R")) {
            var matcher = pattern.matcher(line);
            if (matcher.find()) {
                count++;
            }
        }
        return count;
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

    private String noreplyAddress(HostedRepository repository) {
        return "test+" + repository.forge().currentUser().id() + "+" +
                repository.forge().currentUser().userName() +
                "@openjdk.java.net";
    }

    @Test
    void simpleArchive(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
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
                                            .list(listAddress)
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .ignoredComments(Set.of())
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .readyLabels(Set.of("rfr"))
                                            .readyComments(Map.of(ignored.forge().currentUser().userName(), Pattern.compile("ready")))
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .headers(Map.of("Extra1", "val1", "Extra2", "val2"))
                                            .sendInterval(Duration.ZERO)
                                            .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A simple change",
                                                               "Change msg\n\nWith several lines");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "1234: This is a pull request");
            pr.setBody("This should not be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // A PR that isn't ready for review should not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Flag it as ready for review
            pr.setBody("This should now be ready");
            pr.addLabel("rfr");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // But it should still not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a general comment - not a ready marker
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.addComment("hello there");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // It should still not be archived
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertFalse(archiveContains(archiveFolder.path(), "This is a pull request"));

            // Now post a ready comment
            ignoredPr.addComment("ready");

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This should now be ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch:"));
            assertTrue(archiveContains(archiveFolder.path(), "Changes:"));
            assertTrue(archiveContains(archiveFolder.path(), "Webrev:"));
            assertTrue(archiveContains(archiveFolder.path(), webrevServer.uri().toString()));
            assertTrue(archiveContains(archiveFolder.path(), "webrev.00"));
            assertTrue(archiveContains(archiveFolder.path(), "Issue:"));
            assertTrue(archiveContains(archiveFolder.path(), "http://issues.test/browse/TSTPRJ-1234"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch:"));
            assertTrue(archiveContains(archiveFolder.path(), "^ - " + editHash.abbreviate() + ": Change msg"));
            assertFalse(archiveContains(archiveFolder.path(), "With several lines"));

            // The mailing list as well
            listServer.processIncoming();
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: 1234: This is a pull request", mail.subject());
            assertEquals(pr.author().fullName(), mail.author().fullName().orElseThrow());
            assertEquals(noreplyAddress(archive), mail.author().address());
            assertEquals(listAddress, mail.sender());
            assertEquals("val1", mail.headerValue("Extra1"));
            assertEquals("val2", mail.headerValue("Extra2"));

            // And there should be a webrev
            Repository.materialize(webrevFolder.path(), archive.url(), "webrev");
            assertTrue(webrevContains(webrevFolder.path(), "1 lines changed"));
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
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
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
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
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is another comment"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This should now be ready"));

            listServer.processIncoming();
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(noreplyAddress(archive), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }
            assertTrue(conversations.get(0).allMessages().get(2).body().contains("This is a comment 😄"));
        }
    }

    @Test
    void reviewComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
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
                                            .list(listAddress)
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // And make a file specific comment
            var currentMaster = localRepo.resolve("master").orElseThrow();
            var comment = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");

            // Add one from an ignored user as well
            var ignoredPr = ignored.pullRequest(pr.id());
            ignoredPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Don't mind me");

            // Process comments
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should now contain an entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a pull request"));
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertTrue(archiveContains(archiveFolder.path(), "Review comment"));
            assertTrue(archiveContains(archiveFolder.path(), "> This is now ready"));
            assertTrue(archiveContains(archiveFolder.path(), reviewFile.toString()));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));

            // The mailing list as well
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());

            // Comment on the comment
            pr.addReviewCommentReply(comment, "This is a review reply");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain the additional comment (but no quoted footers)
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is a review reply"));
            assertTrue(archiveContains(archiveFolder.path(), ">> This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "^> PR:"));

            // As well as the mailing list
            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            assertEquals(3, conversations.get(0).allMessages().size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(noreplyAddress(archive), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }
        }
    }

    @Test
    void combineComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            pr.addComment("Avoid combining");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();

            // Make several file specific comments
            var first = pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Another review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Further review comment");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Final review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a combined entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // As well as the mailing list
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(3, conversations.get(0).allMessages().size());

            var commentReply = conversations.get(0).replies(mail).get(0);
            assertEquals(2, commentReply.body().split("^On.*wrote:").length);
            assertTrue(commentReply.body().contains("Avoid combining\n\n"), commentReply.body());

            var reviewReply = conversations.get(0).replies(mail).get(1);
            assertEquals(2, reviewReply.body().split("^On.*wrote:").length);
            assertEquals(2, reviewReply.body().split("> This is now ready").length, reviewReply.body());
            assertEquals("RFR: This is a pull request", reviewReply.subject());
            assertTrue(reviewReply.body().contains("Review comment\n\n"), reviewReply.body());
            assertTrue(reviewReply.body().contains("Another review comment"), reviewReply.body());

            // Now reply to the first (collapsed) comment
            pr.addReviewCommentReply(first, "I agree");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should contain a new entry
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(3, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // The combined review comments should only appear unquoted once
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "^Another review comment"));
        }
    }

    @Test
    void commentThreading(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a file specific comment
            var reviewPr = reviewer.pullRequest(pr.id());
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

            // Finally some approvals and another comment
            pr.addReview(Review.Verdict.APPROVED, "Nice");
            reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "The final review comment");
            reviewPr.addReview(Review.Verdict.APPROVED, "Looks fine");
            reviewPr.addReviewCommentReply(comment2, "You are welcome");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();
            listServer.processIncoming();
            listServer.processIncoming();

            // Sanity check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(9, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));

            // File specific comments should appear after the approval
            var archiveText = archiveContents(archiveFolder.path()).orElseThrow();
            assertTrue(archiveText.indexOf("Looks fine") < archiveText.indexOf("The final review comment"));

            // Check the mailing list
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var mail = conversations.get(0).first();
            assertEquals("RFR: This is a pull request", mail.subject());
            assertEquals(10, conversations.get(0).allMessages().size());

            // There should be four separate threads
            var thread1 = conversations.get(0).replies(mail).get(0);
            assertEquals(2, thread1.body().split("^On.*wrote:").length);
            assertEquals(2, thread1.body().split("> This is now ready").length, thread1.body());
            assertEquals("RFR: This is a pull request", thread1.subject());
            assertTrue(thread1.body().contains("Review comment\n\n"), thread1.body());
            assertFalse(thread1.body().contains("Another review comment"), thread1.body());
            var thread1reply1 = conversations.get(0).replies(thread1).get(0);
            assertTrue(thread1reply1.body().contains("I agree"));
            assertEquals(noreplyAddress(archive), thread1reply1.author().address());
            assertEquals(archive.forge().currentUser().fullName(), thread1reply1.author().fullName().orElseThrow());
            var thread1reply2 = conversations.get(0).replies(thread1reply1).get(0);
            assertTrue(thread1reply2.body().contains("Great"));
            assertEquals("integrationreviewer1@openjdk.java.net", thread1reply2.author().address());
            assertEquals("Generated Reviewer 1", thread1reply2.author().fullName().orElseThrow());

            var thread2 = conversations.get(0).replies(mail).get(1);
            assertEquals(2, thread2.body().split("^On.*wrote:").length);
            assertEquals(2, thread2.body().split("> This is now ready").length, thread2.body());
            assertEquals("RFR: This is a pull request", thread2.subject());
            assertFalse(thread2.body().contains("Review comment\n\n"), thread2.body());
            assertTrue(thread2.body().contains("Another review comment"), thread2.body());
            var thread2reply1 = conversations.get(0).replies(thread2).get(0);
            assertTrue(thread2reply1.body().contains("Sounds good"));
            var thread2reply2 = conversations.get(0).replies(thread2reply1).get(0);
            assertTrue(thread2reply2.body().contains("Thanks"));

            var replies = conversations.get(0).replies(mail);
            var thread3 = replies.get(2);
            assertEquals("RFR: This is a pull request", thread3.subject());
            var thread4 = replies.get(3);
            assertEquals("RFR: This is a pull request", thread4.subject());
            assertTrue(thread4.body().contains("Looks fine"));
            assertTrue(thread4.body().contains("The final review comment"));
            assertTrue(thread4.body().contains("Marked as reviewed by integrationreviewer1 (Reviewer)"));
        }
    }

    @Test
    void commentThreadingSeparated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make two file specific comments
            var reviewPr = reviewer.pullRequest(pr.id());
            var comment1 = reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");
            var comment2 = reviewPr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Another review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addReviewCommentReply(comment1, "I agree");
            pr.addReviewCommentReply(comment2, "I don't agree");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Sanity check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(3, archiveContainsCount(archiveFolder.path(), "^On.*wrote:"));
        }
    }

    @Test
    void reviewContext(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a file specific comment
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review comment");

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should only contain context around line 2
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
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
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);
            var initialHash = CheckableRepository.appendAndCommit(localRepo,
                                                                  "Line 0.1\nLine 0.2\nLine 0.3\nLine 0.4\n" +
                                                                          "Line 1\nLine 2\nLine 3\nLine 4\n" +
                                                                          "Line 5\nLine 6\nLine 7\nLine 8\n" +
                                                                          "Line 8.1\nLine 8.2\nLine 8.3\nLine 8.4\n" +
                                                                          "Line 9\nLine 10\nLine 11\nLine 12\n" +
                                                                          "Line 13\nLine 14\nLine 15\nLine 16\n");
            localRepo.push(initialHash, author.url(), "master");

            // Make a change with a corresponding PR
            var current = Files.readString(localRepo.root().resolve(reviewFile), StandardCharsets.UTF_8);
            var updated = current.replaceAll("Line 2", "Line 2 edit\nLine 2.5");
            updated = updated.replaceAll("Line 13", "Line 12.5\nLine 13 edit");
            Files.writeString(localRepo.root().resolve(reviewFile), updated, StandardCharsets.UTF_8);
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
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
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
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
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
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
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
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
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var commenter = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            var nextHash = CheckableRepository.appendAndCommit(localRepo, "Yet one more line", "Fixing");
            localRepo.push(nextHash, author.url(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the updated push
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "1 additional commit"));
            assertTrue(archiveContains(archiveFolder.path(), "full.*/" + pr.id() + "/webrev.01"));
            assertTrue(archiveContains(archiveFolder.path(), "inc.*/" + pr.id() + "/webrev.00-01"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fixing"));

            // The webrev comment should be updated
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains("Full"))
                                         .filter(comment -> comment.body().contains("Incremental"))
                                         .filter(comment -> comment.body().contains(nextHash.hex()))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(noreplyAddress(archive), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
            }

            // Add a comment
            var commenterPr = commenter.pullRequest(pr.id());
            commenterPr.addReviewComment(masterHash, nextHash, reviewFile.toString(), 2, "Review comment");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Ensure that additional updates are only reported once
            for (int i = 0; i < 3; ++i) {
                var anotherHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fixing");
                localRepo.push(anotherHash, author.url(), "edit");

                // Make sure that the push registered
                lastHeadHash = pr.headHash();
                refreshCount = 0;
                do {
                    pr = author.pullRequest(pr.id());
                    if (refreshCount++ > 100) {
                        fail("The PR did not update after the new push");
                    }
                } while (pr.headHash().equals(lastHeadHash));

                TestBotRunner.runPeriodicItems(mlBot);
                TestBotRunner.runPeriodicItems(mlBot);
                listServer.processIncoming();
            }
            var updatedConversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, updatedConversations.size());
            var conversation = updatedConversations.get(0);
            assertEquals(6, conversation.allMessages().size());
            assertEquals("[Rev 01] RFR: This is a pull request", conversation.allMessages().get(1).subject());
            assertEquals("[Rev 01] RFR: This is a pull request", conversation.allMessages().get(2).subject(), conversation.allMessages().get(2).toString());
            assertEquals("[Rev 04] RFR: This is a pull request", conversation.allMessages().get(5).subject());
        }
    }

    @Test
    void rebased(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var sender = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(sender)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("first"), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line", "Original msg");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            var newLocalRepo = Repository.materialize(tempFolder.path().resolve("second"), author.url(), "master");
            var newEditHash = CheckableRepository.appendAndCommit(newLocalRepo, "Another line", "Replaced msg");
            newLocalRepo.push(newEditHash, author.url(), "edit", true);

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the rebased push
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "updated with a new target base"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/webrev.01"));
            assertFalse(archiveContains(archiveFolder.path(), "Incremental"));
            assertTrue(archiveContains(archiveFolder.path(), "Patch"));
            assertTrue(archiveContains(archiveFolder.path(), "Fetch"));
            assertTrue(archiveContains(archiveFolder.path(), "Original msg"));
            assertTrue(archiveContains(archiveFolder.path(), "Replaced msg"));

            // The webrev comment should be updated
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(newEditHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());

            // Check that sender address is set properly
            var mailmanServer = MailingListServerFactory.createMailmanServer(listServer.getArchive(), listServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress.address());
            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            for (var newMail : conversations.get(0).allMessages()) {
                assertEquals(noreplyAddress(archive), newMail.author().address());
                assertEquals(listAddress, newMail.sender());
                assertFalse(newMail.hasHeader("PR-Head-Hash"));
            }
            assertEquals("[Rev 01] RFR: This is a pull request", conversations.get(0).allMessages().get(1).subject());
        }
    }

    @Test
    void incrementalAfterRebase(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var sender = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(sender)
                                            .repo(author)
                                            .archive(archive)
                                            .archiveRef("archive")
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path().resolve("first"), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);
            localRepo.push(masterHash, archive.url(), "archive", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "A line", "Original msg");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Push more stuff to master
            localRepo.checkout(masterHash, true);
            var unrelatedFile = localRepo.root().resolve("unrelated.txt");
            Files.writeString(unrelatedFile, "Other things happens in master");
            localRepo.add(unrelatedFile);
            var newMasterHash = localRepo.commit("Unrelated change", "duke", "duke@openjdk.org");
            localRepo.push(newMasterHash, author.url(), "master");

            // And more stuff to the pr branch
            localRepo.checkout(editHash, true);
            CheckableRepository.appendAndCommit(localRepo, "Another line", "More updates");

            // Merge master
            localRepo.merge(newMasterHash);
            var newEditHash = localRepo.commit("Latest changes from master", "duke", "duke@openjdk.org");
            localRepo.push(newEditHash, author.url(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The archive should reference the rebased push
            Repository.materialize(archiveFolder.path(), archive.url(), "archive");
            assertTrue(archiveContains(archiveFolder.path(), "updated with a new target base"));
            assertTrue(archiveContains(archiveFolder.path(), "excludes the unrelated changes"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/webrev.01"));
            assertTrue(archiveContains(archiveFolder.path(), pr.id() + "/webrev.00-01"));
            assertTrue(archiveContains(archiveFolder.path(), "Original msg"));
            assertTrue(archiveContains(archiveFolder.path(), "More updates"));
        }
    }

    @Test
    void skipAddingExistingWebrev(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory();
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
                                            .list(listAddress)
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

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

            // Flag it as ready for review
            pr.setBody("This should now be ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should now contain an entry
            var archiveRepo = Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), editHash.abbreviate()));

            // And there should be a webrev comment
            var comments = pr.comments();
            var webrevComments = comments.stream()
                                         .filter(comment -> comment.author().equals(author.forge().currentUser()))
                                         .filter(comment -> comment.body().contains("webrev"))
                                         .filter(comment -> comment.body().contains(editHash.hex()))
                                         .collect(Collectors.toList());
            assertEquals(1, webrevComments.size());
            assertEquals(1, countSubstrings(webrevComments.get(0).body(), "webrev.00"));

            // Pretend the archive didn't work out
            archiveRepo.push(masterHash, archive.url(), "master", true);

            // Run another archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // The webrev comment should not contain duplicate entries
            comments = pr.comments();
            webrevComments = comments.stream()
                                     .filter(comment -> comment.author().equals(author.forge().currentUser()))
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
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var from = EmailAddress.from("test", "test@test.mail");
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Run an archive pass
            TestBotRunner.runPeriodicItems(mlBot);

            // First unapprove it
            var reviewedPr = reviewer.pullRequest(pr.id());
            reviewedPr.addReview(Review.Verdict.DISAPPROVED, "Reason 1");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain a note
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Changes requested by "));
            assertEquals(1, archiveContainsCount(archiveFolder.path(), " by integrationreviewer1"));
            if (author.forge().supportsReviewBody()) {
                assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 1"));
            }

            // Then approve it
            reviewedPr.addReview(Review.Verdict.APPROVED, "Reason 2");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain another note
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "Marked as reviewed by "));
            if (author.forge().supportsReviewBody()) {
                assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 2"));
            }
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "Re: RFR:"));

            // Yet another change
            reviewedPr.addReview(Review.Verdict.DISAPPROVED, "Reason 3");
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should contain another note
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(2, archiveContainsCount(archiveFolder.path(), "Changes requested by "));
            if (author.forge().supportsReviewBody()) {
                assertEquals(1, archiveContainsCount(archiveFolder.path(), "Reason 3"));
            }
        }
    }

    @Test
    void ignoreComments(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var ignored = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .ignoredUsers(Set.of(ignored.forge().currentUser().userName()))
                                            .ignoredComments(Set.of(Pattern.compile("ignore this comment", Pattern.MULTILINE | Pattern.DOTALL)))
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            // Make a bunch of comments
            pr.addComment("Plain comment");
            pr.addComment("ignore this comment");
            pr.addComment("I think it is time to\nignore this comment!");
            pr.addReviewComment(masterHash, editHash, reviewFile.toString(), 2, "Review ignore this comment");

            var ignoredPR = ignored.pullRequest(pr.id());
            ignoredPR.addComment("Don't mind me");

            TestBotRunner.runPeriodicItems(mlBot);
            TestBotRunner.runPeriodicItems(mlBot);

            // The archive should not contain the ignored comments
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "This is now ready"));
            assertFalse(archiveContains(archiveFolder.path(), "ignore this comment"));
            assertFalse(archiveContains(archiveFolder.path(), "it is time to"));
            assertFalse(archiveContains(archiveFolder.path(), "Don't mind me"));
            assertFalse(archiveContains(archiveFolder.path(), "Review ignore"));
        }
    }

    @Test
    void replyToEmptyReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(reviewer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBot = MailingListBridgeBot.newBuilder()
                                            .from(from)
                                            .repo(author)
                                            .archive(archive)
                                            .censusRepo(censusBuilder.build())
                                            .list(listAddress)
                                            .listArchive(listServer.getArchive())
                                            .smtpServer(listServer.getSMTP())
                                            .webrevStorageRepository(archive)
                                            .webrevStorageRef("webrev")
                                            .webrevStorageBase(Path.of("test"))
                                            .webrevStorageBaseUri(webrevServer.uri())
                                            .issueTracker(URIBuilder.base("http://issues.test/browse/").build())
                                            .build();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make an empty approval
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addReview(Review.Verdict.APPROVED, "");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            pr.addComment("Thanks for the review!");
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // The approval text should be included in the quote
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertEquals(1, archiveContainsCount(archiveFolder.path(), "^> Marked as reviewed"));
        }
    }

    @Test
    void cooldown(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().userName()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .list(listAddress)
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.build();
            var mlBotWithCooldown = mlBotBuilder.cooldown(Duration.ofDays(1)).build();

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a comment
            pr.addComment("Looks good");

            // Bot with cooldown configured should not bridge the comment
            TestBotRunner.runPeriodicItems(mlBotWithCooldown);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // But without, it should
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Looks good"));
        }
    }

    @Test
    void cooldownNewRevision(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().userName()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .list(listAddress)
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.build();
            var mlBotWithCooldown = mlBotBuilder.cooldown(Duration.ofDays(1)).build();

            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Commit another revision
            var updatedHash = CheckableRepository.appendAndCommit(localRepo, "More stuff");
            localRepo.push(updatedHash, author.url(), "edit");

            // Bot with cooldown configured should not create a new webrev
            TestBotRunner.runPeriodicItems(mlBotWithCooldown);
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // But without, it should
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "webrev.01"));
        }
    }

    @Test
    void retryAfterCooldown(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var cooldown = Duration.ofMillis(500);
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().userName()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .list(listAddress)
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.cooldown(cooldown).build();
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a comment and run the check within the cooldown period
            int counter;
            for (counter = 1; counter < 10; ++counter) {
                var start = Instant.now();
                pr.addComment("Looks good - " + counter + " -");

                // The bot should not bridge the comment due to cooldown
                TestBotRunner.runPeriodicItems(mlBot);
                var elapsed = Duration.between(start, Instant.now());
                if (elapsed.compareTo(cooldown) < 0) {
                    break;
                } else {
                    log.info("Didn't do the test in time - retrying (elapsed: " + elapsed + " required: " + cooldown + ")");
                    // Make sure to run the bot once more after the cooldown has expired to reset the state
                    Thread.sleep(cooldown.toMillis());
                    TestBotRunner.runPeriodicItems(mlBot);
                    listServer.processIncoming();
                    cooldown = cooldown.multipliedBy(2);
                    mlBot = mlBotBuilder.cooldown(cooldown).build();
                }
            }
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // But after the cooldown period has passed, it should
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Looks good - " + counter + " -"));
        }
    }

    @Test
    void retryNewRevisionAfterCooldown(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var archiveFolder = new TemporaryDirectory();
             var listServer = new TestMailmanServer();
             var webrevServer = new TestWebrevServer()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();
            var listAddress = EmailAddress.parse(listServer.createList("test"));
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var from = EmailAddress.from("test", "test@test.mail");
            var cooldown = Duration.ofMillis(500);
            var mlBotBuilder = MailingListBridgeBot.newBuilder()
                                                   .from(from)
                                                   .repo(bot)
                                                   .ignoredUsers(Set.of(bot.forge().currentUser().userName()))
                                                   .archive(archive)
                                                   .censusRepo(censusBuilder.build())
                                                   .list(listAddress)
                                                   .listArchive(listServer.getArchive())
                                                   .smtpServer(listServer.getSMTP())
                                                   .webrevStorageRepository(archive)
                                                   .webrevStorageRef("webrev")
                                                   .webrevStorageBase(Path.of("test"))
                                                   .webrevStorageBaseUri(webrevServer.uri())
                                                   .issueTracker(URIBuilder.base("http://issues.test/browse/").build());

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Line 1\nLine 2\nLine 3\nLine 4");
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.setBody("This is now ready");

            var mlBot = mlBotBuilder.cooldown(cooldown).build();
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Make a new revision and run the check within the cooldown period
            int counter;
            for (counter = 1; counter < 10; ++counter) {
                var start = Instant.now();
                var revHash = CheckableRepository.appendAndCommit(localRepo, "more stuff", "Update number - " + counter + " -");
                localRepo.push(revHash, author.url(), "edit");

                // The bot should not bridge the new revision due to cooldown
                TestBotRunner.runPeriodicItems(mlBot);
                var elapsed = Duration.between(start, Instant.now());
                if (elapsed.compareTo(cooldown) < 0) {
                    break;
                } else {
                    log.info("Didn't do the test in time - retrying (elapsed: " + elapsed + " required: " + cooldown + ")");
                    // Make sure to run the bot once more after the cooldown has expired to reset the state
                    Thread.sleep(cooldown.toMillis());
                    TestBotRunner.runPeriodicItems(mlBot);
                    listServer.processIncoming();
                    cooldown = cooldown.multipliedBy(2);
                    mlBot = mlBotBuilder.cooldown(cooldown).build();
                }
            }
            assertThrows(RuntimeException.class, () -> listServer.processIncoming(Duration.ofMillis(1)));

            // But after the cooldown period has passed, it should
            Thread.sleep(cooldown.toMillis());
            TestBotRunner.runPeriodicItems(mlBot);
            listServer.processIncoming();

            // Check the archive
            Repository.materialize(archiveFolder.path(), archive.url(), "master");
            assertTrue(archiveContains(archiveFolder.path(), "Update number - " + counter + " -"));
        }
    }
}
