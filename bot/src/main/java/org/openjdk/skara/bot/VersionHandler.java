/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.version.Version;

import com.sun.net.httpserver.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

class VersionHandler implements HttpHandler {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bot");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var version = Version.fromManifest();
        if (version.isPresent()) {
            var bytes = version.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        } else {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        }
    }

    static VersionHandler create(BotRunner runner, JSONObject configuration) {
        return new VersionHandler();
    }

    static String name() {
        return "version";
    }
}
