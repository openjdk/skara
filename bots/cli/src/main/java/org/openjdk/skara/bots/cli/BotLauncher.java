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

import org.openjdk.skara.args.*;
import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.network.URIBuilder;
import org.openjdk.skara.json.*;
import org.openjdk.skara.proxy.HttpProxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.*;
import java.util.stream.*;

public class BotLauncher {
    private static Logger log;

    private static void applyLogging(JSONObject config) {
        LogManager.getLogManager().reset();
        log = Logger.getLogger("org.openjdk");
        log.setLevel(Level.FINEST);

        if (!config.contains("log")) {
            return;
        }

        if (config.get("log").asObject().contains("console")) {
            var level = Level.parse(config.get("log").get("console").get("level").asString());
            var handler = new BotConsoleHandler();
            handler.setLevel(level);
            log.addHandler(handler);
        }

        if (config.get("log").asObject().contains("slack")) {
            var maxRate = Duration.ofMinutes(10);
            if (config.get("log").get("slack").asObject().contains("maxrate")) {
                maxRate = Duration.parse(config.get("log").get("slack").get("maxrate").asString());
            }
            var level = Level.parse(config.get("log").get("slack").get("level").asString());
            Map<String, String> details = new HashMap<>();
            if (config.get("log").get("slack").asObject().contains("details")) {
                details = config.get("log").get("slack").get("details").asArray().stream()
                                .collect(Collectors.toMap(o -> o.get("pattern").asString(),
                                                          o -> o.get("link").asString()));
            }
            var handler = new BotSlackHandler(URIBuilder.base(config.get("log").get("slack").get("webhook").asString()).build(),
                                              config.get("log").get("slack").get("username").asString(),
                                              maxRate,
                                              details);
            handler.setLevel(level);
            log.addHandler(handler);
        }

        if (config.get("log").asObject().contains("logstash")) {
            var logstashConf = config.get("log").get("logstash").asObject();
            var level = Level.parse(logstashConf.get("level").asString());
            var maxRecords = 100;
            if (logstashConf.contains("maxrecords")) {
                maxRecords = logstashConf.get("maxrecords").asInt();
            }
            var handler = new BotLogstashHandler(URIBuilder.base(logstashConf.get("endpoint").asString()).build(), maxRecords);
            if (logstashConf.contains("fields")) {
                for (var field : logstashConf.get("fields").asArray()) {
                    if (field.asObject().contains("pattern")) {
                        handler.addExtraField(field.get("name").asString(),
                                field.get("value").asString(),
                                field.get("pattern").asString());
                    } else {
                        handler.addExtraField(field.get("name").asString(),
                                field.get("value").asString());
                    }
                }
            }
            handler.setLevel(level);
            log.addHandler(handler);
        }
    }

    private static JSONObject readConfiguration(Path jsonFile) {
        try {
            return JSON.parse(Files.readString(jsonFile, StandardCharsets.UTF_8)).asObject();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open configuration file: " + jsonFile);
        }
    }

    public static void main(String... args) {
        HttpProxy.setup();

        var flags = List.of(
                Option.shortcut("t")
                      .fullname("timeout")
                      .describe("ISO8601")
                      .helptext("When running once, only run for this long (default 1 hour)")
                      .optional(),
                Switch.shortcut("o")
                      .fullname("once")
                      .helptext("Instead of repeatedly executing periodical task, run each task exactly once")
                      .optional(),
                Switch.shortcut("l")
                      .fullname("list-bots")
                      .helptext("List all available bots and then exit")
                      .optional());
        var inputs = List.of(
                Input.position(0)
                     .describe("configuration.json")
                     .singular()
                     .required());
        var parser = new ArgumentParser("bots", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("list-bots")) {
            var botFactories = BotFactory.getBotFactories();
            System.out.println("Number of available bots: " + botFactories.size());
            for (var botFactory : botFactories) {
                System.out.println(" - " + botFactory.name() + " (" + botFactory.getClass().getModule() + ")");
            }
            System.exit(0);
        }

        Path jsonFile = arguments.at(0).via(Paths::get);
        var jsonConfig = readConfiguration(jsonFile);

        applyLogging(jsonConfig);
        var log = Logger.getLogger("org.openjdk.skara.bots.cli");

        BotRunnerConfiguration runnerConfig = null;
        try {
            runnerConfig = BotRunnerConfiguration.parse(jsonConfig, jsonFile.getParent());
        } catch (ConfigurationError configurationError) {
            System.out.println("Failed to parse configuration file: " + jsonFile);
            System.out.println("Error message: " + configurationError.getMessage());
            System.exit(1);
        }

        var botFactories = BotFactory.getBotFactories().stream()
                                     .collect(Collectors.toMap(BotFactory::name, Function.identity()));
        if (botFactories.size() == 0) {
            System.out.println("Error: no bot factories found. Make sure the module path is correct. Exiting...");
            System.exit(1);
        }

        var bots = new ArrayList<Bot>();

        for (var botEntry : botFactories.entrySet()) {
            try {
                var botConfig = runnerConfig.perBotConfiguration(botEntry.getKey());
                bots.addAll(botEntry.getValue().create(botConfig));
            } catch (ConfigurationError configurationError) {
                log.info("No configuration for available bot '" + botEntry.getKey() + "', skipping...");
            }
        }

        var runner = new BotRunner(runnerConfig, bots);

        try {
            if (arguments.contains("once")) {
                runner.runOnce(arguments.get("timeout").or("PT60M").via(Duration::parse));
            } else {
                runner.run();
            }
        } catch (TimeoutException e) {
            e.printStackTrace();
        }
    }
}
