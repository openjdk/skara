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

import org.openjdk.skara.bot.BotTaskAggregationHandler;
import org.openjdk.skara.network.RestRequest;
import org.openjdk.skara.json.JSON;

import java.io.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BotLogstashHandler extends BotTaskAggregationHandler {
    private final RestRequest endpoint;
    private final DateTimeFormatter dateTimeFormatter;
    private final int maxRecords;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.cli");


    private static class ExtraField {
        String name;
        String value;
        Pattern pattern;
    }

    private final List<ExtraField> extraFields;

    BotLogstashHandler(URI endpoint, int maxRecords) {
        this.endpoint = new RestRequest(endpoint);
        this.maxRecords = maxRecords;
        dateTimeFormatter = DateTimeFormatter.ISO_INSTANT
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault());
        extraFields = new ArrayList<>();
    }

    void addExtraField(String name, String value) {
        addExtraField(name, value, null);
    }

    void addExtraField(String name, String value, String pattern) {
        var extraField = new ExtraField();
        extraField.name = name;
        extraField.value = value;
        if (pattern != null) {
            extraField.pattern = Pattern.compile(pattern);
        }
        extraFields.add(extraField);
    }

    private void publishToLogstash(Instant time, Level level, String message, Map<String, String> extraFields) {
        try {
            var query = JSON.object();
            query.put("@timestamp", dateTimeFormatter.format(time));
            query.put("level", level.getName());
            query.put("level_value", level.intValue());
            query.put("message", message);

            for (var extraField : extraFields.entrySet()) {
                query.put(extraField.getKey(), extraField.getValue());
            }

            endpoint.post("/")
                    .body(query)
                    .executeUnparsed();
        } catch (RuntimeException | IOException e) {
            log.warning("Exception during logstash publishing: " + e.getMessage());
            log.throwing("BotSlackHandler", "publish", e);
        }
    }

    private String formatDuration(Duration duration) {
        return String.format("[%02d:%02d]", duration.toMinutes(), duration.toSeconds() % 60);
    }

    private String formatRecord(Instant base, LogRecord record) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.print(formatDuration(Duration.between(base, record.getInstant())));
        printer.print("[");
        printer.print(record.getLevel().getName());
        printer.print("] ");
        printer.print(record.getMessage());

        var exception = record.getThrown();
        if (exception != null) {
            exception.printStackTrace(printer);
        }

        return writer.toString().stripTrailing();
    }

    private Map<String, String> getExtraFields(LogRecord record) {
        var ret = new HashMap<String, String>();
        for (var extraField : extraFields) {
            if (extraField.pattern != null) {
                var matcher = extraField.pattern.matcher(record.getMessage());
                if (matcher.matches()) {
                    var value = matcher.replaceFirst(extraField.value);
                    ret.put(extraField.name, value);
                }
            } else {
                ret.put(extraField.name, extraField.value);
            }
        }
        return ret;
    }

    // Remove every entry below minLevel
    private List<LogRecord> filterRecords(List<LogRecord> records, Level minLevel) {
        return records.stream()
                .filter(entry -> entry.getLevel().intValue() >= minLevel.intValue())
                .collect(Collectors.toList());
    }

    @Override
    public void publishAggregated(List<LogRecord> task) {
        var maxLevel = task.stream()
                           .max(Comparator.comparingInt(r -> r.getLevel().intValue()))
                           .map(LogRecord::getLevel)
                           .orElseThrow();
        if (maxLevel.intValue() < getLevel().intValue()) {
            return;
        }

        var start = task.get(0).getInstant();

        // For duplicate keys, the first value seen is retained
        var concatenatedFields = task.stream()
                .map(this::getExtraFields)
                .flatMap(extra -> extra.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                          (value1, value2) -> value1));

        // First try to accommodate size limit by filtering out low level logging
        if (task.size() > maxRecords) {
            task = filterRecords(task, Level.FINER);
        }
        if (task.size() > maxRecords) {
            task = filterRecords(task, Level.FINE);
        }

        // If there's still too many lines, strip out the middle
        if (task.size() > maxRecords) {
            var beginning = task.subList(0, maxRecords / 2);
            var end = task.subList(task.size() - maxRecords / 2, task.size());
            task = beginning;
            task.addAll(end);
        }

        var concatenatedMessage = task.stream()
                                      .map(record -> formatRecord(start, record))
                                      .collect(Collectors.joining("\n"));

        publishToLogstash(start, maxLevel, concatenatedMessage, concatenatedFields);
    }

    @Override
    public void publishSingle(LogRecord record) {
        if (record.getLevel().intValue() < getLevel().intValue()) {
            return;
        }
        publishToLogstash(record.getInstant(), record.getLevel(), record.getMessage(), getExtraFields(record));
    }
}
