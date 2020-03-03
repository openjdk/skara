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

class CommitterCheckTests {
    private static final List<String> CENSUS = List.of(
        "<?xml version=\"1.0\" encoding=\"us-ascii\"?>",
        "<census time=\"2019-03-13T10:29:41-07:00\">",
        "  <person name=\"foo\">",
        "    <full-name>Foo</full-name>",
        "  </person>",
        "  <person name=\"bar\">",
        "    <full-name>Bar</full-name>",
        "  </person>",
        "  <person name=\"baz\">",
        "    <full-name>Baz</full-name>",
        "  </person>",
        "  <group name=\"test\">",
        "    <full-name>Test</full-name>",
        "    <person ref=\"foo\" role=\"lead\" />",
        "    <person ref=\"bar\" />",
        "    <person ref=\"baz\" />",
        "  </group>",
        "  <project name=\"test\">",
        "    <full-name>Test</full-name>",
        "    <sponsor ref=\"test\" />",
        "    <person role=\"lead\" ref=\"foo\" />",
        "    <person role=\"committer\" ref=\"bar\" />",
        "    <person role=\"author\" ref=\"baz\" />",
        "  </project>",
        "</census>"
    );

    private static final List<String> CONFIGURATION = List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = committer"
    );

    private static Commit mergeCommit(Author author, Author committer) {
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(hash, hash);
        var date = ZonedDateTime.now();
        var message = List.of("Merge");
        var metadata = new CommitMetadata(hash, parents, author, committer, date, message);
        return new Commit(metadata, List.of());
    }

    private static Commit commit(Author author, Author committer) {
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"));
        var date = ZonedDateTime.now();
        var message = List.of("Initial commit");
        var metadata = new CommitMetadata(hash, parents, author, committer, date, message);
        return new Commit(metadata, List.of());
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    private static Census census() throws IOException {
        return Census.parse(CENSUS);
    }

    private static JCheckConfiguration conf() throws IOException {
        return JCheckConfiguration.parse(CONFIGURATION);
    }

    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    @Test
    void authorIsLeadShouldPass() throws IOException {
        var author = new Author("Foo", "foo@localhost");
        var commit = commit(author, author);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message(commit), conf()));
        assertEquals(0, issues.size());
    }

    @Test
    void authorIsCommitterShouldPass() throws IOException {
        var author = new Author("Bar", "bar@localhost");
        var commit = commit(author, author);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message(commit), conf()));
        assertEquals(0, issues.size());
    }

    @Test
    void authorIsAuthorShouldNotWork() throws IOException {
        var author = new Author("Baz", "baz@localhost");
        var commit = commit(author, author);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        var issue = issues.get(0);
        assertTrue(issue instanceof CommitterIssue);
        var committerIssue = (CommitterIssue) issue;
        assertEquals("test", committerIssue.project().name());
        assertEquals(commit, committerIssue.commit());
        assertEquals(CommitterCheck.class, committerIssue.check().getClass());
        assertEquals(message, committerIssue.message());
        assertEquals(Severity.ERROR, committerIssue.severity());
    }

    @Test
    void unknownAuthorAndCommitterShouldFail() throws IOException {
        var author = new Author("Foo", "foo@host.org");
        var committer = new Author("Bar", "bar@host.org");
        var commit = commit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        var issue = issues.get(0);
        assertTrue(issue instanceof CommitterEmailIssue);
        var committerIssue = (CommitterEmailIssue) issue;
        assertEquals("localhost", committerIssue.expectedDomain());
        assertEquals(commit, committerIssue.commit());
        assertEquals(check, committerIssue.check());
        assertEquals(message, committerIssue.message());
        assertEquals(Severity.ERROR, committerIssue.severity());
    }

    @Test
    void unknownAuthorAndKnownCommitterShouldPass() throws IOException {
        var author = new Author("Foo", "foo@host.org");
        var committer = new Author("bar", "bar@localhost");
        var commit = commit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));
        assertEquals(0, issues.size());
    }

    @Test
    void unknownAuthorAndKnownAuthorShouldFail() throws IOException {
        var author = new Author("Foo", "foo@host.org");
        var committer = new Author("Baz", "baz@localhost");
        var commit = commit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        var issue = issues.get(0);
        assertTrue(issue instanceof CommitterIssue);
        var committerIssue = (CommitterIssue) issue;
        assertEquals("test", committerIssue.project().name());
        assertEquals(commit, committerIssue.commit());
        assertEquals(CommitterCheck.class, committerIssue.check().getClass());
        assertEquals(message, committerIssue.message());
        assertEquals(Severity.ERROR, committerIssue.severity());
    }

    @Test
    void missingCommitterNameShouldFail() throws IOException {
        var author = new Author("Foo", "foo@host.org");
        var committer = new Author("", "baz@localhost");
        var commit = commit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(2, issues.size());
        assertTrue(issues.get(0) instanceof CommitterNameIssue);
        var issue = (CommitterNameIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void missingCommitterEmailShouldFail() throws IOException {
        var author = new Author("Foo", "foo@host.org");
        var committer = new Author("Baz", "");
        var commit = commit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(2, issues.size());
        assertTrue(issues.get(0) instanceof CommitterEmailIssue);
        var issue = (CommitterEmailIssue) issues.get(0);
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(message, issue.message());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void allowedToMerge() throws IOException {
        var author = new Author("baz", "baz@localhost");
        var committer = new Author("baz", "baz@localhost");
        var commit = mergeCommit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var issues = toList(check.check(commit, message, conf()));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof CommitterIssue);

        check = new CommitterCheck(census());
        var text = new ArrayList<>(CONFIGURATION);
        text.addAll(List.of("[checks \"committer\"]", "allowed-to-merge=baz"));
        var conf = JCheckConfiguration.parse(text);
        issues = toList(check.check(commit, message, conf));
        assertEquals(List.of(), issues);
    }

    @Test
    void allowedToMergeShouldOnlyWorkForMergeCommits() throws IOException {
        var author = new Author("baz", "baz@localhost");
        var committer = new Author("baz", "baz@localhost");
        var commit = commit(author, committer);
        var message = message(commit);
        var check = new CommitterCheck(census());
        var text = new ArrayList<>(CONFIGURATION);
        text.addAll(List.of("[checks \"committer\"]", "allowed-to-merge=baz"));
        var conf = JCheckConfiguration.parse(text);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof CommitterIssue);
    }
}
