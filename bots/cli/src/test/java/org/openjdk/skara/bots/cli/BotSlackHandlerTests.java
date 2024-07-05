/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.cli;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class BotSlackHandlerTests {

    @BeforeAll
    static void setUp() {
        Logger log = Logger.getGlobal();
        log.setLevel(Level.FINER);
        log = Logger.getLogger("org.openjdk.skara.bot");
        log.setLevel(Level.FINER);
    }

    @Test
    void simple() throws IOException {

        try (var receiver = new RestReceiver()) {
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", "`testc` ", Duration.ofSeconds(1), new HashMap<>());
            var record = new LogRecord(Level.INFO, "Hello");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertEquals("test", requests.get(0).get("username").asString());
            assertEquals("`testc` `INFO` Hello", requests.get(0).get("text").asString());
        }
    }

    @Test
    void noUser() throws IOException {

        try (var receiver = new RestReceiver()) {
            var handler = new BotSlackHandler(receiver.getEndpoint(), null, null, Duration.ofSeconds(1), new HashMap<>());
            var record = new LogRecord(Level.INFO, "Hello");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertNull(requests.get(0).get("username"));
            assertEquals("`INFO` Hello", requests.get(0).get("text").asString());
        }
    }

    @Test
    void throttled() throws IOException, InterruptedException {
        try (var receiver = new RestReceiver()) {
            final var maxDuration = Duration.ofMillis(1500);
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", null, maxDuration, new HashMap<>());

            // Post until we hit throttling
            var posted = 0;
            var maxAttempts = 10000;
            var wasThrottled = false;
            while (posted < maxAttempts) {
                var record = new LogRecord(Level.INFO, "Hello");
                handler.publish(record);

                posted++;
                if (receiver.getRequests().size() != posted) {
                    wasThrottled = true;
                    break;
                }
            }
            assertTrue(wasThrottled, "Did not get throttled, is maxDuration too low?");

            var requests = receiver.getRequests();
            var lastRequest = requests.getLast().asObject();
            assertEquals("test", lastRequest.get("username").asString(), lastRequest.toString());
            assertTrue(lastRequest.get("text").asString().contains("Hello"), lastRequest.toString());

            Thread.sleep(maxDuration);
            var record = new LogRecord(Level.INFO, "Hello a final time!");
            handler.publish(record);
            lastRequest = requests.getLast().asObject();
            assertEquals("test", lastRequest.get("username").asString(), lastRequest.toString());
            assertTrue(lastRequest.get("text").asString().contains("Hello a final time!"), lastRequest.toString());
            assertTrue(lastRequest.get("text").asString().contains("dropped"), lastRequest.toString());
        }
    }

    @Test
    void unthrottled() throws IOException, InterruptedException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", null, Duration.ofMillis(1), new HashMap<>());
            var record = new LogRecord(Level.INFO, "Hello");
            handler.publish(record);
            Thread.sleep(10);
            record = new LogRecord(Level.INFO, "Hello again!");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(2, requests.size());
            assertEquals("test", requests.get(0).get("username").asString());
            assertTrue(requests.get(0).get("text").asString().contains("Hello"));
            assertEquals("test", requests.get(1).get("username").asString());
            assertTrue(requests.get(1).get("text").asString().contains("Hello again!"));
        }
    }

    @Test
    void detailsLink() throws IOException {
        try (var receiver = new RestReceiver()) {
            var details = new HashMap<String, String>();
            details.put(".*error: (xyz).*", "http://go.to/$1");
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", null, Duration.ofMillis(1), details);

            var record = new LogRecord(Level.INFO, "Something bad happened. error: xyz occurred");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            var request = requests.get(0).asObject();
            assertEquals("test", request.get("username").asString());
            assertTrue(request.get("text").asString().contains("Something bad"));
            assertEquals(1, request.get("attachments").asArray().size());

            var attachment = request.get("attachments").asArray().get(0);
            assertTrue(attachment.get("title_link").asString().contains("go.to/xyz"));
        }
    }

    @Test
    void detailsNotMatching() throws IOException {
        try (var receiver = new RestReceiver()) {
            var details = new HashMap<String, String>();
            details.put(".*error: (xyz).*", "http://go.to/$1");
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", null, Duration.ofMillis(1), details);

            var record = new LogRecord(Level.INFO, "Something bad happened. error: abc occurred");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            var request = requests.get(0).asObject();
            assertEquals("test", request.get("username").asString());
            assertTrue(request.get("text").asString().contains("Something bad"));
            assertFalse(request.contains("attachments"));
        }
    }

    @Test
    void taskLog() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", null, Duration.ZERO, new HashMap<>());

            LoggingBot.runOnce(handler, log -> {
                log.warning("Hello");
                log.warning("Bye");
            });

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertEquals("test", requests.get(0).get("username").asString());
            assertTrue(requests.get(0).get("text").asString().contains("Hello"), requests.get(0).get("text").asString());
            assertTrue(requests.get(0).get("text").asString().contains("Bye"), requests.get(0).get("text").asString());
        }
    }

    @Test
    void taskLogDetailsLink() throws IOException {
        try (var receiver = new RestReceiver()) {
            var details = new HashMap<String, String>();
            details.put("error: (def) occured$", "http://go.to/$1");
            var handler = new BotSlackHandler(receiver.getEndpoint(), "test", null, Duration.ZERO, details);

            LoggingBot.runOnce(handler, log -> {
                log.warning("Hello");
                log.warning("Something bad happened. error: def occured");
                log.warning("Bye");
            });

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            var request = requests.get(0).asObject();
            assertEquals("test", request.get("username").asString());
            assertTrue(request.get("text").asString().contains("Hello"), request.get("text").asString());
            assertTrue(request.get("text").asString().contains("Bye"), request.get("text").asString());

            assertEquals(1, request.get("attachments").asArray().size());

            var attachment = request.get("attachments").asArray().get(0);
            assertTrue(attachment.get("title_link").asString().contains("go.to/def"));
        }

    }
}
