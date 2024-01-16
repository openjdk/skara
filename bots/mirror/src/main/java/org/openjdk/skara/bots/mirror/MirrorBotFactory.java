/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mirror;

import java.util.regex.Pattern;
import org.openjdk.skara.bot.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.Branch;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class MirrorBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    static final String NAME = "mirror";
    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var storage = configuration.storageFolder();
        try {
            Files.createDirectories(storage);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var specific = configuration.specific();

        var bots = new ArrayList<Bot>();
        for (var repo : specific.get("repositories").asArray()) {
            var fromName = repo.get("from").asString();
            var fromRepo = configuration.repository(fromName);

            var toName = repo.get("to").asString();
            var toRepo = configuration.repository(toName);

            List<String> refspecs;
            if (repo.contains("refspecs")) {
                var refspecsElement = repo.get("refspecs");
                if (refspecsElement.isArray()) {
                    refspecs = refspecsElement.asArray().stream()
                            .map(JSONValue::asString)
                            .toList();
                } else {
                    refspecs = List.of(refspecsElement.asString());
                }
            } else {
                refspecs = List.of();
            }

            List<Pattern> branchPatterns;
            if (repo.contains("branches")) {
                if (!refspecs.isEmpty()) {
                    throw new IllegalStateException("Cannot combine refspecs and branches");
                }
                // Accept both an array of regex patterns as well as a single comma separated
                // string for backwards compatibility
                var branchesElement = repo.get("branches");
                if (branchesElement.isArray()) {
                    branchPatterns = branchesElement.asArray().stream()
                            .map(JSONValue::asString)
                            .map(Pattern::compile)
                            .toList();
                } else {
                    branchPatterns = Arrays.stream(repo.get("branches").asString().split(","))
                            .map(Pattern::compile)
                            .toList();
                }
            } else {
                branchPatterns = List.of();
            }

            var includeTags = branchPatterns.isEmpty() && refspecs.isEmpty();
            var onlyTags = false;
            if (repo.contains("tags")) {
                var tags = repo.get("tags").asString().toLowerCase().strip();
                if (!Set.of("include", "only").contains(tags)) {
                    throw new IllegalStateException("\"tags\" field can only have value \"include\" or \"only\"");
                }
                onlyTags = tags.equals("only");
                includeTags = tags.equals("include");
            }
            if (onlyTags) {
                // Tags are by definition included when only tags are mirrored
                includeTags = true;
            }
            if (onlyTags && !branchPatterns.isEmpty()) {
                throw new IllegalStateException("Branches cannot be mirrored when only tags are mirrored");
            }
            if ((onlyTags || includeTags) && !refspecs.isEmpty()) {
                throw new IllegalStateException("Cannot combine refspecs and tags");
            }

            log.info("Setting up mirroring from " + fromRepo.name() + " to " + toRepo.name());
            bots.add(new MirrorBot(storage, fromRepo, toRepo, branchPatterns, includeTags, onlyTags, refspecs));
        }
        return bots;
    }
}
