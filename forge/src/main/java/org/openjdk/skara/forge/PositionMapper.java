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
package org.openjdk.skara.forge;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class PositionMapper {
    private static final Pattern filePattern = Pattern.compile("^diff --git a/(.*) b/.*$");
    private static final Pattern hunkPattern = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private static class PositionOffset {
        int position;
        int line;
    }

    private final Map<String, List<PositionOffset>> fileDiffs = new HashMap<>();
    private final Logger log = Logger.getLogger("org.openjdk.skara.host.github");

    private PositionMapper(List<String> lines) {
        int position = 0;
        var latestList = new ArrayList<PositionOffset>();

        for (var line : lines) {
            var fileMatcher = filePattern.matcher(line);
            if (fileMatcher.matches()) {
                latestList = new ArrayList<>();
                fileDiffs.put(fileMatcher.group(1), latestList);
                continue;
            }
            var hunkMatcher = hunkPattern.matcher(line);
            if (hunkMatcher.matches()) {
                var positionOffset = new PositionOffset();
                if (latestList.isEmpty()) {
                    position = 1;
                    positionOffset.position = 1;
                } else {
                    positionOffset.position = position + 1;
                }
                positionOffset.line = Integer.parseInt(hunkMatcher.group(2));
                latestList.add(positionOffset);
            }
            position++;
        }
    }

    int positionToLine(String file, int position) {
        if (!fileDiffs.containsKey(file)) {
            throw new IllegalArgumentException("Unknown file " + file);
        }
        var positionOffsets = fileDiffs.get(file);
        PositionOffset activeOffset = null;
        for (var offset : positionOffsets) {
            if (offset.position > position) {
                break;
            }
            activeOffset = offset;
        }
        if (activeOffset == null) {
            log.warning("No matching line found (position: " + position + " file: " + file + ")");
            return -1;
        }
        return activeOffset.line + (position - activeOffset.position);
    }

    int lineToPosition(String file, int line) {
        if (!fileDiffs.containsKey(file)) {
            throw new IllegalArgumentException("Unknown file " + file);
        }
        var positionOffsets = fileDiffs.get(file);
        PositionOffset activeOffset = null;
        for (var offset : positionOffsets) {
            if (offset.line > line) {
                break;
            }
            activeOffset = offset;
        }
        if (activeOffset == null) {
            log.warning("No matching position found (line: " + line + " file: " + file + ")");
            return -1;
        }
        return activeOffset.position + (line - activeOffset.line);
    }

    static PositionMapper parse(String diff) {
        return new PositionMapper(diff.lines().collect(Collectors.toList()));
    }
}
