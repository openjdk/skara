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

import org.junit.jupiter.api.Test;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MergeMessageCheckTests {
    private static final List<String> CONFIGURATION = List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = merge",
        "[checks \"merge\"]",
        "message = Merge"
    );

    private static JCheckConfiguration conf() throws IOException {
        return JCheckConfiguration.parse(CONFIGURATION);
    }

    private static Commit commit(List<String> message) {
        var author = new Author("foo", "foo@host.org");
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(hash, hash);
        var authored = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, message);
        return new Commit(metadata, List.of());
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
    void correctMessageShouldPass() throws IOException {
        var commit = commit(List.of("Merge"));
        var message = message(commit);
        var check = new MergeMessageCheck();
        var issues = toList(check.check(commit, message, conf(), null));
        assertEquals(0, issues.size());
    }

    @Test
    void incorrectMessageShouldFail() throws IOException {
        var commit = commit(List.of("Work"));
        var message = message(commit);
        var check = new MergeMessageCheck();
        var issues = toList(check.check(commit, message, conf(), null));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof MergeMessageIssue);
    }

    @Test
    void multiLineMessageShouldWork() throws IOException {
        var commit = commit(List.of("Merge", "", "This is a summary"));
        var message = message(commit);
        var check = new MergeMessageCheck();
        var issues = toList(check.check(commit, message, conf(), null));

        assertEquals(List.of(), issues);
    }

    @Test
    void usingRegexShouldWork() throws IOException {
        var commit = commit(List.of("Merge 'feature' into 'master'"));
        var message = message(commit);
        var check = new MergeMessageCheck();
        var conf = new ArrayList<>(CONFIGURATION);
        conf.set(conf.size() - 1, "message = Merge \\'[a-z]+\\' into \\'[a-z]+\\'");
        var issues = toList(check.check(commit, message, JCheckConfiguration.parse(conf), null));

        assertEquals(List.of(), issues);
    }
}
