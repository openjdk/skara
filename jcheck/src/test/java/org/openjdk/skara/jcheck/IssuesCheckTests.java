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

import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.CommitMetadata;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.io.IOException;

class IssuesCheckTests {
    private final Utilities utils = new Utilities();

    private static final List<String> CONFIGURATION = List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = issues"
    );

    private static JCheckConfiguration conf() {
        return JCheckConfiguration.parse(CONFIGURATION);
    }

    private static Commit commit(List<String> message) {
        var author = new Author("foo", "foo@host.org");
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(hash);
        var date = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, author, date, message);
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
    void titleOnlyMessageShouldFail() {
        var commit = commit(List.of("Bugfix"));
        var message = message(commit);
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof IssuesIssue);
        var issue = (IssuesIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check.getClass(), issue.check().getClass());
    }

    @Test
    void singleIssueReferenceShouldPass() {
        var commit = commit(List.of("1234570: A bug"));
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message(commit), conf()));
        assertEquals(0, issues.size());
    }

    @Test
    void multipleIssueReferencesShouldPass() {
        var commit = commit(List.of("1234570: A bug", "1234567: Another bug"));
        var message = message(commit);
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message, conf()));
        assertEquals(0, issues.size());
    }

    @Test
    void issueWithLeadingZeroShouldFail() {
        var commit = commit(List.of("0123456: A bug"));
        var message = message(commit);
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof IssuesIssue);
        var issue = (IssuesIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check.getClass(), issue.check().getClass());
    }

    @Test
    void issueWithTooFewDigitsShouldFail() {
        var commit = commit(List.of("123456: A bug"));
        var message = message(commit);
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof IssuesIssue);
        var issue = (IssuesIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check.getClass(), issue.check().getClass());
    }

    @Test
    void issueWithTooManyDigitsShouldFail() {
        var commit = commit(List.of("12345678: A bug"));
        var message = message(commit);
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof IssuesIssue);
        var issue = (IssuesIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check.getClass(), issue.check().getClass());
    }

    @Test
    void issueWithPrefixShouldFail() {
        var commit = commit(List.of("JDK-7654321: A bug"));
        var message = message(commit);
        var check = new IssuesCheck(utils);
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof IssuesIssue);
        var issue = (IssuesIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(check.getClass(), issue.check().getClass());
    }
}
