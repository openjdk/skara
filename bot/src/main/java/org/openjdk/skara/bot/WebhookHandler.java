/*
 * Copyright (c) 2018, 2021 Oracle and/or its affiliates. All rights reserved.
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
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

class WebhookHandler implements HttpHandler {
    private final static Logger log = Logger.getLogger("org.openjdk.skara.bot");
    private final BotRunner runner;

    private WebhookHandler(BotRunner runner) {
        this.runner = runner;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        JSONValue json = null;
        try {
            json = JSON.parse(input);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to parse incoming request: " + input, e);
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().close();
            return;
        }

        // Reply immediately
        var response = "{}";
        exchange.sendResponseHeaders(200, response.length());
        var output = exchange.getResponseBody();
        output.write(response.getBytes(StandardCharsets.UTF_8));
        output.close();

        runner.processWebhook(json);
    }

    static String name() {
        return "webhook";
    }

    static WebhookHandler create(BotRunner runner, JSONObject configuration) {
        return new WebhookHandler(runner);
    }
}
