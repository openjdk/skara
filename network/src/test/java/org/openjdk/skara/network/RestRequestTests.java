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
    private final List<JSONObject> requests = new ArrayList<>();
    private final List<String> responses;
    private int responseCode;

    private int truncatedResponseCount = 0;
    private boolean usedCache = false;

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
            if (input.isBlank()) {
                requests.add(JSON.object());
            } else {
                requests.add(JSON.parse(input).asObject());
            }

            var pageQuery = exchange.getRequestURI().getQuery();
            var requestedPage = pageQuery == null ? 1 : Integer.parseInt(pageQuery);
            var response = responses.get(requestedPage - 1);

            if (responses.size() > 1) {
                var responseHeaders = exchange.getResponseHeaders();
                if (requestedPage < responses.size()) {
                    responseHeaders.add("Link", "<" + getEndpoint() + "?" + (requestedPage + 1) + ">; rel=\"next\"");
                }
                if (requestedPage > 1) {
                    responseHeaders.add("Link", "<" + getEndpoint() + "?" + (requestedPage - 1) + ">; rel=\"prev\"");
                }
            }

            usedCache = false;
            if (truncatedResponseCount == 0) {
                var responseHeaders = exchange.getResponseHeaders();
                var etag = checksum(response);
                if (exchange.getRequestHeaders().containsKey("If-None-Match")) {
                    var requestedEtag = exchange.getRequestHeaders().getFirst("If-None-Match");
                    if (requestedEtag.equals(etag)) {
                        usedCache = true;
                        exchange.sendResponseHeaders(304, -1);
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

    private HttpServer createServer() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        var server = HttpServer.create(address, 0);
        server.createContext("/test", new Handler());
        server.setExecutor(null);
        server.start();
        return server;
    }

    RestReceiver() throws IOException {
        this("{}", 200);
    }

    RestReceiver(String response, int responseCode) throws IOException
    {
        this.responses = List.of(response);
        this.responseCode = responseCode;
        this.server = createServer();
    }

    RestReceiver(List<String> responsePages) throws IOException {
        this.responses = Collections.unmodifiableList(responsePages);
        this.responseCode = 200;
        this.server = createServer();
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
    void pagination() throws IOException {
        var page1 = "[ { \"a\": 1 } ]";
        var page2 = "[ { \"b\": 2 } ]";
        try (var receiver = new RestReceiver(List.of(page1, page2))) {
            var request = new RestRequest(receiver.getEndpoint());
            var result = request.post("/test").execute();
            assertEquals(2, result.asArray().size());
            assertEquals(1, result.asArray().get(0).get("a").asInt());
        }
    }

    @Test
    void fieldPagination() throws IOException {
        var page1 = "{ \"a\": 1, \"b\": [ 1, 2, 3 ] }";
        var page2 = "{ \"a\": 1, \"b\": [ 4, 5, 6 ] }";
        try (var receiver = new RestReceiver(List.of(page1, page2))) {
            var request = new RestRequest(receiver.getEndpoint());
            var result = request.post("/test").execute();
            assertEquals(1, result.get("a").asInt());
            assertEquals(6, result.get("b").asArray().size());
            assertEquals(1, result.get("b").asArray().get(0).asInt());
            assertEquals(4, result.get("b").asArray().get(3).asInt());
            assertEquals(6, result.get("b").asArray().get(5).asInt());
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
