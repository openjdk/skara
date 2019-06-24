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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.*;

class BotConsoleHandler extends StreamHandler {

    private final DateTimeFormatter dateTimeFormatter;
    private final Map<Integer, String> levelAbbreviations;

    BotConsoleHandler() {
        dateTimeFormatter = DateTimeFormatter.ISO_INSTANT
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault());

        levelAbbreviations = new HashMap<>();
        levelAbbreviations.put(Level.INFO.intValue(), "I");
        levelAbbreviations.put(Level.FINE.intValue(), "F");
        levelAbbreviations.put(Level.FINER.intValue(), "finer");
        levelAbbreviations.put(Level.FINEST.intValue(), "finest");
        levelAbbreviations.put(Level.SEVERE.intValue(), "E");
        levelAbbreviations.put(Level.WARNING.intValue(), "W");
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < getLevel().intValue()) {
            return;
        }

        var level = levelAbbreviations.getOrDefault(record.getLevel().intValue(), "?");
        System.out.println("[" + dateTimeFormatter.format(record.getInstant().truncatedTo(ChronoUnit.SECONDS)) + "][" + record.getThreadID() + "][" +
                level + "] " + record.getMessage());
        var exception = record.getThrown();
        if (exception != null) {
            exception.printStackTrace(System.out);
        }
        System.out.flush();
    }
}
