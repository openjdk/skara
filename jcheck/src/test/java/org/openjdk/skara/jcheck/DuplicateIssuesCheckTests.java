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
import org.openjdk.skara.test.TemporaryDirectory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.time.ZonedDateTime;
import java.io.IOException;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;

class DuplicateIssuesCheckTests {
    private static JCheckConfiguration conf() {
        return JCheckConfiguration.parse(List.of(
            "[general]",
            "project = test",
            "[checks]",
            "error = duplicate-issues"
        ));
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    private static List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    @Test
    void noDuplicatedIssuesShouldPass() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("1: Added README and .jcheck/conf", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("2: Modified README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line again"), WRITE, APPEND);
            r.add(readme);
            var third = r.commit("3: Modified README again", "duke", "duke@openjdk.java.net");
            var check = new DuplicateIssuesCheck(r);

            var commit = r.lookup(third).orElseThrow();
            var issues = toList(check.check(commit, message(commit), conf()));
            assertEquals(List.of(), issues);
        }
    }

    @Test
    void duplicateIssuesInMessageShouldFail() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("1: Added README and .jcheck/conf", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("2: Modified README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line again"), WRITE, APPEND);
            r.add(readme);
            var third = r.commit("3: Modified README again\n3: Modified README again", "duke", "duke@openjdk.java.net");

            var check = new DuplicateIssuesCheck(r);

            var commit = r.lookup(third).orElseThrow();
            var issues = toList(check.check(commit, message(commit), conf()));
            assertEquals(2, issues.size());
            assertTrue(issues.get(0) instanceof DuplicateIssuesIssue);
            var issue = (DuplicateIssuesIssue) issues.get(0);
            assertEquals("3", issue.issue().id());
            assertEquals(List.of(third), issue.hashes());
        }
    }

    @Test
    void duplicateIssuesInPreviousCommitsShouldFail() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("1: Added README and .jcheck/conf", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("2: Modified README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line again"), WRITE, APPEND);
            r.add(readme);
            var third = r.commit("2: Modified README again", "duke", "duke@openjdk.java.net");

            var check = new DuplicateIssuesCheck(r);
            var commit = r.lookup(third).orElseThrow();
            var issues = toList(check.check(commit, message(commit), conf()));
            assertEquals(1, issues.size());
            assertTrue(issues.get(0) instanceof DuplicateIssuesIssue);
            var issue = (DuplicateIssuesIssue) issues.get(0);
            assertEquals("2", issue.issue().id());
            assertEquals(2, issue.hashes().size());
            assertTrue(issue.hashes().contains(second));
            assertTrue(issue.hashes().contains(third));
        }
    }
}
