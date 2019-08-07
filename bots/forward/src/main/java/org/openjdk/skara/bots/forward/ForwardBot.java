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
package org.openjdk.skara.bots.forward;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.util.List;
import java.util.logging.Logger;

class ForwardBot implements Bot, WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    private final HostedRepository fromHostedRepo;
    private final Branch fromBranch;

    private final HostedRepository toHostedRepo;
    private final Branch toBranch;

    ForwardBot(HostedRepository fromHostedRepo, Branch fromBranch,
               HostedRepository toHostedRepo, Branch toBranch) {
        this.fromHostedRepo = fromHostedRepo;
        this.fromBranch = fromBranch;
        this.toHostedRepo = toHostedRepo;
        this.toBranch = toBranch;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof ForwardBot)) {
            return true;
        }
        var otherBot = (ForwardBot) other;
        return !toHostedRepo.getName().equals(otherBot.toHostedRepo.getName());
    }

    @Override
    public void run(Path scratchPath) {
        try {
            var sanitizedUrl =
                URLEncoder.encode(toHostedRepo.getUrl().toString(), StandardCharsets.UTF_8);
            var toDir = scratchPath.resolve("forward").resolve(sanitizedUrl);
            Repository toLocalRepo = null;
            if (!Files.exists(toDir)) {
                log.info("Cloning " + toHostedRepo.getName());
                Files.createDirectories(toDir);
                toLocalRepo = Repository.clone(toHostedRepo.getUrl(), toDir, true);
            } else {
                log.info("Found existing scratch directory for " + toHostedRepo.getName());
                toLocalRepo = Repository.get(toDir).orElseThrow(() -> {
                        return new RuntimeException("Repository in " + toDir + " has vanished");
                });
            }

            log.info("Fetching " + fromHostedRepo.getName() + ":" + fromBranch.name() +
                     " to " + toBranch.name());
            var fetchHead = toLocalRepo.fetch(fromHostedRepo.getUrl(),
                                              fromBranch.name() + ":" + toBranch.name());
            log.info("Pushing " + toBranch.name() + " to " + toHostedRepo.getName());
            toLocalRepo.push(fetchHead, toHostedRepo.getUrl(), toBranch.name(), false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "FowardBot@(" + fromHostedRepo.getName() + ":" + fromBranch.name() +
                           "-> " + toHostedRepo.getName() + ":" + toBranch.name() + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
