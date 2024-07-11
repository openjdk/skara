/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The MirrorBot mirrors one HostedRepository to another. It can be configured
 * to only mirror a specific set of branches, or everything (which also
 * includes tags). When only mirroring a set of branches, the includeTags
 * setting can be used to also include tags.
 */
class MirrorBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final Path storage;
    private final HostedRepository from;
    private final HostedRepository to;
    private final List<Pattern> branchPatterns;
    private final boolean includeTags;
    private final boolean onlyTags;
    private final List<String> refspecs;

    MirrorBot(Path storage, HostedRepository from, HostedRepository to) {
        this(storage, from, to, List.of(), true, false, List.of());
    }

    MirrorBot(Path storage, HostedRepository from, HostedRepository to, List<Pattern> branchPatterns,
              boolean includeTags, boolean onlyTags, List<String> refspecs) {
        this.storage = storage;
        this.from = from;
        this.to = to;
        this.branchPatterns = branchPatterns;
        this.includeTags = includeTags;
        this.onlyTags = onlyTags;
        this.refspecs = refspecs;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof MirrorBot otherBot)) {
            return true;
        }
        return !to.name().equals(otherBot.to.name());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        try {
            var sanitizedUrl =
                URLEncoder.encode(to.webUrl().toString(), StandardCharsets.UTF_8);
            var dir = storage.resolve(sanitizedUrl);
            Repository repo = null;


            if (!Files.exists(dir)) {
                log.info("Cloning " + from.name());
                Files.createDirectories(dir);
                repo = Repository.mirror(from.authenticatedUrl(), dir);
            } else {
                log.info("Found existing scratch directory for " + to.name());
                repo = Repository.get(dir).orElseGet(() -> {
                    log.info("The existing scratch directory is not a valid repository. Recloning " + from.name());
                    try {
                        try (var paths = Files.walk(dir)) {
                            paths.map(Path::toFile)
                                 .sorted(Comparator.reverseOrder())
                                 .forEach(File::delete);
                        }
                        return Repository.mirror(from.authenticatedUrl(), dir);
                    } catch (IOException io) {
                        throw new RuntimeException(io);
                    }
                });
            }

            log.info("Pulling " + from.name());
            repo.fetchAll(from.authenticatedUrl(), includeTags || onlyTags || !refspecs.isEmpty());
            if (onlyTags) {
                log.info("Pushing tags to " + to.name());
                repo.pushTags(to.authenticatedUrl(), true);
            } else if (branchPatterns.isEmpty() && includeTags) {
                log.info("Pushing tags and branches to " + to.name());
                repo.pushAll(to.authenticatedUrl(), true);
            } else if (!branchPatterns.isEmpty()) {
                for (var branch : repo.branches()) {
                    if (branchPatterns.stream().anyMatch(p -> p.matcher(branch.name()).matches())) {
                        var hash = repo.resolve(branch);
                        if (hash.isPresent()) {
                            log.info("Pushing branch " + branch.name() + " to " + to.name() + " " +
                                     (includeTags ? "including" : "excluding") + " tags");
                            repo.push(hash.get(), to.authenticatedUrl(), branch.name(), true, includeTags);
                        } else {
                            log.severe("Branch " + branch + " not found in repo " + repo);
                        }
                    }
                }
            } else if (!refspecs.isEmpty()) {
                for (var refspec : refspecs) {
                    log.info("Pushing using refspec " + refspec + " to " + to.name());
                    repo.push(refspec, to.authenticatedUrl());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public String toString() {
        var name = "MirrorBot@" + from.name() + "->" + to.name();
        if (!refspecs.isEmpty()) {
            name += " (" + String.join(",", refspecs) + ")";
        } else {
            if (branchPatterns.isEmpty()) {
                if (onlyTags) {
                    name += " ()";
                } else {
                    name += " (*)";
                }
            } else {
                var branchPatterns = this.branchPatterns.stream().map(Pattern::toString).collect(Collectors.toList());
                name += " (" + String.join(",", branchPatterns) + ")";
            }
            if (onlyTags) {
                name += " [tags only]";
            } else if (includeTags) {
                name += " [tags included]";
            } else {
                name += " [tags excluded]";
            }
        }
        return name;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    @Override
    public String workItemName() {
        return botName();
    }

    @Override
    public String botName() {
        return name();
    }

    @Override
    public String name() {
        return MirrorBotFactory.NAME;
    }

    public List<Pattern> getBranchPatterns() {
        return branchPatterns;
    }

    public boolean isIncludeTags() {
        return includeTags;
    }

    public boolean isOnlyTags() {
        return onlyTags;
    }

    public List<String> getRefspecs() {
        return refspecs;
    }
}
