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

import java.util.*;
import java.time.ZonedDateTime;

class BlacklistCheckTests {
    private static final JCheckConfiguration conf = JCheckConfiguration.parse(List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = blacklist"
    ));

    private static Commit commit(Hash hash) {
        var author = new Author("Foo", "foo@bar.org");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"));
        var authored = ZonedDateTime.now();
        var message = List.of("Initial commit");
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, message);
        return new Commit(metadata, List.of());
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
    void commitNotOnBlacklistShouldPass() {
        var hash = new Hash("0123456789012345678901234567890123456789");
        var commit = commit(hash);
        var check = new BlacklistCheck(Set.of());
        var issues = toList(check.check(commit, message(commit), conf));
        assertEquals(0, issues.size());
    }

    @Test
    void commitOnBlacklistShouldFail() {
        var hash = new Hash("0123456789012345678901234567890123456789");
        var commit = commit(hash);
        var message = message(commit);
        var check = new BlacklistCheck(Set.of(hash));
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof BlacklistIssue);
        var issue = (BlacklistIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }
}
