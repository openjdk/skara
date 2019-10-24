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
package org.openjdk.skara.forge.github;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.github.PositionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionMapperTests {
    private static final String diff = "diff --git a/vcs/src/main/java/org/openjdk/skara/vcs/Range.java b/vcs/src/main/java/org/openjdk/skara/vcs/Range.java\n" +
            "index d849c08..c42e24a 100644\n" +
            "--- a/vcs/src/main/java/org/openjdk/skara/vcs/Range.java\n" +
            "+++ b/vcs/src/main/java/org/openjdk/skara/vcs/Range.java\n" +
            "@@ -42,18 +42,7 @@ public static Range fromString(String s) {\n" +
            "         }\n" +
            " \n" +
            "         var start = Integer.parseInt(s.substring(0, separatorIndex));\n" +
            "-\n" +
            "-        // Need to work arond a bug in git where git sometimes print -1\n" +
            "-        // as an unsigned int for the count part of the range\n" +
            "-        var countString = s.substring(separatorIndex + 1, s.length());\n" +
            "-        var count =\n" +
            "-            countString.equals(\"18446744073709551615\") ?  0 : Integer.parseInt(countString);\n" +
            "-\n" +
            "-        if (count == 0 && start != 0) {\n" +
            "-            // start is off-by-one when count is 0.\n" +
            "-            // but if start == 0, a file was added and we need a 0 here.\n" +
            "-            start++;\n" +
            "-        }\n" +
            "+        var count = Integer.parseInt(s.substring(separatorIndex + 1, s.length()));\n" +
            " \n" +
            "         return new Range(start, count);\n" +
            "     }\n" +
            "diff --git a/vcs/src/main/java/org/openjdk/skara/vcs/git/GitCombinedDiffParser.java b/vcs/src/main/java/org/openjdk/skara/vcs/git/GitCombinedDiffParser.java\n" +
            "index f829554..8044ad1 100644\n" +
            "--- a/vcs/src/main/java/org/openjdk/skara/vcs/git/GitCombinedDiffParser.java\n" +
            "+++ b/vcs/src/main/java/org/openjdk/skara/vcs/git/GitCombinedDiffParser.java\n" +
            "@@ -43,7 +43,7 @@ public GitCombinedDiffParser(List<Hash> bases, Hash head, String delimiter) {\n" +
            "         this.delimiter = delimiter;\n" +
            "     }\n" +
            " \n" +
            "-    private List<List<Hunk>> parseSingleFileMultiParentDiff(UnixStreamReader reader) throws IOException {\n" +
            "+    private List<List<Hunk>> parseSingleFileMultiParentDiff(UnixStreamReader reader, List<PatchHeader> headers) throws IOException {\n" +
            "         assert line.startsWith(\"diff --combined\");\n" +
            " \n" +
            "         while ((line = reader.readLine()) != null &&\n" +
            "@@ -64,7 +64,14 @@ public GitCombinedDiffParser(List<Hash> bases, Hash head, String delimiter) {\n" +
            "             assert words[0].startsWith(\"@@@\");\n" +
            "             var sourceRangesPerParent = new ArrayList<Range>(numParents);\n" +
            "             for (int i = 1; i <= numParents; i++) {\n" +
            "-                sourceRangesPerParent.add(Range.fromString(words[i].substring(1))); // skip initial '-'\n" +
            "+                var header = headers.get(i - 1);\n" +
            "+                if (header.status().isAdded()) {\n" +
            "+                    // git reports wrong start for added files, they should\n" +
            "+                    // always have range (0,0), but git reports (1,0)\n" +
            "+                    sourceRangesPerParent.add(new Range(0, 0));\n" +
            "+                } else {\n" +
            "+                    sourceRangesPerParent.add(Range.fromString(words[i].substring(1))); // skip initial '-'\n" +
            "+                }\n" +
            "             }\n" +
            "             var targetRange = Range.fromString(words[numParents + 1].substring(1)); // skip initial '+'\n" +
            " \n" +
            "@@ -174,8 +181,10 @@ public GitCombinedDiffParser(List<Hash> bases, Hash head, String delimiter) {\n" +
            "             headersPerParent.add(new ArrayList<PatchHeader>());\n" +
            "         }\n" +
            " \n" +
            "+        var headersForFiles = new ArrayList<List<PatchHeader>>();\n" +
            "         while (line != null && line.startsWith(\"::\")) {\n" +
            "             var headersForFile = parseCombinedRawLine(line);\n" +
            "+            headersForFiles.add(headersForFile);\n" +
            "             assert headersForFile.size() == numParents;\n" +
            " \n" +
            "             for (int i = 0; i < numParents; i++) {\n" +
            "@@ -193,13 +202,18 @@ public GitCombinedDiffParser(List<Hash> bases, Hash head, String delimiter) {\n" +
            "         for (int i = 0; i < numParents; i++) {\n" +
            "             hunksPerFilePerParent.add(new ArrayList<List<Hunk>>());\n" +
            "         }\n" +
            "+\n" +
            "+        int headerIndex = 0;\n" +
            "         while (line != null && !line.equals(delimiter)) {\n" +
            "-            var hunksPerParentForFile = parseSingleFileMultiParentDiff(reader);\n" +
            "+            var headersForFile = headersForFiles.get(headerIndex);\n" +
            "+            var hunksPerParentForFile = parseSingleFileMultiParentDiff(reader, headersForFile);\n" +
            "             assert hunksPerParentForFile.size() == numParents;\n" +
            " \n" +
            "             for (int i = 0; i < numParents; i++) {\n" +
            "                 hunksPerFilePerParent.get(i).add(hunksPerParentForFile.get(i));\n" +
            "             }\n" +
            "+\n" +
            "+            headerIndex++;\n" +
            "         }\n" +
            " \n" +
            "         var patchesPerParent = new ArrayList<List<Patch>>(numParents);\n" +
            "diff --git a/vcs/src/main/java/org/openjdk/skara/vcs/tools/GitRange.java b/vcs/src/main/java/org/openjdk/skara/vcs/tools/GitRange.java\n" +
            "new file mode 100644\n" +
            "index 0000000..62a6bde\n" +
            "--- /dev/null\n" +
            "+++ b/vcs/src/main/java/org/openjdk/skara/vcs/tools/GitRange.java\n" +
            "@@ -0,0 +1,52 @@\n" +
            "+/*\n" +
            "+ * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.\n" +
            "+ * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.\n" +
            "+ *\n" +
            "+ * This code is free software; you can redistribute it and/or modify it\n" +
            "+ * under the terms of the GNU General Public License version 2 only, as\n" +
            "+ * published by the Free Software Foundation.\n" +
            "+ *\n" +
            "+ * This code is distributed in the hope that it will be useful, but WITHOUT\n" +
            "+ * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or\n" +
            "+ * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License\n" +
            "+ * version 2 for more details (a copy is included in the LICENSE file that\n" +
            "+ * accompanied this code).\n" +
            "+ *\n" +
            "+ * You should have received a copy of the GNU General Public License version\n" +
            "+ * 2 along with this work; if not, write to the Free Software Foundation,\n" +
            "+ * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.\n" +
            "+ *\n" +
            "+ * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA\n" +
            "+ * or visit www.oracle.com if you need additional information or have any\n" +
            "+ * questions.\n" +
            "+ */\n" +
            "+package org.openjdk.skara.vcs.tools;\n" +
            "+\n" +
            "+import org.openjdk.skara.vcs.Range;\n" +
            "+\n" +
            "+class GitRange {\n" +
            "+    static Range fromString(String s) {\n" +
            "+        var separatorIndex = s.indexOf(\",\");\n" +
            "+\n" +
            "+        if (separatorIndex == -1) {\n" +
            "+            var start = Integer.parseInt(s);\n" +
            "+            return new Range(start, 1);\n" +
            "+        }\n" +
            "+\n" +
            "+        var start = Integer.parseInt(s.substring(0, separatorIndex));\n" +
            "+\n" +
            "+        // Need to work around a bug in git where git sometimes print -1\n" +
            "+        // as an unsigned int for the count part of the range\n" +
            "+        var countString = s.substring(separatorIndex + 1, s.length());\n" +
            "+        var count =\n" +
            "+            countString.equals(\"18446744073709551615\") ?  0 : Integer.parseInt(countString);\n" +
            "+\n" +
            "+        if (count == 0 && start != 0) {\n" +
            "+            // start is off-by-one when count is 0.\n" +
            "+            // but if start == 0, a file was added and we need a 0 here.\n" +
            "+            start++;\n" +
            "+        }\n" +
            "+\n" +
            "+        return new Range(start, count);\n" +
            "+    }\n" +
            "+}\n" +
            "diff --git a/vcs/src/main/java/org/openjdk/skara/vcs/tools/UnifiedDiffParser.java b/vcs/src/main/java/org/openjdk/skara/vcs/tools/UnifiedDiffParser.java\n" +
            "index 2bf6972..2dbeccd 100644\n" +
            "--- a/vcs/src/main/java/org/openjdk/skara/vcs/tools/UnifiedDiffParser.java\n" +
            "+++ b/vcs/src/main/java/org/openjdk/skara/vcs/tools/UnifiedDiffParser.java\n" +
            "@@ -149,8 +149,8 @@ private Hunks parseSingleFileTextualHunks(UnixStreamReader reader) throws IOExce\n" +
            "                     throw new IllegalStateException(\"Unexpected diff line: \" + line);\n" +
            "                 }\n" +
            "             }\n" +
            "-            hunks.add(new Hunk(Range.fromString(sourceRange), sourceLines, sourceHasNewlineAtEndOfFile,\n" +
            "-                               Range.fromString(targetRange), targetLines, targetHasNewlineAtEndOfFile));\n" +
            "+            hunks.add(new Hunk(GitRange.fromString(sourceRange), sourceLines, sourceHasNewlineAtEndOfFile,\n" +
            "+                               GitRange.fromString(targetRange), targetLines, targetHasNewlineAtEndOfFile));\n" +
            "         }\n" +
            " \n" +
            "         return Hunks.ofTextual(hunks);\n" +
            "@@ -261,14 +261,6 @@ private Hunks parseSingleFileHunks(UnixStreamReader reader) throws IOException {\n" +
            "             }\n" +
            " \n" +
            "             if (line.startsWith(\" \")) {\n" +
            "-                // this is the start of another hunk\n" +
            "-                // TODO: explain this strange behaviour\n" +
            "-                if (sourceLines.size() == 0) {\n" +
            "-                    sourceStart--;\n" +
            "-                }\n" +
            "-                if (targetLines.size() == 0) {\n" +
            "-                    targetStart--;\n" +
            "-                }\n" +
            "                 hunks.add(new Hunk(new Range(sourceStart, sourceLines.size()), sourceLines,\n" +
            "                                    new Range(targetStart, targetLines.size()), targetLines));\n" +
            " \n" +
            "@@ -287,12 +279,6 @@ private Hunks parseSingleFileHunks(UnixStreamReader reader) throws IOException {\n" +
            "         }\n" +
            " \n" +
            "         if (sourceLines.size() > 0 || targetLines.size() > 0) {\n" +
            "-            if (sourceLines.size() == 0) {\n" +
            "-                sourceStart--;\n" +
            "-            }\n" +
            "-            if (targetLines.size() == 0) {\n" +
            "-                targetStart--;\n" +
            "-            }\n" +
            "             hunks.add(new Hunk(new Range(sourceStart, sourceLines.size()), sourceLines,\n" +
            "                                new Range(targetStart, targetLines.size()), targetLines));\n" +
            "         }\n" +
            "diff --git a/vcs/src/test/java/org/openjdk/skara/vcs/RepositoryTests.java b/vcs/src/test/java/org/openjdk/skara/vcs/RepositoryTests.java\n" +
            "index 5d476f1..8747062 100644\n" +
            "--- a/vcs/src/test/java/org/openjdk/skara/vcs/RepositoryTests.java\n" +
            "+++ b/vcs/src/test/java/org/openjdk/skara/vcs/RepositoryTests.java\n" +
            "@@ -376,7 +376,7 @@ void testCommitListingWithMultipleCommits(VCS vcs) throws IOException {\n" +
            "             assertEquals(1, hunks.size());\n" +
            " \n" +
            "             var hunk = hunks.get(0);\n" +
            "-            assertEquals(new Range(1, 0), hunk.source().range());\n" +
            "+            assertEquals(new Range(2, 0), hunk.source().range());\n" +
            "             assertEquals(new Range(2, 1), hunk.target().range());\n" +
            " \n" +
            "             assertEquals(List.of(), hunk.source().lines());\n" +
            "@@ -508,7 +508,7 @@ void testSquash(VCS vcs) throws IOException {\n" +
            "             assertEquals(1, hunks.size());\n" +
            " \n" +
            "             var hunk = hunks.get(0);\n" +
            "-            assertEquals(new Range(1, 0), hunk.source().range());\n" +
            "+            assertEquals(new Range(2, 0), hunk.source().range());\n" +
            "             assertEquals(new Range(2, 2), hunk.target().range());\n" +
            " \n" +
            "             assertEquals(List.of(), hunk.source().lines());\n" +
            "@@ -859,7 +859,7 @@ void testDiffBetweenCommits(VCS vcs) throws IOException {\n" +
            "             assertEquals(1, hunks.size());\n" +
            " \n" +
            "             var hunk = hunks.get(0);\n" +
            "-            assertEquals(1, hunk.source().range().start());\n" +
            "+            assertEquals(2, hunk.source().range().start());\n" +
            "             assertEquals(0, hunk.source().range().count());\n" +
            "             assertEquals(0, hunk.source().lines().size());\n" +
            " \n" +
            "@@ -1132,7 +1132,7 @@ void testDiffWithWorkingDir(VCS vcs) throws IOException {\n" +
            "             assertEquals(1, hunks.size());\n" +
            " \n" +
            "             var hunk = hunks.get(0);\n" +
            "-            assertEquals(1, hunk.source().range().start());\n" +
            "+            assertEquals(2, hunk.source().range().start());\n" +
            "             assertEquals(0, hunk.source().range().count());\n" +
            "             assertEquals(List.of(), hunk.source().lines());\n" +
            " \n" +
            "@@ -1283,7 +1283,7 @@ void testMergeWithEdit(VCS vcs) throws IOException {\n" +
            "             assertEquals(List.of(), secondHunk.source().lines());\n" +
            "             assertEquals(List.of(\"One last line\"), secondHunk.target().lines());\n" +
            " \n" +
            "-            assertEquals(2, secondHunk.source().range().start());\n" +
            "+            assertEquals(3, secondHunk.source().range().start());\n" +
            "             assertEquals(0, secondHunk.source().range().count());\n" +
            "             assertEquals(3, secondHunk.target().range().start());\n" +
            "             assertEquals(1, secondHunk.target().range().count());\n" +
            "@@ -1302,7 +1302,7 @@ void testMergeWithEdit(VCS vcs) throws IOException {\n" +
            "             assertEquals(List.of(), thirdHunk.source().lines());\n" +
            "             assertEquals(List.of(\"One more line\", \"One last line\"), thirdHunk.target().lines());\n" +
            " \n" +
            "-            assertEquals(1, thirdHunk.source().range().start());\n" +
            "+            assertEquals(2, thirdHunk.source().range().start());\n" +
            "             assertEquals(0, thirdHunk.source().range().count());\n" +
            "             assertEquals(2, thirdHunk.target().range().start());\n" +
            "             assertEquals(2, thirdHunk.target().range().count());\n";

    @Test
    void simple() {
        var mapper = PositionMapper.parse(diff);

        assertEquals(38, mapper.positionToLine("vcs/src/main/java/org/openjdk/skara/vcs/tools/GitRange.java", 38));
        assertEquals(70, mapper.positionToLine("vcs/src/main/java/org/openjdk/skara/vcs/git/GitCombinedDiffParser.java", 17));
    }
}
