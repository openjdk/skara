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

import org.openjdk.skara.bot.LogContextMap;
import org.openjdk.skara.json.JSON;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Future;
import java.util.logging.*;
import java.util.regex.Pattern;

/**
 * Handles logging to logstash. Be careful not to call anything that creates new
 * log records from this class as that can cause infinite recursion.
 */
public class BotLogstashHandler extends StreamHandler {
    private final URI endpoint;
    private final HttpClient httpClient;
    private final DateTimeFormatter dateTimeFormatter;
    // Optionally store all futures for testing purposes
    private Collection<Future<HttpResponse<Void>>> futures;

    record RegexReplacement(Pattern pattern, String replacement) {}

    private List<RegexReplacement> regexReplacements = new ArrayList<>();

    private static class ExtraField {
        String name;
        String value;
        Pattern pattern;
    }

    private final List<ExtraField> extraFields;

    BotLogstashHandler(URI endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
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

    void addReplacement(String pattern, String replacement) {
        regexReplacements.add(new RegexReplacement(Pattern.compile(pattern), replacement));
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

    private String applyReplacements(String s) {
        CharSequence ret = s;
        for (RegexReplacement regexReplacement : regexReplacements) {
            var matcher = regexReplacement.pattern.matcher(ret);
            var sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb, regexReplacement.replacement);
            }
            matcher.appendTail(sb);
            ret = sb;
        }
        return ret.toString();
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() < getLevel().intValue()) {
            return;
        }
        Level level = record.getLevel();
        var query = JSON.object();
        query.put("@timestamp", dateTimeFormatter.format(record.getInstant()));
        query.put("level", level.getName());
        query.put("level_value", level.intValue());
        query.put("message", applyReplacements(record.getMessage()));

        if (record.getLoggerName() != null) {
            query.put("logger_name", record.getLoggerName());
        }

        var parameters = record.getParameters();
        if (parameters != null) {
            for (var parameter : parameters) {
                if (parameter instanceof Duration duration) {
                    query.put("duration", duration.toMillis());
                }
            }
        }

        if (record.getThrown() != null) {
            var writer = new StringWriter();
            var printer = new PrintWriter(writer);
            record.getThrown().printStackTrace(printer);
            query.put("stack_trace", writer.toString());
        }

        for (var entry : LogContextMap.entrySet()) {
            query.put(entry.getKey(), entry.getValue());
        }

        for (var extraField : getExtraFields(record).entrySet()) {
            query.put(extraField.getKey(), extraField.getValue());
        }

        var httpRequest = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query.toString()))
                .build();
        var future = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.discarding());
        // Save futures in optional collection when running tests.
        if (futures != null) {
            futures.add(future);
        }
    }

    void setFuturesCollection(Collection<Future<HttpResponse<Void>>> futures) {
        this.futures = futures;
    }
}
