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

import org.openjdk.skara.process.Process;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class Exporter {
    private final static Logger log = Logger.getLogger("org.openjdk.bots.hgbridge");

    private static void repack(Path gitRepo, boolean full) {
        if (full) {
            try (var p = Process.capture("git", "repack", "-a", "-d", "-f", "--depth=50", "--window=10000")
                                .workdir(gitRepo)
                                .execute()) {
                p.check();
            }
        } else {
            try (var p = Process.capture("git", "repack", "--depth=50", "--window=100000")
                                .workdir(gitRepo)
                                .execute()) {
                p.check();
            }
        }
    }

    private static Set<Hash> unreachable(Path gitRepo) {
        try (var p =  Process.capture("git", "fsck", "--unreachable", "--full", "--no-progress")
                             .workdir(gitRepo)
                             .execute()) {
            var lines = p.check().stdout();

            return lines.stream()
                        .filter(l -> l.startsWith("unreachable commit"))
                        .map(l -> l.split("\\s")[2])
                        .map(Hash::new)
                        .collect(Collectors.toSet());
        }
    }

    private static List<Mark> loadMarks(Path p) throws IOException {
        if (Files.exists(p)) {
            return Files.lines(p)
                        .map(line -> line.split(","))
                        .map(entry -> new Mark(Integer.parseInt(entry[0]), new Hash(entry[1]), new Hash(entry[2])))
                        .collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    private static void saveMarks(List<Mark> marks, Path p) throws IOException {
        var lines = marks.stream()
                         .map(mark -> mark.key() + "," + mark.hg().hex() + "," + mark.git().hex())
                         .collect(Collectors.toList());
        Files.write(p, lines);
    }

    private static void clearDirectory(Path directory) {
        try {
            Files.walk(directory)
                 .map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
    }

    private static Optional<Repository> tryExport(Converter converter, URI source, Path destination) throws IOException, InvalidLocalRepository {
        var marksPath = destination.resolve("marks.txt");
        var sourcePath = destination.resolve("source");
        var importPath = destination.resolve("imported.git");

        boolean isInitialConversion = !Files.exists(marksPath);
        if (isInitialConversion) {
            // Ensure that there isn't anything else in the folder that may interfere
            if (Files.exists(destination)) {
                clearDirectory(destination);
            } else {
                Files.createDirectories(destination);
            }
            Repository.init(sourcePath, VCS.HG);
            Repository.init(importPath, VCS.GIT);
        }

        var hgRepo = Repository.get(sourcePath).orElseThrow(() -> new InvalidLocalRepository(sourcePath));
        var gitRepo = Repository.get(importPath).orElseThrow(() -> new InvalidLocalRepository(importPath));

        var oldMarks = loadMarks(marksPath);
        var allNewMarks = converter.pull(hgRepo, source, gitRepo, oldMarks);

        var highestOldMark = oldMarks.stream().max(Mark::compareTo);
        var highestNewMark = allNewMarks.stream().max(Mark::compareTo);
        if (highestOldMark.isPresent() && highestNewMark.isPresent() && highestNewMark.get().key() <= highestOldMark.get().key()) {
            log.fine("No new marks obtained - skipping further processing");
            return Optional.empty();
        }

        var unreachable = unreachable(gitRepo.root());
        var newMarks = allNewMarks.stream()
                .filter(mark -> !unreachable.contains(mark.git()))
                .collect(Collectors.toList());

        if (oldMarks.equals(newMarks)) {
            log.fine("No new marks found after unreachable filtering - skipping further processing");
            return Optional.empty();
        }

        saveMarks(newMarks, marksPath);
        repack(gitRepo.root(), isInitialConversion);

        return Optional.of(gitRepo);
    }

    private static void syncFolder(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            Files.createDirectories(source);
        }
        if (!Files.isDirectory(destination)) {
            Files.createDirectories(destination);
        }
        try (var rsync = Process.capture("rsync", "--archive", "--delete",
                                         source.resolve(".").toString(),
                                         destination.toString())
                                .execute()) {
            var result = rsync.await();
            if (result.status() != 0) {
                throw new IOException("Error during folder sync:\n" + result.stdout());
            }
        }
    }

    static Optional<Repository> export(Converter converter, URI source, Path destination, Path finalMarks) throws IOException {
        final var successMarker = "success.txt";
        final var lastKnownGood = destination.resolve("lkg");
        final var current = destination.resolve("current");
        Optional<Repository> ret;

        // Restore state from previous last working export, if possible
        if (Files.isDirectory(lastKnownGood)) {
            if (!Files.exists(lastKnownGood.resolve(successMarker))) {
                log.warning("Last known good folder does not contain a success marker - erasing");
                clearDirectory(lastKnownGood);
            } else {
                syncFolder(lastKnownGood, current);
                Files.delete(current.resolve(successMarker));
            }
        } else {
            if (Files.exists(destination)) {
                log.info("No last known good export - erasing destination directory");
                clearDirectory(destination);
            }
        }

        // Attempt export
        try {
            ret = tryExport(converter, source, current);
        } catch (InvalidLocalRepository e) {
            log.warning("Repository is corrupted, erasing destination directory");
            clearDirectory(destination);
            try {
                ret = tryExport(converter, source, current);
            } catch (InvalidLocalRepository invalidLocalRepository) {
                throw new IOException("Repository is corrupted even after a fresh export");
            }
        }

        // Exported new revisions successfully, update last known good copy
        if (ret.isPresent()) {
            Files.deleteIfExists(lastKnownGood.resolve(successMarker));
            syncFolder(current, lastKnownGood);
            lastKnownGood.resolve(successMarker).toFile().createNewFile();

            // Update marks
            var markSource = current.resolve("marks.txt");
            Files.copy(markSource, finalMarks, StandardCopyOption.REPLACE_EXISTING);
        }

        return ret;
    }

    static Optional<Repository> current(Path destination) throws IOException {
        final var successMarker = "success.txt";
        final var lastKnownGood = destination.resolve("lkg");

        if (!Files.exists(lastKnownGood.resolve(successMarker))) {
            log.info("Last known good folder does not contain a success marker");
            return Optional.empty();
        } else {
            return Repository.get(lastKnownGood.resolve("imported.git"));
        }
    }

    static class InvalidLocalRepository extends Exception {
        InvalidLocalRepository(Path path) {
            super(path.toString());
        }
    }
}
