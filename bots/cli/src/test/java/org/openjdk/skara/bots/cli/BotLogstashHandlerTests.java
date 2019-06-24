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
package org.openjdk.skara.bots.cli;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.logging.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BotLogstashHandlerTests {
    @Test
    void simple() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 100);
            var record = new LogRecord(Level.INFO, "Hello");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertTrue(requests.get(0).get("message").asString().contains("Hello"));
            assertTrue(requests.get(0).get("level").asString().contains(Level.INFO.getName()));
        }
    }

    @Test
    void simpleTask() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 100);

            LoggingBot.runOnce(handler, log -> {
                log.warning("Hello");
                log.warning("Warning!");
                log.warning("Bye");
            });

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertEquals(Level.WARNING.getName(), requests.get(0).get("level").asString());
            assertEquals(Level.WARNING.intValue(), requests.get(0).get("level_value").asInt());
            assertTrue(requests.get(0).get("message").asString().contains("Hello"));
            assertTrue(requests.get(0).get("message").asString().contains("Warning"));
            assertTrue(requests.get(0).get("message").asString().contains("Bye"));
            assertTrue(requests.get(0).get("message").asString().contains(Level.WARNING.toString()));
        }
    }

    @Test
    void extraField() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 100);
            handler.addExtraField("mandatory", "value");
            handler.addExtraField("optional1", "$1", "^H(ello)$");
            handler.addExtraField("optional2", "$1", "^(Not found)$");
            var record = new LogRecord(Level.INFO, "Hello");
            handler.publish(record);

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertEquals("value", requests.get(0).get("mandatory").asString());
            assertEquals("ello", requests.get(0).get("optional1").asString());
            assertFalse(requests.get(0).contains("optional2"));
        }
    }

    @Test
    void extraFieldTask() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 100);
            handler.addExtraField("mandatory", "value");
            handler.addExtraField("optional1", "$1", "^H(ello)$");
            handler.addExtraField("optional2", "$1", "^(Not found)$");
            handler.addExtraField("optional3", "$1", "^B(ye)$");
            handler.addExtraField("greedy", "$1", "^(.*)$");

            LoggingBot.runOnce(handler, log -> {
                log.warning("Hello");
                log.warning("Warning!");
                log.warning("Bye");
            });

            var requests = receiver.getRequests();
            assertEquals(1, requests.size(), requests.toString());
            assertEquals("value", requests.get(0).get("mandatory").asString());
            assertEquals("ello", requests.get(0).get("optional1").asString());
            assertFalse(requests.get(0).contains("optional2"));
            assertEquals("ye", requests.get(0).get("optional3").asString());
            assertTrue(requests.get(0).get("greedy").asString().contains("Executing item"));
        }
    }

    @Test
    void filterLowLevels() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 10);

            LoggingBot.runOnce(handler, Level.FINER, log -> {
                for (int i = 0; i < 5; ++i) {
                    log.fine("Fine nr " + i);
                }
                for (int i = 0; i < 5; ++i) {
                    log.finer("Finer nr " + i);
                }
            });

            var requests = receiver.getRequests();
            var aggregatedLines = requests.stream()
                                          .filter(request -> request.get("message").asString().contains("Executing item"))
                                          .findAny()
                                          .orElseThrow()
                                          .get("message")
                                          .asString()
                                          .lines()
                                          .collect(Collectors.toList());

            var fineLines = aggregatedLines.stream()
                                           .filter(line -> line.contains("Fine nr"))
                                           .collect(Collectors.toList());
            var finerLines = aggregatedLines.stream()
                                            .filter(line -> line.contains("Finer nr"))
                                            .collect(Collectors.toList());
            assertEquals(5, fineLines.size(), aggregatedLines.toString());
            assertEquals(0, finerLines.size(), aggregatedLines.toString());
        }
    }

    @Test
    void filterLowestLevels() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 15);

            LoggingBot.runOnce(handler, Level.FINER, log -> {
                for (int i = 0; i < 5; ++i) {
                    log.fine("Fine nr " + i);
                }
                for (int i = 0; i < 5; ++i) {
                    log.finer("Finer nr " + i);
                }
                for (int i = 0; i < 5; ++i) {
                    log.finest("Finest nr " + i);
                }
            });

            var requests = receiver.getRequests();
            var aggregatedLines = requests.stream()
                                          .filter(request -> request.get("message").asString().contains("Executing item"))
                                          .findAny()
                                          .orElseThrow()
                                          .get("message")
                                          .asString()
                                          .lines()
                                          .collect(Collectors.toList());

            var fineLines = aggregatedLines.stream()
                                           .filter(line -> line.contains("Fine nr"))
                                           .collect(Collectors.toList());
            var finerLines = aggregatedLines.stream()
                                            .filter(line -> line.contains("Finer nr"))
                                            .collect(Collectors.toList());
            var finestLines = aggregatedLines.stream()
                                             .filter(line -> line.contains("Finest nr"))
                                             .collect(Collectors.toList());
            assertEquals(5, fineLines.size(), aggregatedLines.toString());
            assertEquals(5, finerLines.size(), aggregatedLines.toString());
            assertEquals(0, finestLines.size(), aggregatedLines.toString());
        }
    }

    @Test
    void filterMiddle() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint(), 100);

            LoggingBot.runOnce(handler, Level.FINER, log -> {
                for (int i = 0; i < 100; ++i) {
                    log.fine("Start nr " + i);
                }
                for (int i = 0; i < 100; ++i) {
                    log.fine("Middle nr " + i);
                }
                for (int i = 0; i < 100; ++i) {
                    log.fine("End nr " + i);
                }
            });

            var requests = receiver.getRequests();
            var aggregatedLines = requests.stream()
                                          .filter(request -> request.get("message").asString().contains("Executing item"))
                                          .findAny()
                                          .orElseThrow()
                                          .get("message")
                                          .asString();
            assertTrue(aggregatedLines.contains("Start nr"));
            assertFalse(aggregatedLines.contains("Middle nr"));
            assertTrue(aggregatedLines.contains("End nr"));
        }
    }
}
