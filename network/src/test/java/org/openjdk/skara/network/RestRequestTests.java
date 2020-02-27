/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.network;

import com.sun.net.httpserver.*;
import org.openjdk.skara.json.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RestReceiver implements AutoCloseable {
    private final HttpServer server;
    private final List<JSONObject> requests;
    private final String response;
    private int responseCode;

    private int truncatedResponseCount = 0;
    private volatile boolean usedCache = false;

    class Handler implements HttpHandler {
        private String checksum(String body) {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(body.getBytes(StandardCharsets.UTF_8));
                return Base64.getUrlEncoder().encodeToString(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Cannot find SHA-256");
            }
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JSON.parse(input).asObject());

            usedCache = false;
            if (truncatedResponseCount == 0) {
                var responseHeaders = exchange.getResponseHeaders();
                var etag = checksum(response);
                if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                    var requestedEtag = exchange.getRequestHeaders().getFirst("If-None-Match");
                    if (requestedEtag.equals(etag)) {
                        exchange.sendResponseHeaders(304, -1);
                        usedCache = true;
                        return;
                    }
                }
                responseHeaders.add("ETag", etag);
            }

            exchange.sendResponseHeaders(responseCode, response.length());
            OutputStream outputStream = exchange.getResponseBody();
            if (truncatedResponseCount > 0) {
                truncatedResponseCount--;
            } else {
                outputStream.write(response.getBytes());
            }
            outputStream.close();
        }
    }

    RestReceiver() throws IOException {
        this("{}", 200);
    }

    RestReceiver(String response, int responseCode) throws IOException
    {
        this.response = response;
        this.responseCode = responseCode;
        requests = new ArrayList<>();
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        server = HttpServer.create(address, 0);
        server.createContext("/test", new Handler());
        server.setExecutor(null);
        server.start();
    }

    URI getEndpoint() {
        return URIBuilder.base("http://" + server.getAddress().getHostString() + ":" +  server.getAddress().getPort() + "/test").build();
    }

    List<JSONObject> getRequests() {
        return requests;
    }

    void setTruncatedResponseCount(int count) {
        truncatedResponseCount = count;
    }

    boolean usedCached() {
        return usedCache;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

class RestRequestTests {
    @Test
    void simpleRequest() throws IOException {
        try (var receiver = new RestReceiver()) {
            var request = new RestRequest(receiver.getEndpoint());
            request.post("/test").execute();
        }
    }

    @Test
    void retryOnTransientErrors() throws IOException {
        try (var receiver = new RestReceiver()) {
            receiver.setTruncatedResponseCount(1);

            var request = new RestRequest(receiver.getEndpoint());
            request.setRetryBackoffStep(Duration.ofMillis(1));
            request.post("/test").execute();
        }
    }

    @Test
    void failOnNonTransientErrors() throws IOException {
        try (var receiver = new RestReceiver()) {
            receiver.setTruncatedResponseCount(6);

            var request = new RestRequest(receiver.getEndpoint());
            request.setRetryBackoffStep(Duration.ofMillis(1));
            assertThrows(RuntimeException.class, () -> request.post("/test").execute());
        }
    }

    @Test
    void transformError() throws IOException {
        try (var receiver = new RestReceiver("{}", 400)) {
            var request = new RestRequest(receiver.getEndpoint());
            var response = request.post("/test")
                   .onError(r -> Optional.of(JSON.object().put("transformed", true)))
                   .execute();
            assertTrue(response.contains("transformed"));
        }
    }

    @Test
    void parseError() throws IOException {
        try (var receiver = new RestReceiver("{{bad_json", 200)) {
            var request = new RestRequest(receiver.getEndpoint());
            assertThrows(RuntimeException.class, () -> request.post("/test").execute());
        }
    }

    @Test
    void unparsed() throws IOException {
        try (var receiver = new RestReceiver("{{bad", 200)) {
            var request = new RestRequest(receiver.getEndpoint());
            var response = request.post("/test").executeUnparsed();
            assertEquals("{{bad", response);
        }
    }

    @Test
    void cached() throws IOException {
        try (var receiver = new RestReceiver()) {
            var request = new RestRequest(receiver.getEndpoint());
            request.get("/test").execute();
            assertFalse(receiver.usedCached());
            request.get("/test").execute();
            assertTrue(receiver.usedCached());
            var anotherRequest = new RestRequest(receiver.getEndpoint());
            anotherRequest.get("/test").execute();
            assertTrue(receiver.usedCached());
        }
    }

    @Test
    void cachedSeparateAuth() throws IOException {
        try (var receiver = new RestReceiver()) {
            var plainRequest = new RestRequest(receiver.getEndpoint());
            var authRequest1 = new RestRequest(receiver.getEndpoint(), "id1", () -> List.of("user", "1"));
            var authRequest2 = new RestRequest(receiver.getEndpoint(), "id2", () -> List.of("user", "2"));

            plainRequest.get("/test").execute();
            assertFalse(receiver.usedCached());
            authRequest1.get("/test").execute();
            assertFalse(receiver.usedCached());

            plainRequest.get("/test").execute();
            assertTrue(receiver.usedCached());

            authRequest2.get("/test").execute();
            assertFalse(receiver.usedCached());
            authRequest2.get("/test").execute();
            assertTrue(receiver.usedCached());
        }
    }
}
