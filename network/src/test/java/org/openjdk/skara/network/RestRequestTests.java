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
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RestReceiver implements AutoCloseable {
    private final HttpServer server;
    private final List<JSONObject> requests;
    private final String response;
    private int responseCode;

    private int truncatedResponseCount = 0;

    class Handler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JSON.parse(input).asObject());

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
}
