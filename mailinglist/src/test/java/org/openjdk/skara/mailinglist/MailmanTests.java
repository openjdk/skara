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
import org.openjdk.skara.test.TestMailmanServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MailmanTests {
    @Test
    void simple() throws IOException {
        try (var testServer = new TestMailmanServer()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var mail = Email.create(sender, "Subject", "Body")
                            .recipient(EmailAddress.parse(listAddress))
                            .build();
            mailmanList.post(mail);
            testServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(mail, conversation.first());
        }
    }

    @Test
    void replies() throws IOException {
        try (var testServer = new TestMailmanServer()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var sentParent = Email.create(sender, "Subject", "Body")
                                  .recipient(EmailAddress.parse(listAddress))
                                  .build();
            mailmanList.post(sentParent);
            testServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(sentParent, conversation.first());

            var replier = EmailAddress.from("Replier", "replier@test.email");
            var sentReply = Email.create(replier, "Reply subject", "Reply body")
                                 .recipient(EmailAddress.parse(listAddress))
                                 .header("In-Reply-To", sentParent.id().toString())
                                 .build();
            mailmanList.post(sentReply);
            testServer.processIncoming();

            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            conversation = conversations.get(0);
            assertEquals(sentParent, conversation.first());

            var replies = conversation.replies(conversation.first());
            assertEquals(1, replies.size());
            var reply = replies.get(0);
            assertEquals(sentReply, reply);
        }
    }

    @Test
    void cached() throws IOException {
        try (var testServer = new TestMailmanServer()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getList(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var mail = Email.create(sender, "Subject", "Body")
                            .recipient(EmailAddress.parse(listAddress))
                            .build();
            mailmanList.post(mail);
            testServer.processIncoming();

            {
                var conversations = mailmanList.conversations(Duration.ofDays(1));
                assertEquals(1, conversations.size());
                var conversation = conversations.get(0);
                assertEquals(mail, conversation.first());
                assertFalse(testServer.lastResponseCached());
            }
            {
                var conversations = mailmanList.conversations(Duration.ofDays(1));
                assertEquals(1, conversations.size());
                var conversation = conversations.get(0);
                assertEquals(mail, conversation.first());
                assertTrue(testServer.lastResponseCached());
            }
        }
    }

    @Test
    void interval() throws IOException {
        try (var testServer = new TestMailmanServer()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ofDays(1));
            var mailmanList = mailmanServer.getList(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var mail1 = Email.create(sender, "Subject 1", "Body 1")
                            .recipient(EmailAddress.parse(listAddress))
                            .build();
            var mail2 = Email.create(sender, "Subject 2", "Body 2")
                             .recipient(EmailAddress.parse(listAddress))
                             .build();
            new Thread(() -> {
                mailmanList.post(mail1);
                mailmanList.post(mail2);
            }).start();
            testServer.processIncoming();
            assertThrows(RuntimeException.class, () -> testServer.processIncoming(Duration.ZERO));

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(mail1, conversation.first());
        }
    }
}
