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

import org.junit.jupiter.api.Test;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemListsCheckTests {

    // Default dirs and pattern
    private static final List<String> CONFIGURATION = List.of(
            "[general]",
            "project = test",
            "[checks]",
            "error = problemlists"
    );

    // Default dirs and custom pattern
    private static final List<String> CONFIGURATION2 = List.of(
            "[general]",
            "project = test",
            "[checks]",
            "error = problemlists",
            "[checks \"problemlists\"]",
            "pattern = ^ProjProblemList.txt$"
    );

    // custom dirs and default pattern
    private static final List<String> CONFIGURATION3 = List.of(
            "[general]",
            "project = test",
            "[checks]",
            "error = problemlists",
            "[checks \"problemlists\"]",
            "dirs = test1|test2"
    );

    // custom dirs and custom pattern
    private static final List<String> CONFIGURATION4 = List.of(
            "[general]",
            "project = test",
            "[checks]",
            "error = problemlists",
            "[checks \"problemlists\"]",
            "dirs = test1|test2",
            "pattern = ^ProjProblemList.txt$"
    );

    private static final JCheckConfiguration conf = JCheckConfiguration.parse(CONFIGURATION);
    private static final JCheckConfiguration conf2 = JCheckConfiguration.parse(CONFIGURATION2);
    private static final JCheckConfiguration conf3 = JCheckConfiguration.parse(CONFIGURATION3);
    private static final JCheckConfiguration conf4 = JCheckConfiguration.parse(CONFIGURATION4);

    private static final ReadOnlyRepository REPOSITORY = new TestRepository() {
        @Override
        // always has test*/ProblemList.txt and test*/ProjProblemList.txt
        // for h == 1XXXX has test*/ProblemList1.txt
        // for h == 2XXXX has test*/ProblemList2.txt
        public List<FileEntry> files(Hash h, List<Path> paths) throws IOException {
            List<FileEntry> result = new ArrayList<>();
            for (var path : paths) {
                if (path.equals(Path.of("test"))) {
                    result.addAll(filesAt("test", h));
                } else if (path.equals(Path.of("test1"))) {
                    result.addAll(filesAt("test1", h));
                } else if (path.equals(Path.of("test2"))) {
                    result.addAll(filesAt("test2", h));
                } else {
                    result.addAll(super.files(h, paths));
                }
            }
            return result;
        }

        private List<? extends FileEntry> filesAt(String dir, Hash h) {
            var fileType = FileType.fromOctal("100644");
            switch (h.hex().charAt(0)) {
                case '1':
                    return List.of(new FileEntry(h, fileType, h, Path.of(dir + "/ProblemList.txt")),
                            new FileEntry(h, fileType, h, Path.of(dir + "/ProblemList1.txt")),
                            new FileEntry(h, fileType, h, Path.of(dir + "/ProjProblemList.txt")));
                case '2':
                    return List.of(new FileEntry(h, fileType, h, Path.of(dir + "/ProblemList.txt")),
                            new FileEntry(h, fileType, h, Path.of(dir + "/ProblemList2.txt")),
                            new FileEntry(h, fileType, h, Path.of(dir + "/ProjProblemList.txt")));
                default:
                    return List.of(new FileEntry(h, fileType, h, Path.of(dir + "/ProblemList.txt")),
                            new FileEntry(h, fileType, h, Path.of(dir + "/ProjProblemList.txt")));
            }
        }

        @Override
        // ProblemList*.txt always contain tests problem listed because of bugs 2 and 3 and unless h[0] == 1 b/c of 1
        // ProjProblemList.txt always contain tests problem listed because of PROJ-2,PROJ-3 and PROJ1-1
        // and unless h[0] == 1 b/c of PROJ-1
        public Optional<List<String>> lines(Path p, Hash h) throws IOException {
            if (p.getParent().toString().startsWith("test")) {
                List<String> result;
                var filename = p.getFileName().toString();
                if (filename.startsWith("ProblemList") && filename.endsWith(".txt")) {
                    if (h.hex().charAt(0) == '1') {
                        result = List.of("test1 2", "test3 2,3", "# test 1,2,3");
                    } else {
                        result = List.of("test1 1", "test1 2", "test3 2,3", "# test 1,2,3");
                    }
                } else if (filename.equals("ProjProblemList.txt")) {
                    if (h.hex().charAt(0) == '1') {
                        result = List.of("test1 PROJ-2", "test3 PROJ-2,PROJ-3,PROJ1-1", "# test PROJ-1,PROJ-2,PROJ-3");
                    } else {
                        result = List.of("test1 PROJ-1", "test1 PROJ-2", "test3 PROJ-2,PROJ-3,PROJ1-1", "# test PROJ-1,PROJ-2,PROJ-3");
                    }
                } else {
                    return super.lines(p, h);
                }
                return Optional.of(result);
            }
            return super.lines(p, h);
        }
    };

    private static Commit commit(int id, String... message) {
        var author = new Author("foo", "foo@host.org");
        var hash = new Hash(("" + id).repeat(40));
        var parents = List.of(Hash.zero());
        var authored = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, List.of(message));
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
    void titleOnlyMessageShouldBypass() {
        var commit = commit(0, "Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(0, issues.size());
    }

    @Test
    void singleNeverBeenProblemListed() {
        var commit = commit(0, "4: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(0, issues.size());
    }

    @Test
    void singlePrefixedNeverBeenProblemListed() {
        var commit = commit(0, "PROJ-1: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(0, issues.size());
    }

    @Test
    void multipleHaveNeverBeenProblemListed() {
        var commit = commit(0, "4: Bugfix", "5: Bugfix2");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(0, issues.size());
    }

    @Test
    void singleAlwaysProblemListed() {
        var commit = commit(0, "3: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof ProblemListsIssue);
        var issue = (ProblemListsIssue) issues.get(0);
        assertEquals("3", issue.issue());
        assertEquals(Set.of(Path.of("test/ProblemList.txt")), issue.files());
    }

    @Test
    void singleUnproblemListed() {
        var commit = commit(1, "1: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(0, issues.size());
    }

    @Test
    void singleAlwaysProblemListedInTwoLists() {
        var check = new ProblemListsCheck(REPOSITORY);

        {
            var commit = commit(1, "2: Bugfix");
            var message = message(commit);
            var issues = toList(check.check(commit, message, conf));

            assertEquals(1, issues.size());
            assertTrue(issues.get(0) instanceof ProblemListsIssue);
            var issue = (ProblemListsIssue) issues.get(0);
            assertEquals("2", issue.issue());
            assertEquals(Set.of(Path.of("test/ProblemList.txt"),
                    Path.of("test/ProblemList1.txt")), issue.files());
        }

        {
            var commit = commit(2, "2: Bugfix");
            var message = message(commit);
            var issues = toList(check.check(commit, message, conf));

            assertEquals(1, issues.size());
            assertTrue(issues.get(0) instanceof ProblemListsIssue);
            var issue = (ProblemListsIssue) issues.get(0);
            assertEquals("2", issue.issue());
            assertEquals(Set.of(Path.of("test/ProblemList.txt"),
                    Path.of("test/ProblemList2.txt")), issue.files());
        }
    }

    @Test
    void multipleAlwaysProblemListed() {
        var commit = commit(0, "2: Bugfix", "3: Bugfix2");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(2, issues.size());
        // assume that issues are in the same order as messages
        assertTrue(issues.get(0) instanceof ProblemListsIssue);
        var issue = (ProblemListsIssue) issues.get(0);
        assertEquals("2", issue.issue());
        assertEquals(Set.of(Path.of("test/ProblemList.txt")), issue.files());

        issue = (ProblemListsIssue) issues.get(1);
        assertEquals("3", issue.issue());
        assertEquals(Set.of(Path.of("test/ProblemList.txt")), issue.files());
    }

    @Test
    void multipleYetOnlyOneProblemListed() {
        var commit = commit(0, "4: Bugfix", "3: Bugfix2");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf));

        assertEquals(1, issues.size());
        // assume that issues are in the same order as messages
        assertTrue(issues.get(0) instanceof ProblemListsIssue);
        var issue = (ProblemListsIssue) issues.get(0);
        assertEquals("3", issue.issue());
        assertEquals(Set.of(Path.of("test/ProblemList.txt")), issue.files());
    }

    @Test
    void singlePrefixedNeverBeenProblemListedConf2() {
        var commit = commit(0, "PROJ-4: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf2));

        assertEquals(0, issues.size());
    }

    @Test
    void singleNeverBeenProblemListedConf2() {
        var commit = commit(0, "1: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf2));

        assertEquals(0, issues.size());
    }

    @Test
    void singlePrefixedAlwaysProblemListedConf2() {
        var commit = commit(0, "PROJ-3: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf2));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof ProblemListsIssue);
        var issue = (ProblemListsIssue) issues.get(0);
        assertEquals("PROJ-3", issue.issue());
        assertEquals(Set.of(Path.of("test/ProjProblemList.txt")), issue.files());
    }

    @Test
    void singlePrefixedUnproblemListedConf2() {
        var commit = commit(1, "PROJ-1: Bugfix");
        var message = message(commit);
        var check = new ProblemListsCheck(REPOSITORY);
        var issues = toList(check.check(commit, message, conf2));

        assertEquals(0, issues.size());
    }

    @Test
    void singleAlwaysProblemListedInTwoListsConf3() {
        var check = new ProblemListsCheck(REPOSITORY);

        {
            var commit = commit(1, "2: Bugfix");
            var message = message(commit);
            var issues = toList(check.check(commit, message, conf3));

            assertEquals(1, issues.size());
            assertTrue(issues.get(0) instanceof ProblemListsIssue);
            var issue = (ProblemListsIssue) issues.get(0);
            assertEquals("2", issue.issue());
            assertEquals(Set.of(
                    Path.of("test1/ProblemList.txt"),
                    Path.of("test1/ProblemList1.txt"),
                    Path.of("test2/ProblemList.txt"),
                    Path.of("test2/ProblemList1.txt")), issue.files());
        }

        {
            var commit = commit(2, "2: Bugfix");
            var message = message(commit);
            var issues = toList(check.check(commit, message, conf3));

            assertEquals(1, issues.size());
            assertTrue(issues.get(0) instanceof ProblemListsIssue);
            var issue = (ProblemListsIssue) issues.get(0);
            assertEquals("2", issue.issue());
            assertEquals(Set.of(
                    Path.of("test1/ProblemList.txt"),
                    Path.of("test1/ProblemList2.txt"),
                    Path.of("test2/ProblemList.txt"),
                    Path.of("test2/ProblemList2.txt")), issue.files());
        }
    }

    @Test
    void singlePrefixedAlwaysProblemListedConf4() {
        var check = new ProblemListsCheck(REPOSITORY);

        var commit = commit(0, "PROJ-2: Bugfix");
        var message = message(commit);
        var issues = toList(check.check(commit, message, conf4));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof ProblemListsIssue);
        var issue = (ProblemListsIssue) issues.get(0);
        assertEquals("PROJ-2", issue.issue());
        assertEquals(Set.of(Path.of("test1/ProjProblemList.txt"),
                Path.of("test2/ProjProblemList.txt")), issue.files());

    }

}
