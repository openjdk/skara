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

class ExecutableCheckTests {
    private static final Hash NULL_HASH = new Hash("0".repeat(40));
    private static final JCheckConfiguration conf = JCheckConfiguration.parse(List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = executable"
    ));

    private static List<Diff> parentDiffs(String filename, String mode) {
        var hunk = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of("An additional line"));
        var patch = new TextualPatch(Path.of(filename), FileType.fromOctal("100644"), NULL_HASH,
                                     Path.of(filename), FileType.fromOctal(mode), NULL_HASH,
                                     Status.from('M'), List.of(hunk));
        var diff = new Diff(NULL_HASH, NULL_HASH, List.of(patch));
        return List.of(diff);
    }


    private static Commit commit(List<Diff> parentDiffs) {
        var author = new Author("foo", "foo@host.org");
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(hash, hash);
        var message = List.of("A commit");
        var date = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, author, date, message);
        return new Commit(metadata, parentDiffs);
    }

    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    @Test
    void regularFileShouldPass() throws IOException {
        var commit = commit(parentDiffs("README", "100644"));
        var message = message(commit);
        var check = new ExecutableCheck();
        var issues = toList(check.check(commit, message, conf));
        assertEquals(0, issues.size());
    }

    @Test
    void executableFileShouldFail() throws IOException {
        var commit = commit(parentDiffs("README", "100755"));
        var message = message(commit);
        var check = new ExecutableCheck();
        var issues = toList(check.check(commit, message, conf));
        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof ExecutableIssue);
        var issue = (ExecutableIssue) issues.get(0);
        assertEquals(Path.of("README"), issue.path());
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }
}

