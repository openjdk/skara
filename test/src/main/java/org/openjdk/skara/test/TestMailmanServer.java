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

import com.sun.net.httpserver.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.mailinglist.Mbox;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public class TestMailmanServer implements AutoCloseable {
    private final HttpServer httpServer;
    private final SMTPServer smtpServer;
    private final Map<String, Path> lists = new HashMap<>();
    private boolean lastResponseCached;

    static private final Pattern listPathPattern = Pattern.compile("^/test/(.*?)/.*");

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var listMatcher = listPathPattern.matcher(exchange.getRequestURI().getPath());
            if (!listMatcher.matches()) {
                throw new RuntimeException();
            }
            var list = lists.get(listMatcher.group(1));
            var response = Files.readString(list);
            lastResponseCached = false;

            try {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(response.getBytes(StandardCharsets.UTF_8));
                var etag = "\"" + Base64.getUrlEncoder().encodeToString(digest.digest()) + "\"";
                exchange.getResponseHeaders().add("ETag", etag);

                if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                    if (exchange.getRequestHeaders().getFirst("If-None-Match").equals(etag)) {
                        exchange.sendResponseHeaders(304, 0);
                        lastResponseCached = true;
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

    public TestMailmanServer() throws IOException
    {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        httpServer = HttpServer.create(address, 0);
        httpServer.createContext("/test", new Handler());
        httpServer.setExecutor(null);
        httpServer.start();

        smtpServer = new SMTPServer();
    }

    public URI getArchive() {
        return URIBuilder.base("http://" + httpServer.getAddress().getHostString() + ":" +  httpServer.getAddress().getPort() + "/test/").build();
    }

    public String getSMTP() {
        return smtpServer.address();
    }

    public String createList(String name) throws IOException {
        var listName = EmailAddress.parse(name + "@testserver.test").toString();
        var listPath = Files.createTempFile("list-" + name, ".txt");
        lists.put(name, listPath);
        return listName;
    }

    public void processIncoming(Duration timeout) throws IOException {
        var email = smtpServer.receive(timeout);
        var mboxEntry = Mbox.fromMail(email);

        var listPath = email.recipients().stream()
                            .filter(recipient -> lists.containsKey(recipient.localPart()))
                            .map(recipient -> lists.get(recipient.localPart()))
                            .findAny().orElseThrow();
        Files.writeString(listPath, mboxEntry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void processIncoming() throws IOException {
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
}
