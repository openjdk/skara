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
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class SMTPServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ConcurrentLinkedDeque<Email> emails = new ConcurrentLinkedDeque<>();

    private final static Logger log = Logger.getLogger("org.openjdk.skara.test");;
    private final static Pattern commandPattern = Pattern.compile("^([A-Z]+) ?(.*)$");
    private final static Pattern encodeQuotedPrintablePattern = Pattern.compile("([^\\x00-\\x7f]+)");
    private final static Pattern headerPattern = Pattern.compile("[^A-Za-z0-9-]+: .+");

    private class AcceptThread implements Runnable {
        private void sendLine(String line, BufferedWriter out) throws IOException {
            log.fine("> " + line);
            out.write(line + "\n");
            out.flush();
        }

        private String readLine(BufferedReader in) throws IOException {
            while (!in.ready()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            var line = in.readLine();
            log.fine("< " + line);
            return line;
        }

        private void handleSession(BufferedReader in, BufferedWriter out) throws IOException {
            sendLine("220 localhost SMTP", out);
            var message = new ArrayList<String>();
            var done = false;
            while (!done) {
                var line = readLine(in);
                var commandMatcher = commandPattern.matcher(line);
                if (!commandMatcher.matches()) {
                    throw new RuntimeException("Illegal input: " + line);
                }
                switch (commandMatcher.group(1)) {
                    case "EHLO":
                        sendLine("250 HELP", out);
                        break;
                    case "MAIL":
                        sendLine("250 FROM OK", out);
                        break;
                    case "RCPT":
                        sendLine("250 RCPT OK", out);
                        break;
                    case "DATA":
                        sendLine("354 Enter message now, end with .", out);
                        while (true) {
                            var messageLine = readLine(in);
                            if (messageLine.equals(".")) {
                                sendLine("250 MESSAGE OK", out);
                                break;
                            }
                            message.add(messageLine);
                        }
                        break;
                    case "QUIT":
                        sendLine("BYE", out);
                        done = true;
                        break;
                }
            }

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
                if (line.startsWith(".")) {
                    line = line.substring(1);
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
                        handleSession(new BufferedReader(input), new BufferedWriter(output));
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
        var acceptThread = new Thread(new AcceptThread());
        acceptThread.start();
    }

    public String address() {
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
