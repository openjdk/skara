/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.email.Email;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.mailinglist.mailman.Mailman3Server;
import org.openjdk.skara.test.TestMailmanServer;

import static org.junit.jupiter.api.Assertions.*;

class Mailman3Tests {
    @Test
    void simple() throws IOException {
        try (var testServer = TestMailmanServer.createV3()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailman3Server(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var mail = Email.create(sender, "Subject", "Body")
                            .recipient(EmailAddress.parse(listAddress))
                            .build();
            mailmanServer.post(mail);
            var expectedMail = Email.from(mail)
                                    .sender(EmailAddress.parse(listAddress))
                                    .build();

            testServer.processIncoming();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedMail, conversation.first());
        }
    }

    @Test
    void replies() throws IOException {
        try (var testServer = TestMailmanServer.createV3()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailman3Server(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var sentParent = Email.create(sender, "Subject", "Body")
                                  .recipient(EmailAddress.parse(listAddress))
                                  .build();
            mailmanServer.post(sentParent);
            testServer.processIncoming();
            var expectedParent = Email.from(sentParent)
                                      .sender(EmailAddress.parse(listAddress))
                                      .build();

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedParent, conversation.first());

            var replier = EmailAddress.from("Replier", "replier@test.email");
            var sentReply = Email.create(replier, "Reply subject", "Reply body")
                                 .recipient(EmailAddress.parse(listAddress))
                                 .header("In-Reply-To", sentParent.id().toString())
                                 .build();
            mailmanServer.post(sentReply);
            var expectedReply = Email.from(sentReply)
                                     .sender(EmailAddress.parse(listAddress))
                                     .build();

            testServer.processIncoming();

            conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            conversation = conversations.get(0);
            assertEquals(expectedParent, conversation.first());

            var replies = conversation.replies(conversation.first());
            assertEquals(1, replies.size());
            var reply = replies.get(0);
            assertEquals(expectedReply, reply);
        }
    }

    @Test
    void interval() throws IOException {
        try (var testServer = TestMailmanServer.createV3()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailman3Server(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ofDays(1));
            var mailmanList = mailmanServer.getListReader(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var mail1 = Email.create(sender, "Subject 1", "Body 1")
                             .recipient(EmailAddress.parse(listAddress))
                             .build();
            var mail2 = Email.create(sender, "Subject 2", "Body 2")
                             .recipient(EmailAddress.parse(listAddress))
                             .build();
            new Thread(() -> {
                mailmanServer.post(mail1);
                mailmanServer.post(mail2);
            }).start();
            var expectedMail = Email.from(mail1)
                                    .sender(EmailAddress.parse(listAddress))
                                    .build();

            testServer.processIncoming();
            assertThrows(RuntimeException.class, () -> testServer.processIncoming(Duration.ZERO));

            var conversations = mailmanList.conversations(Duration.ofDays(1));
            assertEquals(1, conversations.size());
            var conversation = conversations.get(0);
            assertEquals(expectedMail, conversation.first());
        }
    }

    @Test
    void poll3days() throws Exception {
        try (var testServer = TestMailmanServer.createV3()) {
            var listAddress = testServer.createList("test");
            var now = ZonedDateTime.now();
            var mailmanServer = new Mailman3Server(testServer.getArchive(),
                    testServer.getSMTP(), Duration.ZERO, now.minusDays(2));
            var mailmanList = mailmanServer.getListReader(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");

            var duration3Days = Duration.between(now.minusDays(3), now);

            {
                // A 2 days old should be picked up
                var mail2daysAgo = Email.create(sender, "Subject 2 day2 ago", "Body 1")
                        .recipient(EmailAddress.parse(listAddress))
                        .date(now.minusDays(2))
                        .build();
                mailmanServer.post(mail2daysAgo);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration3Days);
                assertEquals(1, conversations.size());
                assertEquals(3, testServer.callCount(),
                        "Server wasn't for initial interval plus every day since start time");
            }
            {
                // A 2 days old mail should not be picked up now as old results should be cached
                var mail2daysAgo2 = Email.create(sender, "Subject 2 days ago 2", "Body 2")
                        .recipient(EmailAddress.parse(listAddress))
                        .date(now.minusDays(2))
                        .build();
                mailmanServer.post(mail2daysAgo2);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration3Days);
                assertEquals(1, conversations.size());
                assertEquals(1, testServer.callCount(), "Server wasn't called once");
            }
            {
                // A 1-day-old mail should not be picked up now as old results should be cached
                var mail1dayAgo = Email.create(sender, "Subject 1 day ago", "Body 2")
                        .recipient(EmailAddress.parse(listAddress))
                        .date(now.minusDays(1))
                        .build();
                mailmanServer.post(mail1dayAgo);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration3Days);
                assertEquals(1, conversations.size());
                assertEquals(1, testServer.callCount());
            }
            {
                // A current mail should be found
                var mailNow = Email.create(sender, "Subject now", "Body 3")
                        .recipient(EmailAddress.parse(listAddress))
                        .build();
                mailmanServer.post(mailNow);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration3Days);
                assertEquals(2, conversations.size());
                assertEquals(1, testServer.callCount());
            }
            {
                // Another mail from last month should not be found
                var mail1dayAgo2 = Email.create(sender, "Subject 1 day ago 2", "Body 2")
                        .recipient(EmailAddress.parse(listAddress))
                        .date(now.minusDays(1))
                        .build();
                mailmanServer.post(mail1dayAgo2);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration3Days);
                assertEquals(2, conversations.size());
                assertEquals(1, testServer.callCount());
            }
            {
                // Another current mail should be found
                var mailNow2 = Email.create(sender, "Subject now 2", "Body 3")
                        .recipient(EmailAddress.parse(listAddress))
                        .build();
                mailmanServer.post(mailNow2);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration3Days);
                assertEquals(3, conversations.size());
                assertEquals(1, testServer.callCount());
            }
        }
    }
}
