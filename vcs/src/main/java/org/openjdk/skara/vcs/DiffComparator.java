/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class DiffComparator {
    public static Diff diff(Diff a, Diff b) throws IOException {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory(null);
            var repo = Repository.init(tmpDir, VCS.GIT);

            var aDeleted = a.patches().stream()
                                      .filter(Patch::isTextual)
                                      .filter(p -> p.status().isDeleted())
                                      .map(p -> p.source().path().orElseThrow())
                                      .collect(Collectors.toSet());
            var bDeleted = b.patches().stream()
                                      .filter(Patch::isTextual)
                                      .filter(p -> p.status().isDeleted())
                                      .map(p -> p.source().path().orElseThrow())
                                      .collect(Collectors.toSet());

            for (var patch : a.patches()) {
                if (patch.status().isDeleted() || patch.isBinary()) {
                    continue;
                }

                var path = tmpDir.resolve(patch.target().path().orElseThrow());
                var lines = new ArrayList<String>();
                for (var hunk : patch.asTextualPatch().hunks()) {
                    var start = hunk.target().range().start();
                    for (var i = lines.size(); i < start; i++) {
                        lines.add("");
                    }
                    for (var i = 0; i < hunk.target().lines().size(); i++) {
                        lines.add(hunk.target().lines().get(i));
                    }
                }
                Files.createDirectories(path.getParent());
                Files.write(path, lines);
                repo.add(path);
            }
            var aHash = repo.commit("a", "a", "a@localhost", true);

            Files.walk(repo.root())
                 .filter(p -> !p.toString().contains(".git"))
                 .map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);

            var additionalPatches = new ArrayList<Patch>();
            for (var patch : a.patches()) {
                if (patch.status().isDeleted() && !bDeleted.contains(patch.source().path().get())) {
                    if (patch.isBinary()) {
                        continue;
                    }
                    var path = tmpDir.resolve(patch.source().path().get());
                    var hunks = patch.asTextualPatch().hunks();
                    if (hunks.size() != 1) {
                        throw new IllegalStateException("A deleted patch should only have one hunk");
                    }
                    var hunk = hunks.get(0);
                    Files.write(path, hunk.source().lines());
                    repo.add(path);
                }
            }
            for (var patch : b.patches()) {
                if (patch.status().isDeleted() && !aDeleted.contains(patch.source().path().get())) {
                    additionalPatches.add(patch);
                }
            }

            for (var patch : b.patches()) {
                if (patch.status().isDeleted() || patch.isBinary()) {
                    continue;
                }

                var path = tmpDir.resolve(patch.target().path().orElseThrow());
                var lines = new ArrayList<String>();
                for (var hunk : patch.asTextualPatch().hunks()) {
                    var start = hunk.target().range().start();
                    for (var i = lines.size(); i < start; i++) {
                        lines.add("");
                    }
                    for (var i = 0; i < hunk.target().lines().size(); i++) {
                        lines.add(hunk.target().lines().get(i));
                    }
                }
                Files.write(path, lines);
                repo.add(path);
            }
            var bHash = repo.commit("b", "b", "b@localhost", true);

            var diffDiff = repo.diff(aHash, bHash);
            diffDiff.patches().addAll(additionalPatches);
            return diffDiff;
        } finally {
            if (tmpDir != null) {
                Files.walk(tmpDir)
                     .map(Path::toFile)
                     .sorted(Comparator.reverseOrder())
                     .forEach(File::delete);
                if (Files.exists(tmpDir)) {
                    Files.delete(tmpDir);
                }
            }
        }
    }

    public static boolean areFuzzyEqual(Diff a, Diff b) {
        var aPatches = new HashMap<String, Patch>();
        for (var patch : a.patches()) {
            aPatches.put(patch.toString(), patch);
        }
        var bPatches = new HashMap<String, Patch>();
        for (var patch : b.patches()) {
            bPatches.put(patch.toString(), patch);
        }

        if (aPatches.size() != bPatches.size()) {
            return false;
        }
        var onlyInA = new HashSet<>(aPatches.keySet());
        onlyInA.removeAll(bPatches.keySet());
        if (!onlyInA.isEmpty()) {
            return false;
        }
        var onlyInB = new HashSet<>(bPatches.keySet());
        onlyInB.removeAll(aPatches.keySet());
        if (!onlyInB.isEmpty()) {
            return false;
        }

        for (var key : aPatches.keySet()) {
            var aPatch = aPatches.get(key).asTextualPatch();
            var bPatch = bPatches.get(key).asTextualPatch();
            if (!areFuzzyEqual(aPatch, bPatch)) {
                return false;
            }
        }

        return true;
    }

    private static boolean areFuzzyEqual(Patch a, Patch b) {
        var aHunks = a.asTextualPatch().hunks();
        var bHunks = b.asTextualPatch().hunks();
        if (aHunks.size() != bHunks.size()) {
            return false;
        }
        for (var i = 0; i < aHunks.size(); i++) {
            var aHunk = aHunks.get(i);
            var bHunk = bHunks.get(i);

            if (aHunk.source().lines().size() != bHunk.source().lines().size()) {
                return false;
            }
            for (var j = 0; j < aHunk.source().lines().size(); j++) {
                var aLine = aHunk.source().lines().get(j);
                var bLine = bHunk.source().lines().get(j);
                if (!aLine.equals(bLine)) {
                    return false;
                }
            }

            if (aHunk.target().lines().size() != bHunk.target().lines().size()) {
                return false;
            }
            for (var j = 0; j < aHunk.target().lines().size(); j++) {
                var aLine = aHunk.target().lines().get(j);
                var bLine = bHunk.target().lines().get(j);
                if (!aLine.equals(bLine)) {
                    return false;
                }
            }
        }
        return true;
    }
}
