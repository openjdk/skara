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

import org.openjdk.skara.test.TemporaryDirectory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DiffComparatorTests {
    @Test
    void simpleAdd() throws IOException {
        var ft = FileType.fromOctal("100644");
        var path = Path.of("README");
        var hash = Hash.zero();
        var status = Status.from('A');

        var aHunks = new ArrayList<Hunk>();
        aHunks.add(new Hunk(new Range(0, 0), List.of(),
                            new Range(0, 1), List.of("Hello, world")));
        var aPatches = new ArrayList<Patch>();
        aPatches.add(new TextualPatch(path, ft, hash,
                                      path, ft, hash,
                                      status, aHunks));
        var aDiff = new Diff(hash, hash, aPatches);

        var bHunks = new ArrayList<Hunk>();
        bHunks.add(new Hunk(new Range(0, 0), List.of(),
                            new Range(0, 2), List.of("Hello, world", "Hello again!")));
        var bPatches = new ArrayList<Patch>();
        bPatches.add(new TextualPatch(path, ft, hash,
                                      path, ft, hash,
                                      status, bHunks));
        var bDiff = new Diff(hash, hash, bPatches);

        var diffDiff = DiffComparator.diff(aDiff, bDiff);
        var diffDiffPatches = diffDiff.patches();
        assertEquals(1, diffDiffPatches.size());
        assertTrue(diffDiffPatches.get(0).isTextual());

        var diffDiffPatch = diffDiffPatches.get(0).asTextualPatch();
        assertTrue(diffDiffPatch.status().isModified());
        assertEquals(Optional.of(path), diffDiffPatch.source().path());
        assertEquals(Optional.of(path), diffDiffPatch.target().path());

        var diffDiffHunks = diffDiffPatch.hunks();
        assertEquals(1, diffDiffHunks.size());
        var diffDiffHunk = diffDiffHunks.get(0);
        assertEquals(List.of(), diffDiffHunk.source().lines());
        assertEquals(List.of("Hello again!"), diffDiffHunk.target().lines());
        assertEquals(new Range(2, 0), diffDiffHunk.source().range());
        assertEquals(new Range(2, 1), diffDiffHunk.target().range());
    }

    @Test
    void simpleRemoval() throws IOException {
        var ft = FileType.fromOctal("100644");
        var path = Path.of("README");
        var hash = Hash.zero();
        var status = Status.from('A');

        var aHunks = new ArrayList<Hunk>();
        aHunks.add(new Hunk(new Range(0, 0), List.of(),
                            new Range(0, 1), List.of("Hello, world", "Hello again!")));
        var aPatches = new ArrayList<Patch>();
        aPatches.add(new TextualPatch(path, ft, hash,
                                      path, ft, hash,
                                      status, aHunks));
        var aDiff = new Diff(hash, hash, aPatches);

        var bHunks = new ArrayList<Hunk>();
        bHunks.add(new Hunk(new Range(0, 0), List.of(),
                            new Range(0, 2), List.of("Hello, world")));
        var bPatches = new ArrayList<Patch>();
        bPatches.add(new TextualPatch(path, ft, hash,
                                      path, ft, hash,
                                      status, bHunks));
        var bDiff = new Diff(hash, hash, bPatches);

        var diffDiff = DiffComparator.diff(aDiff, bDiff);
        var diffDiffPatches = diffDiff.patches();
        assertEquals(1, diffDiffPatches.size());
        assertTrue(diffDiffPatches.get(0).isTextual());

        var diffDiffPatch = diffDiffPatches.get(0).asTextualPatch();
        assertTrue(diffDiffPatch.status().isModified());
        assertEquals(Optional.of(path), diffDiffPatch.source().path());
        assertEquals(Optional.of(path), diffDiffPatch.target().path());

        var diffDiffHunks = diffDiffPatch.hunks();
        assertEquals(1, diffDiffHunks.size());
        var diffDiffHunk = diffDiffHunks.get(0);
        assertEquals(List.of("Hello again!"), diffDiffHunk.source().lines());
        assertEquals(List.of(), diffDiffHunk.target().lines());
        assertEquals(new Range(2, 1), diffDiffHunk.source().range());
        assertEquals(new Range(2, 0), diffDiffHunk.target().range());
    }

    @Test
    void removalOfSameFile() throws IOException {
        var ft = FileType.fromOctal("100644");
        var path1 = Path.of("README1");
        var path2 = Path.of("README1");
        var hash = Hash.zero();
        var status = Status.from('D');

        var aHunks = new ArrayList<Hunk>();
        aHunks.add(new Hunk(new Range(1, 1), List.of("Hello world"),
                            new Range(1, 0), List.of()));
        var aPatches = new ArrayList<Patch>();
        aPatches.add(new TextualPatch(path1, ft, hash,
                                      path1, ft, hash,
                                      status, aHunks));
        var aDiff = new Diff(hash, hash, aPatches);

        var bHunks = new ArrayList<Hunk>();
        bHunks.add(new Hunk(new Range(1, 1), List.of("Hello world"),
                            new Range(1, 0), List.of()));
        var bPatches = new ArrayList<Patch>();
        bPatches.add(new TextualPatch(path2, ft, hash,
                                      path2, ft, hash,
                                      status, bHunks));
        var bDiff = new Diff(hash, hash, bPatches);

        var diffDiff = DiffComparator.diff(aDiff, bDiff);
        var diffDiffPatches = diffDiff.patches();
        assertEquals(List.of(), diffDiffPatches);
    }

    @Test
    void removalOfDifferentFile() throws IOException {
        var ft = FileType.fromOctal("100644");
        var path1 = Path.of("README1");
        var path2 = Path.of("README2");
        var hash = Hash.zero();
        var status = Status.from('D');

        var aHunks = new ArrayList<Hunk>();
        aHunks.add(new Hunk(new Range(1, 1), List.of("Hello world"),
                            new Range(1, 0), List.of()));
        var aPatches = new ArrayList<Patch>();
        aPatches.add(new TextualPatch(path1, ft, hash,
                                      path1, ft, hash,
                                      status, aHunks));
        var aDiff = new Diff(hash, hash, aPatches);

        var bHunks = new ArrayList<Hunk>();
        bHunks.add(new Hunk(new Range(1, 1), List.of("Hello world"),
                            new Range(1, 0), List.of()));
        var bPatches = new ArrayList<Patch>();
        bPatches.add(new TextualPatch(path2, ft, hash,
                                      path2, ft, hash,
                                      status, bHunks));
        var bDiff = new Diff(hash, hash, bPatches);

        var diffDiff = DiffComparator.diff(aDiff, bDiff);
        var diffDiffPatches = diffDiff.patches();
        assertEquals(2, diffDiffPatches.size());

        var added = diffDiffPatches.get(0);
        assertTrue(added.status().isAdded());
        assertEquals(Optional.of(path1), added.target().path());

        var deleted = diffDiffPatches.get(1);
        assertTrue(deleted.status().isDeleted());
        assertEquals(Optional.of(path2), deleted.source().path());
    }
}
