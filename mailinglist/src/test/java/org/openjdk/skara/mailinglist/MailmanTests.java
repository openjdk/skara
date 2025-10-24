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
package org.openjdk.skara.mailinglist;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.email.*;
import org.openjdk.skara.test.TestMailmanServer;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MailmanTests {
    @Test
    void simple() throws IOException {
        try (var testServer = TestMailmanServer.createV2()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
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
        try (var testServer = TestMailmanServer.createV2()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
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
    void cached() throws IOException {
        try (var testServer = TestMailmanServer.createV2()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
                                                                             Duration.ZERO, true);
            var mailmanList = mailmanServer.getListReader(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var mail = Email.create(sender, "Subject", "Body")
                            .recipient(EmailAddress.parse(listAddress))
                            .build();
            mailmanServer.post(mail);
            testServer.processIncoming();

            var expectedMail = Email.from(mail)
                                    .sender(EmailAddress.parse(listAddress))
                                    .build();
            {
                var conversations = mailmanList.conversations(Duration.ofDays(1));
                assertEquals(1, conversations.size());
                var conversation = conversations.get(0);
                assertEquals(expectedMail, conversation.first());
                assertFalse(testServer.lastResponseCached());
            }
            {
                var conversations = mailmanList.conversations(Duration.ofDays(1));
                assertEquals(1, conversations.size());
                var conversation = conversations.get(0);
                assertEquals(expectedMail, conversation.first());
                assertTrue(testServer.lastResponseCached());
            }
        }
    }

    @Test
    void interval() throws IOException {
        try (var testServer = TestMailmanServer.createV2()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(), testServer.getSMTP(),
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
    void poll3months() throws Exception {
        try (var testServer = TestMailmanServer.createV2()) {
            var listAddress = testServer.createList("test");
            var mailmanServer = MailingListServerFactory.createMailmanServer(testServer.getArchive(),
                    testServer.getSMTP(), Duration.ZERO);
            var mailmanList = mailmanServer.getListReader(listAddress);
            var sender = EmailAddress.from("Test", "test@test.email");
            var now = ZonedDateTime.now();
            var mail2monthsAgo = Email.create(sender, "Subject 2 months ago", "Body 1")
                    .recipient(EmailAddress.parse(listAddress))
                    .date(now.minusMonths(2))
                    .build();
            var mail1monthAgo = Email.create(sender, "Subject 1 month ago", "Body 2")
                    .recipient(EmailAddress.parse(listAddress))
                    .date(now.minusMonths(1))
                    .build();
            var mailNow = Email.create(sender, "Subject now", "Body 3")
                    .recipient(EmailAddress.parse(listAddress))
                    .build();

            var duration2Months = Duration.between(now.minusMonths(2), now);
            {
                var conversations = mailmanList.conversations(duration2Months);
                assertEquals(0, conversations.size());
                assertEquals(3, testServer.callCount(), "Server wasn't called for every month");
            }
            {
                // A 2 months old mail should not be picked up now as old results should be cached
                mailmanServer.post(mail2monthsAgo);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration2Months);
                assertEquals(0, conversations.size());
                //
                assertEquals(2, testServer.callCount(), "Server should only be called for the current and previous month");
            }
            {
                // A mail from last month should be found
                mailmanServer.post(mail1monthAgo);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration2Months);
                assertEquals(1, conversations.size());
                assertEquals(2, testServer.callCount());
            }
            {
                // A current mail should be found
                mailmanServer.post(mailNow);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration2Months);
                assertEquals(2, conversations.size());
                assertEquals(2, testServer.callCount());
            }
            {
                // Another mail from last month should be found
                var mail1monthAgo2 = Email.create(sender, "Subject 1 month ago 2", "Body 2")
                        .recipient(EmailAddress.parse(listAddress))
                        .date(now.minusMonths(1))
                        .build();
                mailmanServer.post(mail1monthAgo2);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration2Months);
                assertEquals(3, conversations.size());
                assertEquals(2, testServer.callCount());
            }
            {
                // Another current mail should be found
                var mailNow2 = Email.create(sender, "Subject now 2", "Body 3")
                        .recipient(EmailAddress.parse(listAddress))
                        .build();
                mailmanServer.post(mailNow2);
                testServer.processIncoming();
                testServer.resetCallCount();
                var conversations = mailmanList.conversations(duration2Months);
                assertEquals(4, conversations.size());
                assertEquals(2, testServer.callCount());
            }
        }
    }
}
