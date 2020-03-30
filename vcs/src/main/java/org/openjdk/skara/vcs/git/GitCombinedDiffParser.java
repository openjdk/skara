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
package org.openjdk.skara.vcs.git;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

class GitCombinedDiffParser {
    private final List<Hash> bases;
    private final int numParents;
    private final Hash head;
    private final String delimiter;
    private String line = null;

    public GitCombinedDiffParser(List<Hash> bases, Hash head, String delimiter) {
        this.bases = bases;
        this.numParents = bases.size();
        this.head = head;
        this.delimiter = delimiter;
    }

    private List<List<Hunk>> parseSingleFileMultiParentDiff(UnixStreamReader reader, List<PatchHeader> headers) throws IOException {
        if (!line.startsWith("diff --combined")) {
            throw new IllegalStateException("Expected line to start with 'diff --line combined', got: " + line);
        }

        var filename = line.substring("diff --combined ".length());
        var isRenamedWithRegardsToAllParents = headers.stream().allMatch(h -> h.status().isRenamed());
        if (isRenamedWithRegardsToAllParents) {
            // git diff -c does not give a "diff --combined" line, nor hunks, for a rename without modifications
            if (headers.stream().noneMatch(h -> filename.equals(h.targetPath().toString()))) {
                // This diff is for another file, this must have been a rename without modifications
                var result = new ArrayList<List<Hunk>>();
                for (int i = 0; i < numParents; i++) {
                    result.add(List.of());
                }
                return result;
            }
        }

        for (var header : headers) {
            var targetPath = header.targetPath();
            if (targetPath != null && !targetPath.toString().equals(filename)) {
                throw new IllegalStateException("Got header for file " + targetPath.toString() +
                                                " but hunks for file " + filename);
            }
        }

        while ((line = reader.readLine()) != null &&
                !line.startsWith("@@@") &&
                !line.startsWith("diff --combined") &&
                !line.equals(delimiter)) {
            // Skip all diff header lines (we already have them via the raw headers)
            // Note: this will also skip 'Binary files differ...' on purpose
        }

        var hunksPerParent = new ArrayList<List<Hunk>>(numParents);
        for (int i = 0; i < numParents; i++) {
            hunksPerParent.add(new ArrayList<Hunk>());
        }

        while (line != null && line.startsWith("@@@")) {
            var words = line.split("\\s");
            if (!words[0].startsWith("@@@")) {
                throw new IllegalStateException("Expected word to starts with '@@@', got: " + words[0]);
            }
            var sourceRangesPerParent = new ArrayList<Range>(numParents);
            for (int i = 1; i <= numParents; i++) {
                var header = headers.get(i - 1);
                if (header.status().isAdded()) {
                    // git reports wrong start for added files, they should
                    // always have range (0,0), but git reports (1,0)
                    sourceRangesPerParent.add(new Range(0, 0));
                } else {
                    sourceRangesPerParent.add(GitRange.fromCombinedString(words[i].substring(1))); // skip initial '-'
                }
            }
            var targetRange = GitRange.fromCombinedString(words[numParents + 1].substring(1)); // skip initial '+'

            var linesPerParent = new ArrayList<List<String>>(numParents);
            for (int i = 0; i < numParents; i++) {
                linesPerParent.add(new ArrayList<String>());
            }

            while ((line = reader.readLine()) != null &&
                   !line.startsWith("@@@") &&
                   !line.startsWith("diff --combined") &&
                   !line.equals(delimiter)) {
                if (line.equals("\\ No newline at end of file")) {
                    continue;
                }

                var signs = line.substring(0, numParents);
                var content = line.substring(numParents);
                for (int i = 0; i < numParents; i++) {
                    char sign = line.charAt(i);
                    var lines = linesPerParent.get(i);
                    if (sign == '-') {
                        lines.add("-" + content);
                    } else if (sign == '+') {
                        lines.add("+" + content);
                    } else if (sign == ' ') {
                        var presentInParentFile = !signs.contains("-");
                        if (presentInParentFile) {
                            lines.add(" " + content);
                        }
                    } else {
                        throw new RuntimeException("Unexpected diff line: " + line);
                    }
                }
            }

            for (int i = 0; i < numParents; i++) {
                var sourceRange = sourceRangesPerParent.get(i);
                var lines = linesPerParent.get(i);
                var hunks = UnifiedDiffParser.splitDiffWithContext(sourceRange, targetRange, lines);
                hunksPerParent.get(i).addAll(hunks);
            }
        }

        return hunksPerParent;
    }

    private List<PatchHeader> parseCombinedRawLine(String line) {
        var headers = new ArrayList<PatchHeader>(numParents);
        var words = line.substring(2).split("\\s");

        int index = 0;
        int end = index + numParents;

        var srcTypes = new ArrayList<FileType>(numParents);
        while (index < end) {
            srcTypes.add(FileType.fromOctal(words[index]));
            index++;
        }
        var dstType = FileType.fromOctal(words[index]);
        index++;

        end = index + numParents;
        var srcHashes = new ArrayList<Hash>(numParents);
        while (index < end) {
            srcHashes.add(new Hash(words[index]));
            index++;
        }
        var dstHash = new Hash(words[index]);
        index++;

        var statuses = new ArrayList<Status>(numParents);
        var statusWord = words[index];
        for (int i = 0; i < statusWord.length(); i++) {
            statuses.add(Status.from(statusWord.charAt(i)));
        }

        index++;
        var dstPath = Path.of(words[index]);
        if (words.length != (index + 1)) {
            throw new IllegalStateException("Unexpected characters at end of raw line: " + line);
        }

        for (int i = 0; i < numParents; i++) {
            var status = statuses.get(i);
            var srcType = srcTypes.get(i);
            var srcPath = status.isModified() ?  dstPath : null;
            var srcHash = srcHashes.get(i);
            headers.add(new PatchHeader(srcPath, srcType, srcHash,  dstPath, dstType, dstHash, status));
        }

        return headers;
    }

    public List<Diff> parse(UnixStreamReader reader) throws IOException {
        line = reader.readLine();

        if (line == null || line.equals(delimiter)) {
            // Not all merge commits contains non-trivial changes
            var diffsPerParent = new ArrayList<Diff>(numParents);
            for (int i = 0; i < numParents; i++) {
                diffsPerParent.add(new Diff(bases.get(i), head, new ArrayList<Patch>()));
            }
            return diffsPerParent;
        }

        var headersPerParent = new ArrayList<List<PatchHeader>>(numParents);
        for (int i = 0; i < numParents; i++) {
            headersPerParent.add(new ArrayList<PatchHeader>());
        }

        var headersForFiles = new ArrayList<List<PatchHeader>>();
        while (line != null && line.startsWith("::")) {
            var headersForFile = parseCombinedRawLine(line);
            headersForFiles.add(headersForFile);
            if (headersForFile.size() != numParents) {
                throw new IllegalStateException("Expected one raw diff line per parent, have " +
                                                numParents + " parents and got " + headersForFile.size() +
                                                " raw diff lines");
            }

            for (int i = 0; i < numParents; i++) {
                headersPerParent.get(i).add(headersForFile.get(i));
            }

            line = reader.readLine();
        }

        // skip empty newline added by git
        if (!line.equals("")) {
            throw new IllegalStateException("Expected empty line, got: " + line);
        }
        line = reader.readLine();

        var hunksPerFilePerParent = new ArrayList<List<List<Hunk>>>(numParents);
        for (int i = 0; i < numParents; i++) {
            hunksPerFilePerParent.add(new ArrayList<List<Hunk>>());
        }

        int headerIndex = 0;
        while (line != null && !line.equals(delimiter)) {
            var headersForFile = headersForFiles.get(headerIndex);
            var isRenamedWithRegardsToAllParents = headersForFile.stream().allMatch(h -> h.status().isRenamed());
            var hunksPerParentForFile = parseSingleFileMultiParentDiff(reader, headersForFile);

            if (hunksPerParentForFile.size() != numParents) {
                throw new IllegalStateException("Expected at least one hunk per parent, have " +
                                                numParents + " parents and got " + hunksPerParentForFile.size() +
                                                " hunk lists");
            }

            for (int i = 0; i < numParents; i++) {
                hunksPerFilePerParent.get(i).add(hunksPerParentForFile.get(i));
            }

            headerIndex++;
        }

        var patchesPerParent = new ArrayList<List<Patch>>(numParents);
        for (int i = 0; i < numParents; i++) {
            var headers = headersPerParent.get(i);
            var hunks = hunksPerFilePerParent.get(i);
            if (headers.size() != hunks.size()) {
                throw new IllegalStateException("Header lists and hunk lists differ: " + headers.size() +
                                                " headers vs " + hunks.size() + " hunks");
            }
            var patches = new ArrayList<Patch>();
            for (int j = 0; j < headers.size(); j++) {
                var h = headers.get(j);
                var hunksForParentPatch = hunks.get(j);
                patches.add(new TextualPatch(h.sourcePath(), h.sourceFileType(), h.sourceHash(),
                                             h.targetPath(), h.targetFileType(), h.targetHash(),
                                             h.status(), hunksForParentPatch));
            }
            patchesPerParent.add(patches);
        }

        var diffs = new ArrayList<Diff>(numParents);
        for (int i = 0; i < numParents; i++) {
            diffs.add(new Diff(bases.get(i), head, patchesPerParent.get(i)));
        }
        return diffs;
    }
}
