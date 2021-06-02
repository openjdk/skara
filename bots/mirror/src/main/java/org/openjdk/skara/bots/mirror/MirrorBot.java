/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
    private final List<Branch> branches;
    private final boolean shouldMirrorEverything;
    private final boolean includeTags;

    MirrorBot(Path storage, HostedRepository from, HostedRepository to) {
        this(storage, from, to, List.of(), false);
    }

    MirrorBot(Path storage, HostedRepository from, HostedRepository to, List<Branch> branches,
              boolean includeTags) {
        this.storage = storage;
        this.from = from;
        this.to = to;
        this.branches = branches;
        this.shouldMirrorEverything = branches.isEmpty();
        this.includeTags = includeTags;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof MirrorBot)) {
            return true;
        }
        var otherBot = (MirrorBot) other;
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
                if (shouldMirrorEverything) {
                    repo = Repository.mirror(from.url(), dir);
                } else {
                    repo = Repository.clone(to.url(), dir);
                }
            } else {
                log.info("Found existing scratch directory for " + to.name());
                repo = Repository.get(dir).orElseThrow(() -> {
                        return new RuntimeException("Repository in " + dir + " has vanished");
                });
            }

            if (shouldMirrorEverything) {
                log.info("Pulling " + from.name());
                // Tags are always included when mirroring everything
                repo.fetchAll(from.url(), true);
                log.info("Pushing to " + to.name());
                repo.pushAll(to.url());
            } else {
                for (var branch : branches) {
                    var fetchHead = repo.fetch(from.url(), branch.name(), includeTags);
                    repo.push(fetchHead, to.url(), branch.name(), false, includeTags);
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
        if (!branches.isEmpty()) {
            var branchNames = branches.stream().map(Branch::name).collect(Collectors.toList());
            name += " (" + String.join(",", branchNames) + ")";
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
}
