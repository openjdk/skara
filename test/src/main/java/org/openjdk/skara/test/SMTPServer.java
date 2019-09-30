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
package org.openjdk.skara.test;

import org.openjdk.skara.email.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

public class SMTPServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final ConcurrentLinkedDeque<Email> emails = new ConcurrentLinkedDeque<>();

    private static Pattern ehloPattern = Pattern.compile("^EHLO .*$");
    private static Pattern mailFromPattern = Pattern.compile("^MAIL FROM:.*$");
    private static Pattern rcptToPattern = Pattern.compile("^RCPT TO:<.*$");
    private static Pattern dataPattern = Pattern.compile("^DATA$");
    private static Pattern messageEndPattern = Pattern.compile("^\\.$");
    private static Pattern quitPattern = Pattern.compile("^QUIT$");

    private final static Pattern encodeQuotedPrintablePattern = Pattern.compile("([^\\x00-\\x7f]+)");
    private final static Pattern headerPattern = Pattern.compile("[^A-Za-z0-9-]+: .+");

    private class AcceptThread implements Runnable {
        private void handleSession(SMTPSession session) throws IOException {
            session.sendCommand("220 localhost SMTP", ehloPattern);
            session.sendCommand("250 HELP", mailFromPattern);
            session.sendCommand("250 FROM OK", rcptToPattern);
            session.sendCommand("250 RCPT OK", dataPattern);
            session.sendCommand("354 Enter message now, end with .");
            var message = session.readLinesUntil(messageEndPattern);
            session.sendCommand("250 MESSAGE OK", quitPattern);

            // Email headers are only 7-bit safe, ensure that we break any high ascii passing through
            var inHeader = true;
            var mailBody = new StringBuilder();
            for (var line : message) {
                if (inHeader) {
                    var headerMatcher = headerPattern.matcher(line);
                    if (headerMatcher.matches()) {
                        var quoteMatcher = encodeQuotedPrintablePattern.matcher(String.join("\n", message));
                        var ascii7line = quoteMatcher.replaceAll(mo -> "HIGH_ASCII");
                        mailBody.append(ascii7line);
                        mailBody.append("\n");
                        continue;
                    } else {
                        inHeader = false;
                    }
                }
                mailBody.append(line);
                mailBody.append("\n");
            }

            var email = Email.parse(mailBody.toString());
            emails.addLast(email);
        }

        @Override
        public void run() {
            while (!serverSocket.isClosed()) {
                try {
                    try (var socket = serverSocket.accept();
                         var input = new InputStreamReader(socket.getInputStream());
                         var output = new OutputStreamWriter(socket.getOutputStream())) {
                        var session = new SMTPSession(input, output);
                        handleSession(session);
                    }
                } catch (SocketException e) {
                    // Socket closed
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    public SMTPServer() throws IOException {
        serverSocket = new ServerSocket(0);
        acceptThread = new Thread(new AcceptThread());
        acceptThread.start();
    }

    public String address() {
        var host = serverSocket.getInetAddress();
        return InetAddress.getLoopbackAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    public Email receive(Duration timeout) {
        var start = Instant.now();
        while (emails.isEmpty() && start.plus(timeout).isAfter(Instant.now())) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }

        if (emails.isEmpty()) {
            throw new RuntimeException("No email received");
        }
        return emails.removeFirst();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
