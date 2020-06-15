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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.io.IOException;
import java.nio.file.Path;

class SymlinkCheckTests {
    private final Utilities utils = new Utilities();

    private static final List<String> CONFIGURATION = List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = symlink"
    );

    private static JCheckConfiguration conf() {
        return JCheckConfiguration.parse(CONFIGURATION);
    }

    private static List<Diff> symlinkDiff(String filename) {
        var patch = new TextualPatch(null, null, Hash.zero(),
                                     Path.of(filename), FileType.fromOctal("120000"), Hash.zero(),
                                     Status.from('A'), List.of());
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch));
        return List.of(diff);
    }

    private static List<Diff> diff(String filename, String line) {
        var hunk = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of(line));
        var patch = new TextualPatch(Path.of(filename), FileType.fromOctal("100644"), Hash.zero(),
                                     Path.of(filename), FileType.fromOctal("100644"), Hash.zero(),
                                     Status.from('M'), List.of(hunk));
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch));
        return List.of(diff);
    }

    private static Commit commit(List<Diff> diffs) {
        var author = new Author("foo", "foo@localhost");
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(hash);
        var authored = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, List.of("Added symlink"));
        return new Commit(metadata, diffs);
    }

    private static Commit commitWithSymlink(String filename) {
        return commit(symlinkDiff(filename));
    }

    private static Commit commitWithRegularFile(String filename, String line) {
        return commit(diff(filename, line));
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    @Test
    void commitWithSymlinkShouldFail() {
        var commit = commitWithSymlink("symlink");
        var message = message(commit);
        var check = new SymlinkCheck();
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof SymlinkIssue);
        var issue = (SymlinkIssue) issues.get(0);
        assertEquals("symlink", issue.path().toString());
    }

    @Test
    void commitWithoutSymlinkShouldPass() {
        var commit = commitWithRegularFile("README.txt", "Hello, world");
        var message = message(commit);
        var check = new SymlinkCheck();
        var issues = toList(check.check(commit, message, conf()));
        assertEquals(List.of(), issues);
    }
}
