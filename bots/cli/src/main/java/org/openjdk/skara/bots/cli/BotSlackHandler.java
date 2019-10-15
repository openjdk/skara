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
import org.openjdk.skara.network.*;
import org.openjdk.skara.json.JSON;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class BotSlackHandler extends BotTaskAggregationHandler {

    private final RestRequest webhook;
    private final String username;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.cli");;
    private final Duration minimumSeparation;
    private final Map<Pattern, String> linkPatterns;
    private Instant lastUpdate;
    private int dropCount;

    BotSlackHandler(URI webhookUrl, String username, Duration minimumSeparation, Map<String, String> links) {
        webhook = new RestRequest(webhookUrl);
        this.username = username;
        this.minimumSeparation = minimumSeparation;
        linkPatterns = links.entrySet().stream()
                            .collect(Collectors.toMap(entry -> Pattern.compile(entry.getKey(),
                                                                               Pattern.MULTILINE | Pattern.DOTALL),
                                                      Map.Entry::getValue));
        lastUpdate = Instant.EPOCH;
        dropCount = 0;
    }

    private Optional<String> getLink(String message) {
        for (var linkPattern : linkPatterns.entrySet()) {
            var matcher = linkPattern.getKey().matcher(message);
            if (matcher.find()) {
                return Optional.of(matcher.replaceFirst(linkPattern.getValue()));
            }
        }
        return Optional.empty();
    }

    private void publishToSlack(String message) {
        try {
            if (lastUpdate.plus(minimumSeparation).isAfter(Instant.now())) {
                dropCount++;
                return;
            }

            if (dropCount > 0) {
                message = "_*" + dropCount + "* previous message(s) silently dropped due to throttling_\n" +
                        message;
            }
            lastUpdate = Instant.now();
            dropCount = 0;

            var query = JSON.object();
            query.put("text", message);
            if (username != null) {
                query.put("username", username);
            }

            var link = getLink(message);
            if (link.isPresent()) {
                var attachment = JSON.object();
                attachment.put("fallback", "Details link");
                attachment.put("color", "#cc0e31");
                attachment.put("title", "Click for more details");
                attachment.put("title_link", link.get());
                var attachments = JSON.array();
                attachments.add(attachment);
                query.put("attachments", attachments);
            }

            webhook.post("").body(query).executeUnparsed();
        } catch (RuntimeException e) {
            log.warning("Exception during slack notification posting: " + e.getMessage());
            log.throwing("BotSlackHandler", "publish", e);
        }
    }

    @Override
    public void publishAggregated(List<LogRecord> task) {
        var maxLevel = task.stream()
                           .map(record -> record.getLevel().intValue())
                           .max(Integer::compareTo)
                           .orElseThrow();

        if (maxLevel < getLevel().intValue()) {
            return;
        }

        var important = task.stream()
                            .filter(record -> record.getLevel().intValue() >= getLevel().intValue())
                            .map(record -> "`" + record.getLevel().getName() + "` " + record.getMessage())
                            .collect(Collectors.joining("\n"));
        publishToSlack(important);
    }

    @Override
    public void publishSingle(LogRecord record) {
        if (record.getLevel().intValue() < getLevel().intValue()) {
            return;
        }

        var message = "`" + record.getLevel().getName() + "` " + record.getMessage();
        publishToSlack(message);
    }
}
