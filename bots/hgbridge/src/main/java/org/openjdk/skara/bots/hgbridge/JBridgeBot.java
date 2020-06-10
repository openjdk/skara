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
package org.openjdk.skara.bots.hgbridge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

public class JBridgeBot implements Bot, WorkItem {
    private final ExporterConfig exporterConfig;
    private final Path storage;
    private final Logger log = Logger.getLogger("org.openjdk.bots.hgbridge");

    JBridgeBot(ExporterConfig exporterConfig, Path storage) {
        this.exporterConfig = exporterConfig;
        this.storage = storage.resolve(URLEncoder.encode(exporterConfig.source().toString(), StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "JBridgeBot@" + exporterConfig.source();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (other instanceof JBridgeBot) {
            JBridgeBot otherBridgeBot = (JBridgeBot)other;
            return !exporterConfig.source().equals(otherBridgeBot.exporterConfig.source());
        } else {
            return true;
        }
    }

    private void pushMarks(Path markSource, String destName, Path markScratchPath) throws IOException {
        var marksRepo = Repository.materialize(markScratchPath, exporterConfig.marksRepo().url(),
                                               "+" + exporterConfig.marksRef() + ":hgbridge_marks");

        // We should never change existing marks
        var markDest = markScratchPath.resolve(destName);
        var updated = Files.readString(markSource);
        if (Files.exists(markDest)) {
            var existing = Files.readString(markDest);

            if (!updated.startsWith(existing)) {
                throw new RuntimeException("Update containing conflicting marks!");
            }
            if (existing.equals(updated)) {
                // Nothing new to push
                return;
            }
        } else {
            if (!Files.exists(markDest.getParent())) {
                Files.createDirectories(markDest.getParent());
            }
        }

        Files.writeString(markDest, updated, StandardCharsets.UTF_8);
        marksRepo.add(markDest);
        var hash = marksRepo.commit("Updated marks", exporterConfig.marksAuthorName(), exporterConfig.marksAuthorEmail());
        marksRepo.push(hash, exporterConfig.marksRepo().url(), exporterConfig.marksRef());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        log.fine("Running export for " + exporterConfig.source().toString());

        try {
            var converter = exporterConfig.resolve(scratchPath.resolve("converter"));
            var marksFile = scratchPath.resolve("marks.txt");
            var exported = Exporter.export(converter, exporterConfig.source(), storage, marksFile);

            // Push updated marks - other marks files may be updated concurrently, so try a few times
            var retryCount = 0;
            while (exported.isPresent()) {
                try {
                    pushMarks(marksFile,
                              exporterConfig.source().getHost() + "/" + exporterConfig.source().getPath() + "/marks.txt",
                              scratchPath.resolve("markspush"));
                    break;
                } catch (IOException e) {
                    retryCount++;
                    if (retryCount > 10) {
                        log.warning("Retry count exceeded for pushing marks");
                        throw new UncheckedIOException(e);
                    }
                }
            }

            IOException lastException = null;
            for (var destination : exporterConfig.destinations()) {
                var markerBase = destination.url().getHost() + "/" + destination.name();
                var successfulPushMarker = storage.resolve(URLEncoder.encode(markerBase, StandardCharsets.UTF_8) + ".success.txt");
                if (exported.isPresent() || !successfulPushMarker.toFile().isFile()) {
                    var repo = exported.orElse(Exporter.current(storage).orElseThrow());
                    try {
                        Files.deleteIfExists(successfulPushMarker);
                        repo.pushAll(destination.url());
                        storage.resolve(successfulPushMarker).toFile().createNewFile();
                    } catch (IOException e) {
                        log.severe("Failed to push to " + destination.url());
                        log.throwing("JBridgeBot", "run", e);
                        lastException = e;
                    }
                } else {
                    log.fine("No changes detected in " + exporterConfig.source() + " - skipping push to " + destination.name());
                }
            }
            if (lastException != null) {
                throw new UncheckedIOException(lastException);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }
}
