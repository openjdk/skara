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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;
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

    public static TestMailmanServer createV3() throws IOException {
        return new TestMailman3Server();
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
            lastResponseCached = false;

            try {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(mboxContents);
                var etag = "\"" + Base64.getUrlEncoder().encodeToString(digest.digest()) + "\"";
                exchange.getResponseHeaders().add("ETag", etag);

                if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                    if (exchange.getRequestHeaders().getFirst("If-None-Match").equals(etag)) {
                        lastResponseCached = true;
                        exchange.sendResponseHeaders(304, 0);
                        return;
                    }
                }

                exchange.sendResponseHeaders(200, mboxContents.length);
                OutputStream outputStream = exchange.getResponseBody();
                outputStream.write(mboxContents);
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

    protected abstract byte[] getMboxContents(HttpExchange exchange);

    public URI getArchive() {
        return URIBuilder.base("http://" + httpServer.getAddress().getHostString() + ":" +  httpServer.getAddress().getPort() + "/test/").build();
    }

    public String getSMTP() {
        return smtpServer.address();
    }

    public abstract EmailAddress createList(String name);

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
    private final Map<EmailAddress, Map<String, StringBuilder>> lists = new HashMap<>();

    public TestMailman2Server() throws IOException {
        super();
    }

    @Override
    protected void archiveEmail(Email email, String mboxEntry) {
        var listMap = email.recipients().stream()
                            .filter(lists::containsKey)
                            .map(lists::get)
                            .findAny().orElseThrow();
        var datePath = DateTimeFormatter.ofPattern("yyyy-MMMM", Locale.US).format(email.date());
        if (!listMap.containsKey(datePath)) {
            listMap.put(datePath, new StringBuilder());
        }
        listMap.get(datePath).append(mboxEntry);
    }

    @Override
    protected byte[] getMboxContents(HttpExchange exchange) {
        var listMatcher = listPathPattern.matcher(exchange.getRequestURI().getPath());
        if (!listMatcher.matches()) {
            throw new RuntimeException();
        }
        var listPath = listMatcher.group(1);
        var datePath = listMatcher.group(2);
        var listMap = lists.get(EmailAddress.parse(listPath + "@" + httpServer.getAddress().getHostString()));
        var contents = listMap.get(datePath);
        if (contents != null) {
            return contents.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            return null;
        }
    }

    @Override
    public EmailAddress createList(String name) {
        var listName = EmailAddress.parse(name + "@" + httpServer.getAddress().getHostString());
        lists.put(listName, new HashMap<>());
        return listName;
    }
}

class TestMailman3Server extends TestMailmanServer {

    private record EmailEntry(Email email, String mbox) {}

    private Map<EmailAddress, List<EmailEntry>> lists = new HashMap<>();

    private static final Pattern listPathPattern = Pattern.compile("^/test/list/(.*?)/export/(.*)\\.mbox.gz");

    protected TestMailman3Server() throws IOException {
        super();
    }

    @Override
    protected byte[] getMboxContents(HttpExchange exchange) {
        // https://mail-dev.example.com/archives/list/skara-test@mail-dev.example.com/export/foo.mbox.gz?start=2024-10-25&end=2025-10-25
        var listMatcher = listPathPattern.matcher(exchange.getRequestURI().getPath());
        if (!listMatcher.matches()) {
            throw new RuntimeException();
        }
        var listPath = EmailAddress.parse(listMatcher.group(1));

        var query = exchange.getRequestURI().getRawQuery();
        String[] pairs = query.split("&");
        ZonedDateTime start = null;
        ZonedDateTime end = null;
        for (String pair : pairs) {
            int i = pair.indexOf("=");
            if (i > 0) {
                String key = URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
                if ("start".equals(key)) {
                    start = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault());
                } else if ("end".equals(key)) {
                    end = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault());
                }
            } else {
                throw new RuntimeException();
            }
        }
        var entryList = lists.get(listPath);
        var mbox = new StringBuilder();
        var startDate = start;
        var endDate = end;
        entryList.stream()
                .filter(e -> startDate == null || startDate.isBefore(e.email.date()))
                .filter(e -> endDate == null || endDate.isAfter(e.email.date()))
                .forEach(e -> mbox.append(e.mbox));

        var zipped = new ByteArrayOutputStream();
        try (var out = new OutputStreamWriter(new GZIPOutputStream(zipped))) {
            out.write(mbox.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return zipped.toByteArray();
    }

    @Override
    public EmailAddress createList(String name) {
        var emailAddress = EmailAddress.parse(name + "@" + httpServer.getAddress().getHostString());
        lists.put(emailAddress, new ArrayList<>());
        return emailAddress;
    }

    @Override
    protected void archiveEmail(Email email, String mboxEntry) {
        var entryList = email.recipients().stream()
                .filter(recipient -> lists.containsKey(recipient))
                .map(recipient -> lists.get(recipient))
                .findAny().orElseThrow();
        entryList.add(new EmailEntry(email, mboxEntry));
    }
}
