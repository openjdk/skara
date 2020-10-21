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
package org.openjdk.skara.vcs;

import org.openjdk.skara.encoding.Base85;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

public class UnifiedDiffParser {
    public static List<Hunk> parseSingleFileDiff(String[] lines) {
        return parseSingleFileDiff(Arrays.asList(lines));
    }

    public static List<Hunk> parseSingleFileDiff(List<String> lines) {
        var i = 0;
        if (lines.get(i).startsWith("diff ")) {
            i++;
        }
        var extendedHeaders = List.of(
            "old mode ",
            "new mode ",
            "deleted file mode ",
            "new file mode ",
            "copy from ",
            "copy to ",
            "rename from ",
            "rename to ",
            "similarity index ",
            "dissimilarity index ",
            "index "
        );
        while (i < lines.size()) {
            var line = lines.get(i);
            if (extendedHeaders.stream().noneMatch(h -> line.startsWith(h))) {
                break;
            }
            i++;
        }
        if (lines.get(i).startsWith("--- ")) {
            i++;
        }
        if (lines.get(i).startsWith("+++ ")) {
            i++;
        }

        var hunks = new ArrayList<Hunk>();
        while (i < lines.size()) {
            var words = lines.get(i).split("\\s");
            if (!words[0].startsWith("@@")) {
                throw new IllegalStateException("Unexpected diff line at index " + i + ": " + lines.get(i));
            }
            var sourceRange = Range.fromString(words[1].substring(1));
            var targetRange = Range.fromString(words[2].substring(1));

            var nextHeader = i + 1;
            while (nextHeader < lines.size()) {
                if (lines.get(nextHeader).startsWith("@@")) {
                    break;
                }
                nextHeader++;
            }

            var hunkLines = lines.subList(i + 1, nextHeader);
            if (!hunkLines.isEmpty()) {
                hunks.addAll(parseSingleFileDiff(sourceRange, targetRange, hunkLines));
            }
            i = nextHeader;
        }

        return hunks;
    }

    public static List<Hunk> parseSingleFileDiff(Range from, Range to, List<String> hunkLines) {
        var hunks = new ArrayList<Hunk>();

        var sourceStart = from.start();
        var targetStart = to.start();

        var sourceLines = new ArrayList<String>();
        var targetLines = new ArrayList<String>();

        int i = 0;
        while (i < hunkLines.size() && hunkLines.get(i).startsWith(" ")) {
            i++;
            sourceStart++;
            targetStart++;
        }

        while (i < hunkLines.size()) {
            var line = hunkLines.get(i);
            if (line.startsWith("-")) {
                sourceLines.add(line.substring(1));
                i++;
                continue;
            } else if (line.startsWith("+")) {
                targetLines.add(line.substring(1));
                i++;
                continue;
            }

            if (line.startsWith(" ")) {
                hunks.add(new Hunk(new Range(sourceStart, sourceLines.size()), sourceLines,
                                   new Range(targetStart, targetLines.size()), targetLines));

                sourceStart += sourceLines.size();
                targetStart += targetLines.size();

                sourceLines = new ArrayList<String>();
                targetLines = new ArrayList<String>();

                while (i < hunkLines.size() && hunkLines.get(i).startsWith(" ")) {
                    i++;
                    sourceStart++;
                    targetStart++;
                }
            }
        }

        if (sourceLines.size() > 0 || targetLines.size() > 0) {
            hunks.add(new Hunk(new Range(sourceStart, sourceLines.size()), sourceLines,
                               new Range(targetStart, targetLines.size()), targetLines));
        }

        return hunks;
    }
}
