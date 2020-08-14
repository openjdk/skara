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

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

public class Hunk {
    public static final class Info {
        private final Range range;
        private final List<String> lines;
        private final boolean hasNewlineAtEndOfFile;

        private Info(Range range, List<String> lines, boolean hasNewlineAtEndOfFile) {
            this.range = range;
            this.lines = lines;
            this.hasNewlineAtEndOfFile = hasNewlineAtEndOfFile;
        }

        public Range range() {
            return range;
        }

        public List<String> lines() {
            return lines;
        }

        public boolean hasNewlineAtEndOfFile() {
            return hasNewlineAtEndOfFile;
        }
    }

    private final Info source;
    private final Info target;

    public Hunk(Range sourceRange, List<String> sourceLines,
                Range targetRange, List<String> targetLines) {
        this(sourceRange, sourceLines, true, targetRange, targetLines, true);
    }

    public Hunk(Range sourceRange, List<String> sourceLines, boolean sourceHasNewlineAtEndOfFile,
                Range targetRange, List<String> targetLines, boolean targetHasNewlineAtEndOfFile) {
        this.source = new Info(sourceRange, sourceLines, sourceHasNewlineAtEndOfFile);
        this.target = new Info(targetRange, targetLines, targetHasNewlineAtEndOfFile);
    }

    public Info source() {
        return source;
    }

    public Info target() {
        return target;
    }

    public WebrevStats stats() {
        var modified = Math.min(source.lines().size(), target.lines().size());
        var added = target.lines().size() - modified;
        var removed = source.lines().size() - modified;
        return new WebrevStats(added, removed, modified);
    }

    public int changes() {
        return source.lines().size() + target.lines().size();
    }

    public int additions() {
        return target.lines().size();
    }

    public int deletions() {
        return source.lines().size();
    }

    public void write(BufferedWriter w) throws IOException {
        w.append("@@ -");
        w.append(source.range().toString());
        w.append(" +");
        w.append(target.range().toString());
        w.append(" @@");
        w.write("\n");

        for (var line : source.lines()) {
            w.append("-");
            w.append(line);
            w.write("\n");
        }
        if (!source.hasNewlineAtEndOfFile()) {
            w.append("\\ No newline at end of file");
            w.write("\n");
        }

        for (var line : target.lines()) {
            w.append("+");
            w.append(line);
            w.write("\n");
        }
        if (!target.hasNewlineAtEndOfFile()) {
            w.append("\\ No newline at end of file");
            w.write("\n");
        }
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("@@ -");
        sb.append(source.range().toString());
        sb.append(" +");
        sb.append(target.range().toString());
        sb.append(" @@");
        sb.append("\n");

        for (var line : source.lines()) {
            sb.append("-");
            sb.append(line);
            sb.append("\n");
        }
        if (!source.hasNewlineAtEndOfFile()) {
            sb.append("\\ No newline at end of file");
            sb.append("\n");
        }

        for (var line : target.lines()) {
            sb.append("+");
            sb.append(line);
            sb.append("\n");
        }
        if (!target.hasNewlineAtEndOfFile()) {
            sb.append("\\ No newline at end of file");
            sb.append("\n");
        }
        return sb.toString();
    }
}
