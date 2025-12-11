/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.mailinglist;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.email.*;
import org.openjdk.skara.test.TemporaryDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MboxTests {
    @Test
    void simple() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var listName = EmailAddress.parse("test@mbox");
            var list = mbox.getListReader(listName);

            var sender = EmailAddress.from("test", "test@test.mail");
            var sentMail = Email.create(sender, "Subject", "Message")
                                .recipient(EmailAddress.from("test@mbox"))
                                .build();
            var expectedMail = Email.from(sentMail)
                                    .sender(listName)
                                    .build();
            mbox.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedMail, conversation.first());
        }
    }

    @Test
    void multiple() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var listName = EmailAddress.parse("test@mbox");
            var list = mbox.getListReader(listName);

            var sender1 = EmailAddress.from("test1", "test1@test.mail");
            var sender2 = EmailAddress.from("test2", "test2@test.mail");

            var sentParent = Email.create(sender1, "Subject 1", "Message 1")
                                  .recipient(EmailAddress.from("test@mbox"))
                                  .build();
            var expectedParent = Email.from(sentParent)
                                      .sender(listName)
                                      .build();
            mbox.post(sentParent);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());

            var sentReply = Email.create(sender2, "Subject 2", "Message 2")
                                 .recipient(EmailAddress.from("test@mbox"))
                                 .header("In-Reply-To", sentParent.id().toString())
                                 .header("References", sentParent.id().toString())
                                 .build();
            var expectedReply = Email.from(sentReply)
                                     .sender(listName)
                                     .build();
            mbox.post(sentReply);
            conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedParent, conversation.first());
            var replies = conversation.replies(expectedParent);
            assertEquals(1, replies.size());
            var reply = replies.get(0);
            assertEquals(expectedReply, reply);
        }
    }

    @Test
    void uninitialized() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.from("test@mbox"));
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(0, conversations.size());
        }
    }

    @Test
    void differentAuthor() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var listName = EmailAddress.parse("test@mbox");
            var list = mbox.getListReader(listName);

            var sender = EmailAddress.from("test1", "test1@test.mail");
            var author = EmailAddress.from("test2", "test2@test.mail");
            var sentMail = Email.create(author, "Subject", "Message")
                                .recipient(EmailAddress.from("test@mbox"))
                                .sender(sender)
                                .build();
            var expectedMail = Email.from(sentMail)
                                    .sender(listName)
                                    .build();
            mbox.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedMail, conversation.first());
        }
    }

    @Test
    void encodedFrom() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var listName = EmailAddress.parse("test@mbox");
            var list = mbox.getListReader(listName);

            var sender = EmailAddress.from("test", "test@test.mail");
            var sentMail = Email.create(sender, "Subject", """
                                    From is an odd way to start
                                    From may also be the second row
                                    >>From as a quote
                                    And From in the middle""")
                                .recipient(EmailAddress.from("test@mbox"))
                                .build();
            var expectedMail = Email.from(sentMail)
                                    .sender(listName)
                                    .build();
            mbox.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedMail, conversation.first());
        }
    }

    @Test
    void utf8Encode() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var listName = EmailAddress.parse("test@mbox");
            var list = mbox.getListReader(listName);

            var sender = EmailAddress.from("têßt", "test@test.mail");
            var sentMail = Email.create(sender, "Sübjeçt", "(╯°□°)╯︵ ┻━┻")
                                .recipient(EmailAddress.from("test@mbox"))
                                .build();
            var expectedMail = Email.from(sentMail)
                                    .sender(listName)
                                    .build();
            mbox.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedMail, conversation.first());
        }
    }

    @Test
    void unencodedFrom() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox = folder.path().resolve("test.mbox");
            Files.writeString(rawMbox, """
                                      From test at example.com  Wed Aug 21 17:22:50 2019
                                      From: test at example.com (test at example.com)
                                      Date: Wed, 21 Aug 2019 17:22:50 +0000
                                      Subject: this is a test
                                      Message-ID: <abc123@example.com>

                                      Sometimes there are unencoded from lines as well

                                      From this point onwards, it may be hard to parse this
                                      """);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.parse("test@mbox"));
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(1, conversation.allMessages().size());
            assertTrue(conversation.first().body().contains("there are unencoded"), conversation.first().body());
            assertTrue(conversation.first().body().contains("this point onwards"), conversation.first().body());
        }
    }

    @Test
    void replyToWithExtra() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox = folder.path().resolve("test.mbox");
            Files.writeString(rawMbox, """
                                      From test at example.com  Wed Aug 21 17:22:50 2019
                                      From: test at example.com (test at example.com)
                                      Date: Wed, 21 Aug 2019 17:22:50 +0000
                                      Subject: this is a test
                                      Message-ID: <abc123@example.com>

                                      First message

                                      From test2 at example.com  Wed Aug 21 17:32:50 2019
                                      From: test2 at example.com (test2 at example.com)
                                      Date: Wed, 21 Aug 2019 17:32:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <abc123@example.com> (This be a reply)
                                      Message-ID: <def456@example.com>

                                      Second message
                                      """);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.parse("test@mbox"));
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(2, conversation.allMessages().size());
        }
    }

    @Test
    void replyOutOfOrder() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox = folder.path().resolve("test.mbox");
            Files.writeString(rawMbox, """
                                      From test at example.com  Wed Aug 21 17:22:50 2019
                                      From: test at example.com (test at example.com)
                                      Date: Wed, 21 Aug 2019 17:22:50 +0000
                                      Subject: this is a test
                                      Message-ID: <abc123@example.com>

                                      First message

                                      From test3 at example.com  Wed Aug 21 17:42:50 2019
                                      From: test3 at example.com (test3 at example.com)
                                      Date: Wed, 21 Aug 2019 17:42:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <def456@example.com>
                                      Message-ID: <ghi789@example.com>

                                      Third message

                                      From test2 at example.com  Wed Aug 21 17:32:50 2019
                                      From: test2 at example.com (test2 at example.com)
                                      Date: Wed, 21 Aug 2019 17:32:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <abc123@example.com> (This be a reply)
                                      Message-ID: <def456@example.com>

                                      Second message
                                      """);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.parse("test@mbox"));
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(3, conversation.allMessages().size());
        }
    }

    @Test
    void replyCross() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox1 = folder.path().resolve("test1.mbox");
            Files.writeString(rawMbox1, """
                                      From test at example.com  Wed Aug 21 17:22:50 2019
                                      From: test at example.com (test at example.com)
                                      Date: Wed, 21 Aug 2019 17:22:50 +0000
                                      Subject: this is a test
                                      Message-ID: <abc123@example.com>

                                      First message

                                      From test2 at example.com  Wed Aug 21 17:32:50 2019
                                      From: test2 at example.com (test2 at example.com)
                                      Date: Wed, 21 Aug 2019 17:32:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <abc123@example.com> (This be a reply)
                                      Message-ID: <def456@example.com>

                                      Second message

                                      From test3 at example.com  Wed Aug 21 17:42:50 2019
                                      From: test3 at example.com (test3 at example.com)
                                      Date: Wed, 21 Aug 2019 17:42:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <def456@example.com>
                                      Message-ID: <ghi789@example.com>

                                      Third message
                                      """);
            var rawMbox2 = folder.path().resolve("test2.mbox");
            Files.writeString(rawMbox2, """
                                      From test3 at example.com  Wed Aug 21 17:42:50 2019
                                      From: test3 at example.com (test3 at example.com)
                                      Date: Wed, 21 Aug 2019 17:42:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <def456@example.com>
                                      Message-ID: <ghi789@example.com>

                                      Third message

                                      From test2 at example.com  Wed Aug 21 17:32:50 2019
                                      From: test2 at example.com (test2 at example.com)
                                      Date: Wed, 21 Aug 2019 17:32:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <abc123@example.com> (This be a reply)
                                      Message-ID: <def456@example.com>

                                      Second message
                                      """);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.parse("test1@mbox"), EmailAddress.parse("test2@mbox"));
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(3, conversation.allMessages().size());
        }
    }

    @Test
    void replyOutOfOrderSplit() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox1 = folder.path().resolve("test1.mbox");
            Files.writeString(rawMbox1, """
                                      From test at example.com  Wed Aug 21 17:22:50 2019
                                      From: test at example.com (test at example.com)
                                      Date: Wed, 21 Aug 2019 17:22:50 +0000
                                      Subject: this is a test
                                      Message-ID: <abc123@example.com>

                                      First message

                                      From test3 at example.com  Wed Aug 21 17:42:50 2019
                                      From: test3 at example.com (test3 at example.com)
                                      Date: Wed, 21 Aug 2019 17:42:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <def456@example.com>
                                      Message-ID: <ghi789@example.com>

                                      Third message
                                      """);
            var rawMbox2 = folder.path().resolve("test2.mbox");
            Files.writeString(rawMbox2, """
                                      From test2 at example.com  Wed Aug 21 17:32:50 2019
                                      From: test2 at example.com (test2 at example.com)
                                      Date: Wed, 21 Aug 2019 17:32:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <abc123@example.com> (This be a reply)
                                      Message-ID: <def456@example.com>

                                      Second message
                                      """);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.parse("test1@mbox"), EmailAddress.parse("test2@mbox"));
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(3, conversation.allMessages().size());
        }
    }

    /**
     * Tests that fallback on References field works when In-Reply-To points to a non
     * existing email.
     */
    @Test
    void middleMessageMissing() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox1 = folder.path().resolve("test1.mbox");
            Files.writeString(rawMbox1, """
                                      From test at example.com  Wed Aug 21 17:22:50 2019
                                      From: test at example.com (test at example.com)
                                      Date: Wed, 21 Aug 2019 17:22:50 +0000
                                      Subject: this is a test
                                      Message-ID: <abc123@example.com>

                                      First message

                                      From test3 at example.com  Wed Aug 21 17:42:50 2019
                                      From: test3 at example.com (test3 at example.com)
                                      Date: Wed, 21 Aug 2019 17:42:50 +0000
                                      Subject: Re: this is a test
                                      In-Reply-To: <def456@example.com>
                                      References: <foo999@example.com>
                                        <abc123@example.com>
                                        <def456@example.com>
                                      Message-ID: <ghi789@example.com>

                                      Third message
                                      """);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getListReader(EmailAddress.parse("test1@mbox"));
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(2, conversation.allMessages().size());
        }
    }
}
