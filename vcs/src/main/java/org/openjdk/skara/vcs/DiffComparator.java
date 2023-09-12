/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import java.util.regex.Pattern;

public class DiffComparator {

    private static final Pattern COPYRIGHT_PATTERN = Pattern.compile("""
            -(.)*Copyright \\(c\\) (?:\\d|\\s|,)* Oracle and/or its affiliates\\. All rights reserved\\.
            \\+(.)*Copyright \\(c\\) (?:\\d|\\s|,)* Oracle and/or its affiliates\\. All rights reserved\\.
            """);

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
        var aHunks = a.asTextualPatch().hunks()
                .stream()
                .filter(hunk -> !COPYRIGHT_PATTERN.matcher(hunk.toString()).find())
                .toList();
        var bHunks = b.asTextualPatch().hunks()
                .stream()
                .filter(hunk -> !COPYRIGHT_PATTERN.matcher(hunk.toString()).find())
                .toList();

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
