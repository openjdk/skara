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
            var handler = new BotLogstashHandler(receiver.getEndpoint());
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
            var handler = new BotLogstashHandler(receiver.getEndpoint());

            LoggingBot.runOnce(handler, log -> {
                log.warning("Hello");
                log.warning("Warning!");
                log.warning("Bye");
            });

            var requests = receiver.getRequests();
            assertEquals(3, requests.size(), requests.toString());
            assertEquals(Level.WARNING.getName(), requests.get(0).get("level").asString());
            assertEquals(Level.WARNING.intValue(), requests.get(0).get("level_value").asInt());
            assertEquals("Hello", requests.get(0).get("message").asString());
            assertEquals("Warning!", requests.get(1).get("message").asString());
            assertEquals("Bye", requests.get(2).get("message").asString());
            assertEquals(Level.WARNING.toString(), requests.get(0).get("level").asString());
            assertNotNull(requests.get(0).get("work_id"), "work_id not set");
            assertTrue(requests.get(0).get("work_item").asString().contains("LoggingBot@"),
                    "work_item has bad value " + requests.get(0).get("work_item").asString());
        }
    }

    @Test
    void extraField() throws IOException {
        try (var receiver = new RestReceiver()) {
            var handler = new BotLogstashHandler(receiver.getEndpoint());
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
            var handler = new BotLogstashHandler(receiver.getEndpoint());
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
            assertEquals(3, requests.size(), requests.toString());
            assertEquals("value", requests.get(0).get("mandatory").asString());
            assertEquals("ello", requests.get(0).get("optional1").asString());
            assertFalse(requests.get(0).contains("optional2"));
            assertEquals("ye", requests.get(2).get("optional3").asString());
        }
    }
}
