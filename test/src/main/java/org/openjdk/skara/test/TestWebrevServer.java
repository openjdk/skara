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
import org.openjdk.skara.network.URIBuilder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class TestWebrevServer implements AutoCloseable {
    private final HttpServer httpServer;
    private boolean checked = false;
    private boolean redirectFollowed = true;
    private Consumer<URI> handleCallback = null;

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            checked = true;
            if (handleCallback != null) {
                handleCallback.accept(exchange.getRequestURI());
            }

            var response = "ok!";
            var responseBytes = response.getBytes(StandardCharsets.UTF_8);
            if (!exchange.getRequestURI().toString().contains("final=true")) {
                exchange.getResponseHeaders().add("Location", uri() + "&final=true");
                exchange.sendResponseHeaders(302, responseBytes.length);
            } else {
                redirectFollowed = true;
                exchange.sendResponseHeaders(200, responseBytes.length);
            }
            OutputStream outputStream = exchange.getResponseBody();
            outputStream.write(responseBytes);
            outputStream.close();
        }
    }

    public TestWebrevServer() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        httpServer = HttpServer.create(address, 0);
        httpServer.createContext("/webrevs", new Handler());
        httpServer.setExecutor(null);
        httpServer.start();
    }

    public URI uri() {
        return URIBuilder.base("http://" + httpServer.getAddress().getHostString() + ":" +  httpServer.getAddress().getPort() + "/webrevs/").build();
    }

    public boolean isChecked() {
        return checked;
    }

    public boolean isRedirectFollowed() {
        return redirectFollowed;
    }

    public void setHandleCallback(Consumer<URI> callback) {
        if (handleCallback != null) {
            throw new IllegalStateException("Can only set callback once");
        }
        handleCallback = callback;
    }

    @Override
    public void close() throws IOException {
        httpServer.stop(0);
    }
}
