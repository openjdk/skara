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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import jdk.jfr.*;
import java.text.ParseException;
import java.util.concurrent.locks.ReentrantLock;

class ProfileHandler implements HttpHandler {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bot");
    private final Path configurationPath;
    private final int maxDuration;
    private final ReentrantLock lock = new ReentrantLock();

    private ProfileHandler(Path configurationPath, int maxDuration) {
        this.configurationPath = configurationPath;
        this.maxDuration = maxDuration;
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        var query = exchange.getRequestURI().getQuery();
        var parts = query.split("&");
        var result = new HashMap<String, String>();
        for (var part : parts) {
            var keyAndValue = part.split("=");
            result.put(keyAndValue[0], keyAndValue[1]);
        }
        return result;
    }

    private void handleLocked(HttpExchange exchange) throws IOException {
        var params = parameters(exchange);
        var seconds = params.getOrDefault("seconds", "30");
        var configurationName = params.getOrDefault("configuration", "profile");

        Configuration configuration = null;
        try {
            configuration = Configuration.create(configurationPath);
        } catch (ParseException e) {
            log.log(Level.WARNING, "Could not get JFR configuration", e);
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        }

        log.info("Profiling for " + seconds + " seconds with configuration " + configurationName);
        var recording = new Recording(configuration);
        recording.start();

        try {
            var duration = Integer.parseInt(seconds);
            if (duration > maxDuration) {
                duration = maxDuration;
            }
            Thread.sleep(duration * 1000);
        } catch (InterruptedException e) {
            log.log(Level.WARNING, "Thread interrupted when sleeping", e);
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        }

        recording.stop();
        var path = Files.createTempFile("recording", "jfr");
        recording.dump(path);

        var buffer = new byte[4096];
        exchange.sendResponseHeaders(200, Files.size(path));
        try (var output = exchange.getResponseBody(); var stream = Files.newInputStream(path)) {
            while (true) {
                var read = stream.read(buffer);
                if (read == -1) {
                    break;
                }
                output.write(buffer, 0, read);
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "Could not send JFR recording", t);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only allow one recording at a time.
        lock.lock();
        try {
            handleLocked(exchange);
        } finally {
            lock.unlock();
        }
    }

    static ProfileHandler create(BotRunner runner, JSONObject configuration) {
        var configurationPath = Path.of(configuration.get("configuration").asString());
        var maxDuration = configuration.get("max-duration").asInt();
        return new ProfileHandler(configurationPath, maxDuration);
    }

    static String name() {
        return "profile";
    }
}
