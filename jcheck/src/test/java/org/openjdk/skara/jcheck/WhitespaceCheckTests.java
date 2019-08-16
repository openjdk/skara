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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.census.Census;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.io.IOException;

class WhitespaceCheckTests {
    private static final Hash NULL_HASH = new Hash("0".repeat(40));
    private static List<Diff> parentDiffs(String filename, String line) {
        var hunk = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of(line));
        var patch = new TextualPatch(Path.of(filename), FileType.fromOctal("100644"), NULL_HASH,
                                     Path.of(filename), FileType.fromOctal("100644"), NULL_HASH,
                                     Status.from('M'), List.of(hunk));
        var diff = new Diff(NULL_HASH, NULL_HASH, List.of(patch));
        return List.of(diff);
    }

    private static final List<String> CONFIGURATION = List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = whitespace",
        "[checks \"whitespace\"]"
    );

    private static JCheckConfiguration configuration(String files) {
        var lines = new ArrayList<>(CONFIGURATION);
        lines.add("files = " + files);
        return JCheckConfiguration.parse(lines);
    }

    private static Commit commit(List<Diff> parentDiffs) {
        var author = new Author("Foo Bar", "foo@bar.org");
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"));
        var date = ZonedDateTime.now();
        var message = List.of("Initial commit", "", "Reviewed-by: baz");
        var metadata = new CommitMetadata(hash, parents, author, author, date, message);
        return new Commit(metadata, parentDiffs);
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
    void noBadWhitespaceShouldPass() {
        var commit = commit(parentDiffs("README.md", "An additional line"));
        var conf = configuration("README\\.md");
        var check = new WhitespaceCheck();
        var issues = toList(check.check(commit, message(commit), conf));

        assertEquals(0, issues.size());
    }

    @Test
    void trailingWhitespaceShouldFail() {
        var filename = "README.md";
        var line = "An additional line ";
        var commit = commit(parentDiffs(filename, line));
        var conf = configuration("README\\.md");
        var message = message(commit);
        var check = new WhitespaceCheck();
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof WhitespaceIssue);
        var issue = (WhitespaceIssue) issues.get(0);
        assertEquals(Path.of(filename), issue.path());
        assertEquals(1, issue.row());
        assertEquals(line, issue.line());
        assertEquals(List.of(new WhitespaceIssue.Error(line.length() - 1, WhitespaceIssue.Whitespace.TRAILING)),
                     issue.errors());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void tabShouldFail() {
        var filename = "README.md";
        var line = "\tAn additional line";
        var commit = commit(parentDiffs(filename, line));
        var conf = configuration("README\\.md");
        var message = message(commit);
        var check = new WhitespaceCheck();
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof WhitespaceIssue);
        var issue = (WhitespaceIssue) issues.get(0);
        assertEquals(Path.of(filename), issue.path());
        assertEquals(1, issue.row());
        assertEquals(line, issue.line());
        assertEquals(List.of(new WhitespaceIssue.Error(0, WhitespaceIssue.Whitespace.TAB)),
                     issue.errors());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void crShouldFail() {
        var filename = "README.md";
        var line = "An additional line\r\n";
        var commit = commit(parentDiffs(filename, line));
        var conf = configuration("README\\.md");
        var message = message(commit);
        var check = new WhitespaceCheck();
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof WhitespaceIssue);
        var issue = (WhitespaceIssue) issues.get(0);
        assertEquals(Path.of(filename), issue.path());
        assertEquals(1, issue.row());
        assertEquals(line, issue.line());
        assertEquals(List.of(new WhitespaceIssue.Error(line.length() - 2, WhitespaceIssue.Whitespace.CR)),
                     issue.errors());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
    }
}
