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
package org.openjdk.skara.vcs.tools;

import org.openjdk.skara.encoding.Base85;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

public class UnifiedDiffParser {
    private static class Hunks {
        private final List<Hunk> textual;
        private final List<BinaryHunk> binary;

        private Hunks(List<Hunk> textual, List<BinaryHunk> binary) {
            this.textual = textual;
            this.binary = binary;
        }

        static Hunks ofTextual(List<Hunk> textual) {
            return new Hunks(textual, null);
        }

        static Hunks ofBinary(List<BinaryHunk> binary) {
            return new Hunks(null, binary);
        }

        boolean areBinary() {
            return binary != null;
        }

        List<BinaryHunk> binary() {
            return binary;
        }

        List<Hunk> textual() {
            return textual;
        }
    }

    private final String delimiter;
    private String line = null;

    private UnifiedDiffParser(String delimiter) {
        this.delimiter = delimiter;
    }

    private List<PatchHeader> parseRawLines(InputStream stream) throws IOException {
        return parseRawLines(new UnixStreamReader(stream));
    }

    private List<PatchHeader> parseRawLines(UnixStreamReader reader) throws IOException {
        var headers = new ArrayList<PatchHeader>();

        line = reader.readLine();
        while (line != null && line.startsWith(":")) {
            headers.add(PatchHeader.fromRawLine(line));
            line = reader.readLine();
        }

        return headers;
    }

    private Hunks parseSingleFileBinaryHunks(UnixStreamReader reader) throws IOException {
        var hunks = new ArrayList<BinaryHunk>();
        while ((line = reader.readLine()) != null &&
                !line.startsWith("diff") &&
                !line.equals(delimiter)) {
            var words = line.split(" ");
            var format = words[0];
            var inflatedSize = Integer.parseInt(words[1]);

            var data = new ArrayList<String>();
            while ((line = reader.readLine()) != null && !line.equals("")) {
                data.add(line);
            }

            if (format.equals("literal")) {
                hunks.add(BinaryHunk.ofLiteral(inflatedSize, data));
            } else if (format.equals("delta")) {
                hunks.add(BinaryHunk.ofDelta(inflatedSize, data));
            } else {
                throw new IllegalStateException("Unexpected binary diff format: " + words[0]);
            }
        }
        return Hunks.ofBinary(hunks);
    }

    private Hunks parseSingleFileTextualHunks(UnixStreamReader reader) throws IOException {
        var hunks = new ArrayList<Hunk>();

        while (line != null && line.startsWith("@@")) {
            var words = line.split("\\s");
            if (!words[0].startsWith("@@")) {
                throw new IllegalStateException("Unexpected diff line: " + line);
            }

            var sourceRange = words[1].substring(1); // skip initial '-'
            var targetRange = words[2].substring(1); // skip initial '+'

            var sourceLines = new ArrayList<String>();
            var sourceHasNewlineAtEndOfFile = true;
            var targetLines = new ArrayList<String>();
            var targetHasNewlineAtEndOfFile = true;
            var hasSeenLinesWithPlusPrefix = false;
            while ((line = reader.readLine()) != null &&
                   !line.startsWith("@@") &&
                   !line.startsWith("diff") &&
                   !line.equals(delimiter)) {
                if (line.equals("\\ No newline at end of file")) {
                    if (!hasSeenLinesWithPlusPrefix) {
                        sourceHasNewlineAtEndOfFile = false;
                    } else {
                        targetHasNewlineAtEndOfFile = false;
                    }
                    continue;
                }

                if (line.startsWith("-")) {
                    sourceLines.add(line.substring(1)); // skip initial '-'
                } else if (line.startsWith("+")) {
                    hasSeenLinesWithPlusPrefix = true;
                    targetLines.add(line.substring(1)); // skip initial '+'
                } else {
                    throw new IllegalStateException("Unexpected diff line: " + line);
                }
            }
            hunks.add(new Hunk(GitRange.fromString(sourceRange), sourceLines, sourceHasNewlineAtEndOfFile,
                               GitRange.fromString(targetRange), targetLines, targetHasNewlineAtEndOfFile));
        }

        return Hunks.ofTextual(hunks);
    }

    private Hunks parseSingleFileHunks(UnixStreamReader reader) throws IOException {
        if (!line.startsWith("diff")) {
            throw new IllegalStateException("Unexpected diff line: " + line);
        }

        while ((line = reader.readLine()) != null &&
                !line.startsWith("@@") &&
                !line.startsWith("GIT binary patch") &&
                !line.startsWith("diff") &&
                !line.equals(delimiter)) {
            // ignore extended headers, we have the data via the 'raw' lines
        }

        if (line != null && line.startsWith("GIT binary patch")) {
            return parseSingleFileBinaryHunks(reader);
        } else {
            return parseSingleFileTextualHunks(reader);
        }
    }

    private List<Hunks> parseHunks(InputStream stream) throws IOException {
        return parseHunks(new UnixStreamReader(stream));
    }

    private List<Hunks> parseHunks(UnixStreamReader reader) throws IOException {
        var hunks = new ArrayList<Hunks>();

        line = reader.readLine();
        while (line != null && !line.equals(delimiter)) {
            hunks.add(parseSingleFileHunks(reader));
        }

        return hunks;
    }

    public static List<Patch> parseGitRaw(InputStream stream) throws IOException {
        return parseGitRaw(new UnixStreamReader(stream));
    }

    public static List<Patch> parseGitRaw(InputStream stream, String delimiter) throws IOException {
        return parseGitRaw(new UnixStreamReader(stream), delimiter);
    }

    public static List<Patch> parseGitRaw(UnixStreamReader reader) throws IOException {
        return parseGitRaw(reader, null);
    }

    public static List<Patch> parseGitRaw(UnixStreamReader reader, String delimiter) throws IOException {
        var parser = new UnifiedDiffParser(delimiter);

        var headers = parser.parseRawLines(reader);
        var hunks = parser.parseHunks(reader);

        if (headers.size() != hunks.size()) {
            throw new IOException("Num headers (" + headers.size() + ") differ from num hunks (" + hunks.size() + ")");
        }

        var patches = new ArrayList<Patch>();
        for (var i = 0; i < headers.size(); i++) {
            var headerForPatch = headers.get(i);
            var hunksForPatch = hunks.get(i);

            if (hunksForPatch.areBinary()) {
                patches.add(new BinaryPatch(headerForPatch.sourcePath(), headerForPatch.sourceFileType(), headerForPatch.sourceHash(),
                                            headerForPatch.targetPath(), headerForPatch.targetFileType(), headerForPatch.targetHash(),
                                            headerForPatch.status(), hunksForPatch.binary()));
            } else {
                patches.add(new TextualPatch(headerForPatch.sourcePath(), headerForPatch.sourceFileType(), headerForPatch.sourceHash(),
                                             headerForPatch.targetPath(), headerForPatch.targetFileType(), headerForPatch.targetHash(),
                                             headerForPatch.status(), hunksForPatch.textual()));
            }
        }

        return patches;
    }

    public static List<Hunk> splitDiffWithContext(Range from, Range to, List<String> lines) {
        var hunks = new ArrayList<Hunk>();

        var sourceStart = from.start();
        var targetStart = to.start();

        var sourceLines = new ArrayList<String>();
        var targetLines = new ArrayList<String>();

        int i = 0;
        while (i < lines.size() && lines.get(i).startsWith(" ")) {
            i++;
            sourceStart++;
            targetStart++;
        }

        while (i < lines.size()) {
            var line = lines.get(i);
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

                while (i < lines.size() && lines.get(i).startsWith(" ")) {
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
