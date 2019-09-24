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
package org.openjdk.skara.mailinglist;

import org.openjdk.skara.email.*;
import org.openjdk.skara.test.TemporaryDirectory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MboxTests {
    @Test
    void simple() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");

            var sender = EmailAddress.from("test", "test@test.mail");
            var sentMail = Email.create(sender, "Subject", "Message").build();
            list.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentMail, conversation.first());
        }
    }

    @Test
    void multiple() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");

            var sender1 = EmailAddress.from("test1", "test1@test.mail");
            var sender2 = EmailAddress.from("test2", "test2@test.mail");

            var sentParent = Email.create(sender1, "Subject 1", "Message 1").build();
            list.post(sentParent);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());

            var sentReply = Email.create(sender2, "Subject 2", "Message 2")
                                 .header("In-Reply-To", sentParent.id().toString())
                                 .header("References", sentParent.id().toString())
                                 .build();
            list.post(sentReply);
            conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentParent, conversation.first());
            var replies = conversation.replies(sentParent);
            assertEquals(1, replies.size());
            var reply = replies.get(0);
            assertEquals(sentReply, reply);
        }
    }

    @Test
    void uninitialized() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(0, conversations.size());
        }
    }

    @Test
    void nested() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("this/is/a/nested/path/test");

            var sender = EmailAddress.from("test", "test@test.mail");
            var sentMail = Email.create(sender, "Subject", "Message").build();
            list.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentMail, conversation.first());
        }
    }

    @Test
    void differentAuthor() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");

            var sender = EmailAddress.from("test1", "test1@test.mail");
            var author = EmailAddress.from("test2", "test2@test.mail");
            var sentMail = Email.create(author, "Subject", "Message").sender(sender).build();
            list.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentMail, conversation.first());
        }
    }

    @Test
    void encodedFrom() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");

            var sender = EmailAddress.from("test", "test@test.mail");
            var sentMail = Email.create(sender, "Subject", "From is an odd way to start\n" +
                    "From may also be the second row\n" +
                    ">>From as a quote\n" +
                    "And From in the middle").build();
            list.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentMail, conversation.first());
        }
    }

    @Test
    void utf8Encode() {
        try (var folder = new TemporaryDirectory()) {
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");

            var sender = EmailAddress.from("têßt", "test@test.mail");
            var sentMail = Email.create(sender, "Sübjeçt", "(╯°□°)╯︵ ┻━┻").build();
            list.post(sentMail);
            var conversations = list.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentMail, conversation.first());
        }
    }

    @Test
    void unencodedFrom() throws IOException {
        try (var folder = new TemporaryDirectory()) {
            var rawMbox = folder.path().resolve("test.mbox");
            Files.writeString(rawMbox,
                              "From test at example.com  Wed Aug 21 17:22:50 2019\n" +
                                      "From: test at example.com (test at example.com)\n" +
                                      "Date: Wed, 21 Aug 2019 17:22:50 +0000\n" +
                                      "Subject: this is a test\n" +
                                      "Message-ID: <abc123@example.com>\n" +
                                      "\n" +
                                      "Sometimes there are unencoded from lines as well\n" +
                                      "\n" +
                                      "From this point onwards, it may be hard to parse this\n" +
                                      "\n", StandardCharsets.UTF_8);
            var mbox = MailingListServerFactory.createMboxFileServer(folder.path());
            var list = mbox.getList("test");
            var conversations = list.conversations(Duration.ofDays(365 * 100));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(1, conversation.allMessages().size());
            assertTrue(conversation.first().body().contains("there are unencoded"), conversation.first().body());
            assertTrue(conversation.first().body().contains("this point onwards"), conversation.first().body());
        }
    }
}
