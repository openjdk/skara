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
package org.openjdk.skara.test;

import com.sun.net.httpserver.*;
import java.time.format.DateTimeFormatter;
import org.openjdk.skara.email.*;
import org.openjdk.skara.mailinglist.Mbox;
import org.openjdk.skara.network.URIBuilder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public abstract class TestMailmanServer implements AutoCloseable {
    protected final HttpServer httpServer;
    private final SMTPServer smtpServer;
    private int callCount = 0;
    private boolean lastResponseCached;

    public static TestMailmanServer createV2() throws IOException {
        return new TestMailman2Server();
    }

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            callCount++;
            var mboxContents = getMboxContents(exchange);
            if (mboxContents == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            var response = mboxContents.toString();
            lastResponseCached = false;

            try {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(response.getBytes(StandardCharsets.UTF_8));
                var etag = "\"" + Base64.getUrlEncoder().encodeToString(digest.digest()) + "\"";
                exchange.getResponseHeaders().add("ETag", etag);

                if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                    if (exchange.getRequestHeaders().getFirst("If-None-Match").equals(etag)) {
                        lastResponseCached = true;
                        exchange.sendResponseHeaders(304, 0);
                        return;
                    }
                }

                var responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                OutputStream outputStream = exchange.getResponseBody();
                outputStream.write(responseBytes);
                outputStream.close();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    protected TestMailmanServer() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        httpServer = HttpServer.create(address, 0);
        httpServer.createContext("/test", new Handler());
        httpServer.setExecutor(null);
        httpServer.start();

        smtpServer = new SMTPServer();
    }

    protected abstract StringBuilder getMboxContents(HttpExchange exchange);

    public URI getArchive() {
        return URIBuilder.base("http://" + httpServer.getAddress().getHostString() + ":" +  httpServer.getAddress().getPort() + "/test/").build();
    }

    public String getSMTP() {
        return smtpServer.address();
    }

    public abstract String createList(String name);

    public void processIncoming(Duration timeout) {
        var email = smtpServer.receive(timeout);
        var subject = email.subject();
        if (subject.startsWith("Re: ")) {
            subject = subject.substring(4);
        }
        var stripped = Email.from(email)
                            .subject(subject)
                            .build();
        var mboxEntry = Mbox.fromMail(stripped);

        archiveEmail(email, mboxEntry);
    }

    protected abstract void archiveEmail(Email email, String mboxEntry);

    public void processIncoming() {
        processIncoming(Duration.ofSeconds(10));
    }

    @Override
    public void close() throws IOException {
        httpServer.stop(0);
        smtpServer.close();
    }

    public boolean lastResponseCached() {
        return lastResponseCached;
    }

    public void resetCallCount() {
        callCount = 0;
    }

    public int callCount() {
        return callCount;
    }
}

class TestMailman2Server extends TestMailmanServer {

    private static final Pattern listPathPattern = Pattern.compile("^/test/(.*?)/(.*)\\.txt");
    // Map from local part of email list name to map from date string to mbox contents
    private final Map<String, Map<String, StringBuilder>> lists = new HashMap<>();

    public TestMailman2Server() throws IOException {
        super();
    }

    @Override
    protected void archiveEmail(Email email, String mboxEntry) {
        var listMap = email.recipients().stream()
                            .filter(recipient -> lists.containsKey(recipient.localPart()))
                            .map(recipient -> lists.get(recipient.localPart()))
                            .findAny().orElseThrow();
        var datePath = DateTimeFormatter.ofPattern("yyyy-MMMM", Locale.US).format(email.date());
        if (!listMap.containsKey(datePath)) {
            listMap.put(datePath, new StringBuilder());
        }
        listMap.get(datePath).append(mboxEntry);
    }

    @Override
    protected StringBuilder getMboxContents(HttpExchange exchange) {
        var listMatcher = listPathPattern.matcher(exchange.getRequestURI().getPath());
        if (!listMatcher.matches()) {
            throw new RuntimeException();
        }
        var listPath = listMatcher.group(1);
        var datePath = listMatcher.group(2);
        var listMap = lists.get(listPath);
        return listMap.get(datePath);
    }

    @Override
    public String createList(String name) {
        var listName = EmailAddress.parse(name + "@" + httpServer.getAddress().getHostString()).toString();
        lists.put(name, new HashMap<>());
        return listName;
    }
}
