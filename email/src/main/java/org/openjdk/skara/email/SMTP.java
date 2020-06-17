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

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Limited SMTP client implementation - only compatibility requirement (currently) is the OpenJDK
 * mailing list servers.
 */
public class SMTP {
    private static Pattern initReply = Pattern.compile("^220 .*");
    private static Pattern ehloReply = Pattern.compile("^250 .*");
    private static Pattern mailReply = Pattern.compile("^250 .*");
    private static Pattern rcptReply = Pattern.compile("^250 .*");
    private static Pattern dataReply = Pattern.compile("^354 .*");
    private static Pattern doneReply = Pattern.compile("^250 .*");

    public static void send(String server, Email email) throws IOException {
        send(server, email, Duration.ofMinutes(30));
    }

    public static void send(String server, Email email, Duration timeout) throws IOException {
        if (email.recipients().isEmpty()) {
            throw new IllegalArgumentException("Attempting to send an email without recipients");
        }
        var port = 25;
        if (server.contains(":")) {
            var parts = server.split(":", 2);
            server = parts[0];
            port = Integer.parseInt(parts[1]);
        }
        var recipientList = email.recipients().stream()
                                 .map(EmailAddress::toString)
                                 .map(MimeText::encode)
                                 .collect(Collectors.joining(", "));
        try (var socket = new Socket(server, port);
             var out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
             var in = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)) {

            var session = new SMTPSession(in, out, timeout);

            session.waitForPattern(initReply);
            session.sendCommand("EHLO " + email.sender().domain(), ehloReply);
            session.sendCommand("MAIL FROM:" + email.sender().address(), mailReply);
            for (var recipient : email.recipients()) {
                session.sendCommand("RCPT TO:<" + recipient.address() + ">", rcptReply);
            }
            session.sendCommand("DATA", dataReply);
            session.sendCommand("From: " + MimeText.encode(email.author().toString()));
            session.sendCommand("Message-Id: " + email.id());
            session.sendCommand("Date: " + email.date().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            session.sendCommand("Sender: " + MimeText.encode(email.sender().toString()));
            session.sendCommand("To: " + recipientList);
            for (var header : email.headers()) {
                session.sendCommand(header + ": " + MimeText.encode(email.headerValue(header)));
            }
            session.sendCommand("Subject: " + MimeText.encode(email.subject()));
            session.sendCommand("Content-type: text/plain; charset=utf-8");
            session.sendCommand("");
            var escapedBody = email.body().lines()
                                   .map(line -> line.startsWith(".") ? "." + line : line)
                                   .collect(Collectors.joining("\n"));
            session.sendCommand(escapedBody);
            session.sendCommand(".", doneReply);
            session.sendCommand("QUIT");
        }
    }
}
