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
package org.openjdk.skara.email;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailTests {
    @Test
    void parseSimple() {
        var mail = Email.parse("Message-Id: <a@b.c>\n" +
                "Date: Wed, 27 Mar 2019 14:31:00 +0100\n" +
                "Subject: hello\n" +
                "From: B <b@b.c>\n" +
                "To: C <c@c.c>, <d@d.c>\n" +
                "\n" +
                "The body"
        );

        assertEquals(EmailAddress.from("a@b.c"), mail.id());
        assertEquals("hello", mail.subject());
        assertEquals(EmailAddress.from("B", "b@b.c"), mail.author());
        assertEquals(EmailAddress.from("B", "b@b.c"), mail.sender());
        assertEquals(List.of(EmailAddress.from("C", "c@c.c"),
                             EmailAddress.from("d@d.c")),
                     mail.recipients());
        assertEquals("The body", mail.body());
    }

    @Test
    void buildFrom() {
        var original = Email.create(EmailAddress.from("A", "a@b.c"), "Subject", "body")
                            .header("X", "y")
                            .header("Z", "a")
                            .recipient(EmailAddress.from("B", "b@b.c"))
                            .build();
        var copy = Email.from(original).build();
        assertEquals("Subject", copy.subject());
        assertEquals("body", copy.body());
        assertEquals(Set.of("X", "Z"), copy.headers());
        assertEquals("y", copy.headerValue("X"));
        assertEquals("a", copy.headerValue("z"));
        assertEquals(original, copy);
    }

    @Test
    void reparent() {
        var first = Email.create(EmailAddress.from("A", "a@b.c"), "First", "body")
                         .recipient(EmailAddress.from("B", "b@b.c"))
                         .build();
        var second = Email.create(EmailAddress.from("A", "a@b.c"), "Second", "body")
                          .recipient(EmailAddress.from("B", "b@b.c"))
                          .build();
        var reply = Email.reply(first, "The reply", "reply body")
                         .author(EmailAddress.from("C", "c@b.c"))
                         .build();
        assertEquals(first.id().toString(), reply.headerValue("In-Reply-To"));
        assertEquals(first.id().toString(), reply.headerValue("References"));
        var updated = Email.reparent(second, reply).build();
        assertEquals(second.id().toString(), updated.headerValue("In-Reply-To"));
        assertEquals(second.id().toString(), updated.headerValue("References"));
    }

    @Test
    void caseInsensitiveHeaders() {
        var mail = Email.parse("Message-ID: <a@b.c>\n" +
                                       "date: Wed, 27 Mar 2019 14:31:00 +0100\n" +
                                       "SUBJECT: hello\n" +
                                       "FRom: B <b@b.c>\n" +
                                       "tO: C <c@c.c>, <d@d.c>\n" +
                                       "Extra-header: hello\n" +
                                       "\n" +
                                       "The body"
        );

        assertEquals(EmailAddress.from("a@b.c"), mail.id());
        assertEquals("hello", mail.subject());
        assertEquals(EmailAddress.from("B", "b@b.c"), mail.author());
        assertEquals(EmailAddress.from("B", "b@b.c"), mail.sender());
        assertEquals(List.of(EmailAddress.from("C", "c@c.c"),
                             EmailAddress.from("d@d.c")),
                     mail.recipients());
        assertEquals("The body", mail.body());
        assertEquals(Set.of("Extra-header"), mail.headers());
        assertEquals("hello", mail.headerValue("ExTra-HeaDer"));
    }

    @Test
    void redundantTimeZone() {
        var mail = Email.parse("Message-Id: <a@b.c>\n" +
                                       "Date: Wed, 27 Mar 2019 14:31:00 +0700 (PDT)\n" +
                                       "Subject: hello\n" +
                                       "From: B <b@b.c>\n" +
                                       "To: C <c@c.c>, <d@d.c>\n" +
                                       "\n" +
                                       "The body"
        );
        assertEquals(ZonedDateTime.of(2019, 3, 27, 14, 31, 0, 0, ZoneOffset.ofHours(7)), mail.date());
        assertEquals(EmailAddress.from("a@b.c"), mail.id());
        assertEquals("hello", mail.subject());
        assertEquals(EmailAddress.from("B", "b@b.c"), mail.author());
        assertEquals(EmailAddress.from("B", "b@b.c"), mail.sender());
        assertEquals(List.of(EmailAddress.from("C", "c@c.c"),
                             EmailAddress.from("d@d.c")),
                     mail.recipients());
        assertEquals("The body", mail.body());
    }

    @Test
    void parseEncoded() {
        var mail = Email.parse("Message-Id: <a@b.c>\n" +
                                       "Date: Wed, 27 Mar 2019 14:31:00 +0100\n" +
                                       "Subject: hello\n" +
                                       "From: r.b at c.d (r =?iso-8859-1?Q?b=E4?=)\n" +
                                       "To: C <c@c.c>, <d@d.c>\n" +
                                       "\n" +
                                       "The body"
        );

        assertEquals(EmailAddress.from("a@b.c"), mail.id());
        assertEquals("hello", mail.subject());
        assertEquals(EmailAddress.from("r bä", "r.b@c.d"), mail.author());
        assertEquals(EmailAddress.from("r bä", "r.b@c.d"), mail.sender());
        assertEquals(List.of(EmailAddress.from("C", "c@c.c"),
                             EmailAddress.from("d@d.c")),
                     mail.recipients());
        assertEquals("The body", mail.body());
    }

    @Test
    void parseContentType7bit() {
        var mail = Email.parse("""
                Message-Id: <a@b.c>
                Date: Wed, 27 Mar 2019 14:31:00 +0100
                Subject: hello
                From: B <b@b.c>
                To: C <c@c.c>, <d@d.c>
                Content-Type: multipart/mixed; boundary="===============3685582790409215631=="

                --===============3685582790409215631==
                Content-Type: text/plain; charset="utf-8"
                Content-Transfer-Encoding: 7bit
                
                The body text
                
                --===============3685582790409215631==--
                """
        );

        assertEquals("The body text", mail.body());
    }

    @Test
    void parseContentTypeQuotedPrintable() {
        var mail = Email.parse("""
                Message-Id: <a@b.c>
                Date: Wed, 27 Mar 2019 14:31:00 +0100
                Subject: hello
                From: B <b@b.c>
                To: C <c@c.c>, <d@d.c>
                Content-Type: multipart/mixed; boundary="===============3685582790409215631=="

                --===============3685582790409215631==
                Content-Type: text/plain; charset="utf-8"
                Content-Transfer-Encoding: quoted-printable

                A response with weird characters r=C3=A4ksm=C3=B6rg=C3=A5s and a line longer =
                than 76=20
                characters.

                --===============3685582790409215631==--
                """
        );

        assertEquals("A response with weird characters räksmörgås and a line longer than 76 \ncharacters.", mail.body());
    }

    @Test
    void parseContentTypeMultipart() {
        var mail = Email.parse("""
                Message-Id: <a@b.c>
                Date: Wed, 27 Mar 2019 14:31:00 +0100
                Subject: hello
                From: B <b@b.c>
                To: C <c@c.c>, <d@d.c>
                Content-Type: multipart/mixed; boundary="===============3685582790409215631=="

                --===============3685582790409215631==
                Content-Type: text/plain; charset="utf-8"
                Content-Transfer-Encoding: 7bit

                The body text

                --===============3685582790409215631==
                Content-Type: text/html
                Content-Transfer-Encoding: base64
                Content-Disposition: attachment; filename="attachment.html"
                MIME-Version: 1.0

                PCFET0NUWVBFIGh0bWw+PGh0bWw+PGhlYWQ+CjxtZXRhIGh0dHAtZXF1aXY9IkNvbnRlbnQtVHlw
                ZSIgY29udGVudD0idGV4dC9odG1sOyBjaGFyc2V0PXV0Zi04Ij4KICA8L2hlYWQ+CiAgPGJvZHk+
                CiAgICA8aDE+VGhpcyBpcyBhbiBlbWFpbCB3aXRoIGZvcm1hdHRpbmc8L2gxPgogICAgPHA+UGFy
                YWdyYXBoIHRleHQuPC9wPgogICAgPHByZT5QcmVmb3JtYXQgdGV4dC48L3ByZT4KICA8L2JvZHk+
                CjwvaHRtbD4K

                --===============3685582790409215631==--
                """
        );

        assertEquals("The body text", mail.body());
    }
}
