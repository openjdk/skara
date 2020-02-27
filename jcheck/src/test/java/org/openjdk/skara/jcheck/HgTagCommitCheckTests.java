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

class HgTagCommitCheckTests {
    private static List<Diff> parentDiffs(String line) {
        var hunk = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of(line));
        var patch = new TextualPatch(Path.of(".hgtags"), FileType.fromOctal("100644"), Hash.zero(),
                                     Path.of(".hgtags"), FileType.fromOctal("100644"), Hash.zero(),
                                     Status.from('M'), List.of(hunk));
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch));
        return List.of(diff);
    }

    private static final JCheckConfiguration conf = JCheckConfiguration.parse(List.of(
        "[general]",
        "project = test",
        "[repository]",
        "tags=skara-(?:[1-9](?:[0-9]*)(?:\\.[0-9]){0,3})\\+(?:[0-9]+)",
        "[checks]",
        "error = hg-tag"
    ));

    private static Commit commit(Hash hash, List<String> message, List<Diff> parentDiffs) {
        var author = new Author("Foo Bar", "foo@bar.org");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"));
        var date = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, author, date, message);
        return new Commit(metadata, parentDiffs);
    }

    private static Commit mergeCommit() {
        var author = new Author("Foo Bar", "foo@bar.org");
        var parents = List.of(new Hash("12345789012345789012345678901234567890"),
                              new Hash("12345789012345789012345678901234567890"));
        var message = List.of("Merge");
        var date = ZonedDateTime.now();
        var metadata = new CommitMetadata(Hash.zero(), parents, author, author, date, message);
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
    void regularTagShoudlPass() {
        var targetHash = "12345789012345789012345678901234567890";
        var commitHash = "1111222233334444555566667777888899990000";
        var tag = "skara-11+22";
        var diffs = parentDiffs(targetHash + " " + tag);
        var lines = List.of("Added tag " + tag + " for changeset " + targetHash);
        var commit = commit(new Hash(commitHash), lines, diffs);
        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));
        assertEquals(0, issues.size());
    }

    @Test
    void commitThatDoesNotAddTagShouldPass() {
        var commit = commit(Hash.zero(), List.of(), List.of());
        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));
        assertEquals(0, issues.size());
    }

    @Test
    void mergeCommitShouldPass() {
        var commit = mergeCommit();
        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));
        assertEquals(0, issues.size());
    }

    @Test
    void multiLineMessageShouldFail() {
        var targetHash = "12345789012345789012345678901234567890";
        var commitHash = "1111222233334444555566667777888899990000";
        var tag = "skara-11+22";
        var diffs = parentDiffs(targetHash + " " + tag);
        var lines = List.of("Added tag " + tag + " for changeset " + targetHash, "Another line");
        var commit = commit(new Hash(commitHash), lines, diffs);
        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof HgTagCommitIssue);
        var issue = (HgTagCommitIssue) issues.get(0);
        assertEquals(HgTagCommitIssue.Error.TOO_MANY_LINES, issue.error());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void badCommitMessageShouldFail() {
        var targetHash = "12345789012345789012345678901234567890";
        var commitHash = "1111222233334444555566667777888899990000";
        var tag = "skara-11+22";
        var diffs = parentDiffs(targetHash + " " + tag);
        var lines = List.of("I want tag " + tag + " for commit " + targetHash);
        var commit = commit(new Hash(commitHash), lines, diffs);
        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof HgTagCommitIssue);
        var issue = (HgTagCommitIssue) issues.get(0);
        assertEquals(HgTagCommitIssue.Error.BAD_FORMAT, issue.error());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void multiplePatchesShouldFail() {
        var targetHash = "12345789012345789012345678901234567890";
        var tag = "skara-11+22";

        var hunk1 = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of(targetHash + " " + tag));
        var patch1 = new TextualPatch(Path.of(".hgtags"), FileType.fromOctal("100644"), Hash.zero(),
                               Path.of(".hgtags"), FileType.fromOctal("100644"), Hash.zero(),
                               Status.from('M'), List.of(hunk1));
        var hunk2 = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of("An additional line"));
        var patch2 = new TextualPatch(Path.of("README"), FileType.fromOctal("100644"), Hash.zero(),
                                      Path.of("README"), FileType.fromOctal("100644"), Hash.zero(),
                                      Status.from('M'), List.of(hunk2));
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch1, patch2));
        var diffs = List.of(diff);

        var commitHash = "1111222233334444555566667777888899990000";
        var lines = List.of("Added tag " + tag + " for changeset " + targetHash);
        var commit = commit(new Hash(commitHash), lines, diffs);

        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof HgTagCommitIssue);
        var issue = (HgTagCommitIssue) issues.get(0);
            assertEquals(HgTagCommitIssue.Error.TOO_MANY_CHANGES, issue.error());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void multipleHunksShouldFail() {
        var targetHash = "12345789012345789012345678901234567890";
        var tag = "skara-11+22";

        var hunk1 = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of(targetHash + " " + tag));
        var hunk2 = new Hunk(new Range(1, 0), List.of(),
                            new Range(2, 1), List.of(targetHash + " " + "skara-11+23"));
        var patch = new TextualPatch(Path.of(".hgtags"), FileType.fromOctal("100644"), Hash.zero(),
                                     Path.of(".hgtags"), FileType.fromOctal("100644"), Hash.zero(),
                                     Status.from('M'), List.of(hunk1, hunk2));
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch));
        var diffs = List.of(diff);

        var commitHash = "1111222233334444555566667777888899990000";
        var lines = List.of("Added tag " + tag + " for changeset " + targetHash);
        var commit = commit(new Hash(commitHash), lines, diffs);

        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof HgTagCommitIssue);
        var issue = (HgTagCommitIssue) issues.get(0);
            assertEquals(HgTagCommitIssue.Error.TOO_MANY_CHANGES, issue.error());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void differentTagInMessageAndHunkShouldFail() {
        var targetHash = "12345789012345789012345678901234567890";
        var commitHash = "1111222233334444555566667777888899990000";
        var tag = "skara-11+22";
        var diffs = parentDiffs(targetHash + " " + tag);
        var lines = List.of("Added tag skara-11+23 for changeset " + targetHash);
        var commit = commit(new Hash(commitHash), lines, diffs);
        var check = new HgTagCommitCheck(new Utilities());
        var issues = toList(check.check(commit, message(commit), conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof HgTagCommitIssue);
        var issue = (HgTagCommitIssue) issues.get(0);
            assertEquals(HgTagCommitIssue.Error.TAG_DIFFERS, issue.error());
        assertEquals(commit, issue.commit());
        assertEquals(check, issue.check());
        assertEquals(Severity.ERROR, issue.severity());
    }
}
