/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.SMTPServer;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailSenderFactoryTests {
    @Test
    void smtp() throws IOException {
        try (var server = new SMTPServer()) {
            var sender = EmailSenderFactory.create(JSON.object()
                                                       .put("type", "smtp")
                                                       .put("server", server.address()));
            var author = EmailAddress.from("Test", "test@test.email");
            var recipient = EmailAddress.from("Dest", "dest@dest.email");
            var sentMail = Email.create(author, "Subject", "Body")
                                .recipient(recipient)
                                .build();

            sender.send(sentMail);
            var email = server.receive(Duration.ofSeconds(10));
            assertEquals(sentMail, email);
        }
    }
}
