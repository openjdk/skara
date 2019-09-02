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
package org.openjdk.skara.webrev;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.vcs.Hunk;
import org.openjdk.skara.vcs.Range;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HunkCoalescerTest {

    @Test
    void testOverlappingContextMerge() {
        var sourceContent = List.of(
                "A",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "B"
        );

        var targetContent = List.of(
                "C",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "D"
        );

        var hunk1 = new Hunk(new Range(1, 1), List.of("A"), new Range(1, 1), List.of("C"));
        var hunk2 = new Hunk(new Range(10, 1), List.of("B"), new Range(10, 1), List.of("D"));

        var coalescer = new HunkCoalescer(5, sourceContent, targetContent);
        var groups = coalescer.coalesce(List.of(hunk1, hunk2));

        assertEquals(1, groups.size());
        var group = groups.get(0);
        assertEquals(10, group.header().source().count());
        assertEquals(10, group.header().target().count());

        assertEquals(2, group.hunks().size());
        var contextHunk1 = group.hunks().get(0);
        assertEquals(8, contextHunk1.contextAfter().sourceLines().size());
        assertEquals(8, contextHunk1.contextAfter().destinationLines().size());
    }

    @Test
    void testContextOverlapsContent() {
        var sourceContent = List.of(
                "A",
                "",
                "",
                "B"
        );

        var targetContent = List.of(
                "C",
                "",
                "",
                "D"
        );

        var hunk1 = new Hunk(new Range(1, 1), List.of("A"), new Range(1, 1), List.of("C"));
        var hunk2 = new Hunk(new Range(4, 1), List.of("B"), new Range(4, 1), List.of("D"));

        var coalescer = new HunkCoalescer(5, sourceContent, targetContent);
        var groups = coalescer.coalesce(List.of(hunk1, hunk2));

        assertEquals(1, groups.size());
        var group = groups.get(0);
        assertEquals(4, group.header().source().count());
        assertEquals(4, group.header().target().count());

        assertEquals(2, group.hunks().size());
        var contextHunk1 = group.hunks().get(0);
        assertEquals(2, contextHunk1.contextAfter().sourceLines().size());
        assertEquals(2, contextHunk1.contextAfter().destinationLines().size());
    }

    @Test
    void testNonOverllapping() {
        var sourceContent = List.of(
                "A",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "B"
        );

        var targetContent = List.of(
                "C",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "D"
        );

        var hunk1 = new Hunk(new Range(1, 1), List.of("A"), new Range(1, 1), List.of("C"));
        var hunk2 = new Hunk(new Range(14, 1), List.of("B"), new Range(14, 1), List.of("D"));

        var coalescer = new HunkCoalescer(5, sourceContent, targetContent);
        var groups = coalescer.coalesce(List.of(hunk1, hunk2));

        assertEquals(2, groups.size());

        var group1 = groups.get(0);
        assertEquals(6, group1.header().source().count());
        assertEquals(6, group1.header().target().count());

        assertEquals(1, group1.hunks().size());
        var contextHunk1 = group1.hunks().get(0);
        assertEquals(5, contextHunk1.contextAfter().sourceLines().size());
        assertEquals(5, contextHunk1.contextAfter().destinationLines().size());

        var group2 = groups.get(1);
        assertEquals(6, group2.header().source().count());
        assertEquals(6, group2.header().target().count());

        assertEquals(1, group2.hunks().size());
        assertEquals(5, group2.contextBefore().sourceLines().size());
        assertEquals(5, group2.contextBefore().destinationLines().size());
    }
}
