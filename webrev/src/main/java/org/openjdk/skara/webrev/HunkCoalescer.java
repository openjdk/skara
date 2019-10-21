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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.*;

import java.util.*;

class Line {
    private final int number;
    private final String text;

    public Line(int number, String text) {
        this.number = number;
        this.text = text;
    }

    public int number() {
        return number;
    }

    public String text() {
        return text;
    }
}

class ContextHunk {
    private List<Line> removed;
    private List<Line> added;
    private Context contextAfter;

    public ContextHunk(List<Line> removed, List<Line> added, Context contextAfter) {
        this.removed = removed;
        this.added = added;
        this.contextAfter = contextAfter;
    }

    public List<Line> removed() {
        return removed;
    }

    public List<Line> added() {
        return added;
    }

    public Context contextAfter() {
        return contextAfter;
    }
}

class Header {
    private final Range source;
    private final Range target;

    public Header(Range source, Range target) {
        this.source = source;
        this.target = target;
    }

    public Range source() {
        return source;
    }

    public Range target() {
        return target;
    }
}

class Context {
    private final List<Line> sourceLines;
    private final List<Line> destinationLines;

    public Context(List<Line> sourceLines, List<Line> destinationLines) {
        this.sourceLines = sourceLines;
        this.destinationLines = destinationLines;
    }

    public List<Line> sourceLines() {
        return sourceLines;
    }

    public List<Line> destinationLines() {
        return destinationLines;
    }
}

class HunkGroup {
    private final Header header;
    private Context contextBefore;
    private List<ContextHunk> hunks;

    public HunkGroup(Header header, Context contextBefore, List<ContextHunk> hunks) {
        this.header = header;
        this.contextBefore = contextBefore;
        this.hunks = hunks;
    }

    Header header() {
        return header;
    }

    Context contextBefore() {
        return contextBefore;
    }

    List<ContextHunk> hunks() {
        return hunks;
    }
}

class HunkCoalescer {
    private final int numContextLines;
    private final List<String> sourceContent;
    private final List<String> destContent;

    public HunkCoalescer(int numContextLines, List<String> sourceContent, List<String> destContent) {
        this.numContextLines = numContextLines;
        this.sourceContent = sourceContent;
        this.destContent = destContent;
    }

    public List<Hunk> nextGroup(LinkedList<Hunk> hunks) {
        var hunksInRange = new ArrayList<Hunk>();
        hunksInRange.add(hunks.removeFirst());

        while (!hunks.isEmpty()) {
            var next = hunks.peekFirst();
            var last = hunksInRange.get(hunksInRange.size() - 1);
            var destEnd = last.target().range().end() + numContextLines;
            var sourceEnd = last.source().range().end() + numContextLines;
            var nextDestStart = next.target().range().start() - numContextLines;
            var nextSourceStart = next.source().range().start() - numContextLines;
            if (sourceEnd >= nextSourceStart ||
                destEnd >= nextDestStart) {
                hunksInRange.add(hunks.removeFirst());
            } else {
                break;
            }
        }
        return hunksInRange;
    }

    private Header calculateCoalescedHeader(Hunk first, Hunk last) {
        var sourceStart = first.source().range().start() - numContextLines;
        sourceStart = Math.max(sourceStart, 1);

        var destStart = first.target().range().start() - numContextLines;
        destStart = Math.max(destStart, 1);

        var sourceEnd = last.source().range().end() + numContextLines;
        sourceEnd = Math.min(sourceEnd, sourceContent.size() + 1);

        var destEnd = last.target().range().end() + numContextLines;
        destEnd = Math.min(destEnd, destContent.size() + 1);

        var sourceCount = sourceEnd - sourceStart;
        var destCount = destEnd - destStart;

        return new Header(new Range(sourceStart, sourceCount),
                          new Range(destStart, destCount));
    }

    private Context createContextBeforeGroup(Header header, Hunk first) {
        var sourceContextBeforeStart = header.source().start();
        var sourceContextBeforeEnd = first.source().range().start();
        var sourceBeforeContextCount = sourceContextBeforeEnd - sourceContextBeforeStart;

        var destContextBeforeStart = header.target().start();
        var destContextBeforeEnd = first.target().range().start();
        var destBeforeContextCount = destContextBeforeEnd - destContextBeforeStart;

        var beforeContextCount = Math.min(destBeforeContextCount, sourceBeforeContextCount);
        assert beforeContextCount <= numContextLines;

        sourceContextBeforeStart = sourceContextBeforeEnd - beforeContextCount;
        destContextBeforeStart = destContextBeforeEnd - beforeContextCount;

        var sourceContextBefore = new ArrayList<Line>();
        for (var lineNum = sourceContextBeforeStart; lineNum < sourceContextBeforeEnd; lineNum++) {
            sourceContextBefore.add(new Line(lineNum, sourceContent.get(lineNum - 1)));
        }

        var destContextBefore = new ArrayList<Line>();
        for (var lineNum = destContextBeforeStart; lineNum < destContextBeforeEnd; lineNum++) {
            destContextBefore.add(new Line(lineNum, destContent.get(lineNum - 1)));
        }

        return new Context(sourceContextBefore, destContextBefore);
    }

    private List<Line> removedLines(Hunk hunk) {
        var removed = new ArrayList<Line>();

        var removedStart = hunk.source().range().start();
        var removedEnd = hunk.source().range().end();
        for (var lineNum = removedStart; lineNum < removedEnd; lineNum++) {
            var text = sourceContent.get(lineNum - 1);
            removed.add(new Line(lineNum, text));
        }

        assert removed.size() == hunk.source().lines().size();

        return removed;
    }

    private List<Line> addedLines(Hunk hunk) {
        var added = new ArrayList<Line>();
        var addedStart = hunk.target().range().start();
        var addedEnd = hunk.target().range().end();
        for (var lineNum = addedStart; lineNum < addedEnd; lineNum++) {
            var text = destContent.get(lineNum - 1);
            added.add(new Line(lineNum, text));
        }

        assert added.size() == hunk.target().lines().size();

        return added;
    }

    private Context createContextAfterHunk(Hunk hunk, Hunk nextNonEmptySourceHunk, Hunk nextNonEmptyTargetHunk) {
        var sourceAfterContextStart = hunk.source().range().end();
        var sourceAfterContextEnd = hunk.source().range().end() + numContextLines;
        if (nextNonEmptySourceHunk != null || nextNonEmptyTargetHunk != null) {
            sourceAfterContextEnd += numContextLines; // include the "before" context for the next hunk
        }
        sourceAfterContextEnd = Math.min(sourceAfterContextEnd, sourceContent.size() + 1);
        if (nextNonEmptySourceHunk != null) {
            var nextNonEmptySourceHunkStart = nextNonEmptySourceHunk.source().range().start();
            sourceAfterContextEnd = sourceAfterContextEnd > nextNonEmptySourceHunkStart
                    ? Math.min(sourceAfterContextEnd, nextNonEmptySourceHunkStart)
                    : Math.max(sourceAfterContextEnd, nextNonEmptySourceHunkStart);
        }
        var sourceAfterContextCount = sourceAfterContextEnd - sourceAfterContextStart;

        var destAfterContextStart = hunk.target().range().end();
        var destAfterContextEnd = hunk.target().range().end() + numContextLines;
        if (nextNonEmptySourceHunk != null || nextNonEmptyTargetHunk != null) {
            destAfterContextEnd += numContextLines; // include the "before" context for the next hunk
        }
        destAfterContextEnd = Math.min(destAfterContextEnd, destContent.size() + 1);
        if (nextNonEmptyTargetHunk != null) {
            var nextNonEmptyTargetHunkStart = nextNonEmptyTargetHunk.target().range().start();
            destAfterContextEnd = destAfterContextEnd > nextNonEmptyTargetHunkStart
                    ? Math.min(destAfterContextEnd, nextNonEmptyTargetHunkStart)
                    : Math.max(destAfterContextEnd, nextNonEmptyTargetHunkStart);
        }
        var destAfterContextCount = destAfterContextEnd - destAfterContextStart;

        var afterContextCount = Math.min(sourceAfterContextCount, destAfterContextCount);

        var sourceLineNumStart = hunk.source().lines().isEmpty() && hunk.source().range().start() == 0 ?
            sourceAfterContextStart + 1 : sourceAfterContextStart;
        var sourceEndingLineNum = sourceLineNumStart + afterContextCount;
        var sourceContextAfter = new ArrayList<Line>();
        for (var lineNum = sourceLineNumStart; lineNum < sourceEndingLineNum; lineNum++) {
            var text = sourceContent.get(lineNum - 1);
            sourceContextAfter.add(new Line(lineNum, text));
        }

        var destLineNumStart = hunk.target().lines().isEmpty() && hunk.target().range().start() == 0 ?
            destAfterContextStart + 1 : destAfterContextStart;
        var destEndingLineNum = destLineNumStart + afterContextCount;
        var destContextAfter = new ArrayList<Line>();
        for (var lineNum = destLineNumStart; lineNum < destEndingLineNum; lineNum++) {
            var text = destContent.get(lineNum - 1);
            destContextAfter.add(new Line(lineNum, text));
        }

        return new Context(sourceContextAfter, destContextAfter);
    }

    public List<HunkGroup> coalesce(List<Hunk> originalHunks) {
        var groups = new ArrayList<HunkGroup>();

        var worklist = new LinkedList<Hunk>(originalHunks);
        while (!worklist.isEmpty()) {
            var hunkGroup = nextGroup(worklist);

            var first = hunkGroup.get(0);
            var last = hunkGroup.get(hunkGroup.size() - 1);
            var header = calculateCoalescedHeader(first, last);

            var contextBefore = createContextBeforeGroup(header, first);

            var hunksWithContext = new ArrayList<ContextHunk>();
            for (var i = 0; i < hunkGroup.size(); i++) {
                var hunk = hunkGroup.get(i);

                var removed = removedLines(hunk);
                var added = addedLines(hunk);

                Hunk nextNonEmptySourceHunk = null;;
                for (var j = i + 1; j < hunkGroup.size(); j++) {
                    var next = hunkGroup.get(j);
                    if (next.source().range().count() > 0) {
                        nextNonEmptySourceHunk = next;
                        break;
                    }
                }
                Hunk nextNonEmptyTargetHunk = null;
                for (var j = i + 1; j < hunkGroup.size(); j++) {
                    var next = hunkGroup.get(j);
                    if (next.target().range().count() > 0) {
                        nextNonEmptyTargetHunk = next;
                        break;
                    }
                }
                var contextAfter = createContextAfterHunk(hunk, nextNonEmptySourceHunk, nextNonEmptyTargetHunk);

                hunksWithContext.add(new ContextHunk(removed, added, contextAfter));
            }

            groups.add(new HunkGroup(header, contextBefore, hunksWithContext));
        }

        return groups;
    }
}
