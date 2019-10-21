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

import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.openjdk.skara.test.SMTPServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SMTPTests {
    private final static Logger log = Logger.getLogger("org.openjdk.skara.email");;

    @Test
    void simple() throws IOException {
        log.info("Hello");
        try (var server = new SMTPServer()) {
            var sender = EmailAddress.from("Test", "test@test.email");
            var recipient = EmailAddress.from("Dest", "dest@dest.email");
            var sentMail = Email.create(sender, "Subject", "Body").recipient(recipient).build();

            SMTP.send(server.address(), recipient, sentMail);
            var email = server.receive(Duration.ofSeconds(10));
            assertEquals(sentMail, email);
        }
    }

    @Test
    void withHeader() throws IOException {
        log.info("Hello");
        try (var server = new SMTPServer()) {
            var sender = EmailAddress.from("Test", "test@test.email");
            var author = EmailAddress.from("Auth", "auth@test.email");
            var recipient = EmailAddress.from("Dest", "dest@dest.email");
            var sentMail = Email.create(author, "Subject", "Body")
                                .sender(sender)
                                .recipient(recipient)
                                .header("Something", "Other")
                                .build();

            SMTP.send(server.address(), recipient, sentMail);
            var email = server.receive(Duration.ofSeconds(10));
            assertEquals(sentMail, email);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void encoded() throws IOException {
        log.info("Hello");
        try (var server = new SMTPServer()) {
            var sender = EmailAddress.from("Señor Dévèlöper", "test@test.email");
            var recipient = EmailAddress.from("Dêst", "dest@dest.email");
            var sentMail = Email.create(sender, "Sübject", "Bödÿ")
                                .recipient(recipient)
                                .header("Something", "Öthè®")
                                .build();

            SMTP.send(server.address(), recipient, sentMail);
            var email = server.receive(Duration.ofSeconds(10));
            assertEquals(sentMail, email);
        }
    }

    @Test
    void timeout() throws IOException {
        log.info("Hello");
        try (var server = new SMTPServer()) {
            var sender = EmailAddress.from("Test", "test@test.email");
            var recipient = EmailAddress.from("Dest", "dest@dest.email");
            var sentMail = Email.create(sender, "Subject", "Body").recipient(recipient).build();

            assertThrows(RuntimeException.class, () -> SMTP.send(server.address(), recipient, sentMail, Duration.ZERO));
        }
    }
}
