/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import static org.openjdk.skara.bots.common.CommandNameEnum.trailer;

public class TrailerCommand implements CommandHandler {
    public enum TrailerType {
        SINGLE,
        LIST;

        static TrailerType fromString(String value) {
            return switch (value) {
                case "single" -> SINGLE;
                case "list" -> LIST;
                default -> throw new IllegalArgumentException("Unknown trailer type: " + value
                        + ", expected one of: single, list");
            };
        }
    }

    public record TrailerConfig(String key, String alias, String description, TrailerType type, List<Pattern> values) {}

    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "^(?:(set)\\s+([\\p{Alnum}-]+) (.+)?|(remove)\\s+([\\p{Alnum}-]+))$");

    @Override
    public String description() {
        return "Set or remove a custom commit message trailer";
    }

    @Override
    public String name() {
        return trailer.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }

    private void showHelp(PullRequestBot bot, PrintWriter reply) {
        reply.println("Syntax: `/trailer (set|remove) (<key>|<alias>) [<value>]`. For example:");
        reply.println();
        reply.println(" * `/trailer set My-Custom-Trailer Some custom trailer value`");
        reply.println(" * `/trailer remove My-Custom-Trailer`");
        reply.println();
        reply.println("Only trailer keys that have been configured for the repository are allowed.");
        if (bot.trailerConfigs().isEmpty()) {
            reply.println("No custom trailers configured for this repository.");
        } else {
            printConfiguredTrailers(bot, reply);
        }
    }

    private static void printConfiguredTrailers(PullRequestBot bot, PrintWriter reply) {
        reply.println("For this repository, the following custom trailers have been configured:");
        for (TrailerConfig trailerConfig : bot.trailerConfigs()) {
            reply.println();
            reply.println("- Key: " + trailerConfig.key);
            if (trailerConfig.alias != null) {
                reply.println("- Alias: " + trailerConfig.alias);
            }
            reply.println("- Description: " + trailerConfig.description);
            reply.println("- Type: " + trailerConfig.type);
            reply.println("- Valid value pattern(s):");
            for (Pattern pattern : trailerConfig.values()) {
                reply.println("  - `" + pattern.pattern() + "`");
            }
        }
    }

    private static boolean matchesAnyPattern(String value, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(value).matches());
    }

    private static boolean isValidValue(TrailerConfig config, String value) {
        return switch (config.type()) {
            case SINGLE -> matchesAnyPattern(value, config.values());
            case LIST -> Arrays.stream(value.split(",", -1))
                    .map(String::trim)
                    .allMatch(item -> !item.isEmpty() && matchesAnyPattern(item, config.values()));
        };
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance,
            ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().username() + ") is allowed to issue the `trailer` command.");
            return;
        }

        var matcher = COMMAND_PATTERN.matcher(command.args());
        if (!matcher.matches()) {
            showHelp(bot, reply);
            return;
        }

        if ("set".equals(matcher.group(1))) {
            var key = matcher.group(2);
            var config = findTrailerConfig(key, bot, reply);
            if (config.isPresent()) {
                var configKey = config.get().key();
                var value = matcher.group(3);
                if (isValidValue(config.get(), value)) {
                    reply.println(Trailers.setTrailerMarker(configKey, value));
                    reply.println("Trailer `" + configKey + "` with value `" + value + "` successfully set.");
                } else {
                    if (config.get().type() == TrailerType.LIST) {
                        reply.println("Trailer value `" + value + "` for trailer `" + configKey
                                + "` does not match any valid value pattern. Each list item must match one of:");
                    } else {
                        reply.println("Trailer value `" + value + "` for trailer `" + configKey
                                + "` does not match any valid value pattern:");
                    }
                    for (Pattern pattern : config.get().values()) {
                        reply.println("- `" + pattern.pattern() + "`");
                    }
                }
            }
        } else if ("remove".equals(matcher.group(4))) {
            var key = matcher.group(5);
            var config = findTrailerConfig(key, bot, reply);
            if (config.isPresent()) {
                var configKey = config.get().key();
                var existing = Trailers.trailers(pr.repository().forge().currentUser(), allComments);
                if (existing.stream().anyMatch(trailer -> trailer.key().equals(configKey))) {
                    reply.println(Trailers.removeTrailerMarker(configKey));
                    reply.println("Trailer `" + configKey + "` successfully removed.");
                } else {
                    if (existing.isEmpty()) {
                        reply.println("There are no custom trailers set for this pull request.");
                    } else {
                        reply.println("Trailer `" + configKey + "` was not found.");
                        reply.println("Current custom trailers for this pull request are:");
                        for (var trailer : existing) {
                            reply.println("- " + trailer.key() + ": " + trailer.value());
                        }
                    }
                }
            }
        }
    }

    private Optional<TrailerConfig> findTrailerConfig(String key, PullRequestBot bot, PrintWriter reply) {
        var trailerConfigs = bot.trailerConfigs();
        if (trailerConfigs.isEmpty()) {
            reply.println("There are no custom trailers configured for this repository.");
            return Optional.empty();
        } else {
            var config = trailerConfigs.stream()
                    .filter(c -> key.equals(c.key()) || key.equals(c.alias()))
                    .findFirst();
            if (config.isEmpty()) {
                reply.println("Trailer `" + key + "` is not configured for this repository.");
                printConfiguredTrailers(bot, reply);
            }
            return config;
        }
    }
}
