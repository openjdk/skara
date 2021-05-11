/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bot;

import com.sun.net.httpserver.*;
import org.openjdk.skara.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

class RestReceiver {
    private final HttpServer server;
    private final Consumer<JSONValue> consumer;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bot");

    class Handler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            // Reply immediately
            var response = "{}";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream outputStream = exchange.getResponseBody();
            outputStream.write(response.getBytes());
            outputStream.close();

            try {
                var parsedInput = JSON.parse(input);
                consumer.accept(parsedInput);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Failed to parse incoming request: " + input, e);
            }
        }
    }

    RestReceiver(int port, Consumer<JSONValue> consumer) throws IOException
    {
        this.consumer = consumer;
        InetSocketAddress address = new InetSocketAddress(port);
        server = HttpServer.create(address, 0);
        server.createContext("/", new Handler());
        server.setExecutor(null);
        server.start();
    }

    void close() {
        server.stop(0);
    }
}
