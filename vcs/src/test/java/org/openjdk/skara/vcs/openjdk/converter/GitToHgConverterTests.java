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
package org.openjdk.skara.vcs.openjdk.converter;

import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.GitToHgConverter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GitToHgConverterTests {
    void assertCommitEquals(Commit gitCommit, Commit hgCommit) {
        assertEquals(gitCommit.authored(), hgCommit.authored());
        assertEquals(gitCommit.isInitialCommit(), hgCommit.isInitialCommit());
        assertEquals(gitCommit.isMerge(), hgCommit.isMerge());
        assertEquals(gitCommit.numParents(), hgCommit.numParents());

        var hgDiffs = hgCommit.parentDiffs();
        var gitDiffs = gitCommit.parentDiffs();
        assertEquals(gitDiffs.size(), hgDiffs.size());

        for (var i = 0; i < gitDiffs.size(); i++) {
            var hgDiff = hgDiffs.get(i);
            var gitDiff = gitDiffs.get(i);

            var hgPatches = hgDiff.patches();
            var gitPatches = gitDiff.patches();

            assertEquals(gitPatches.size(), hgPatches.size());

            for (var j = 0; j < gitPatches.size(); j++) {
                var hgPatch = hgPatches.get(j);
                var gitPatch = gitPatches.get(j);

                assertEquals(gitPatch.source().path(), hgPatch.source().path());
                assertEquals(gitPatch.source().type(), hgPatch.source().type());

                assertEquals(gitPatch.target().path(), hgPatch.target().path());
                assertEquals(gitPatch.target().type(), hgPatch.target().type());

                assertEquals(gitPatch.status(), hgPatch.status());
                assertEquals(gitPatch.isBinary(), hgPatch.isBinary());
                assertEquals(gitPatch.isTextual(), hgPatch.isTextual());

                if (gitPatch.isTextual()) {
                    var hgHunks = hgPatch.asTextualPatch().hunks();
                    var gitHunks = gitPatch.asTextualPatch().hunks();
                    assertEquals(gitHunks.size(), hgHunks.size());

                    for (var k = 0; k < gitHunks.size(); k++) {
                        var hgHunk = hgHunks.get(k);
                        var gitHunk = gitHunks.get(k);

                        assertEquals(gitHunk.source().range(), hgHunk.source().range());
                        assertEquals(gitHunk.source().lines(), hgHunk.source().lines());

                        assertEquals(gitHunk.target().range(), hgHunk.target().range());
                        assertEquals(gitHunk.target().lines(), hgHunk.target().lines());

                        assertEquals(gitHunk.added(), hgHunk.added());
                        assertEquals(gitHunk.removed(), hgHunk.removed());
                        assertEquals(gitHunk.modified(), hgHunk.modified());
                    }
                }
            }
        }
    }

    void assertReposEquals(ReadOnlyRepository gitRepo, ReadOnlyRepository hgRepo) throws IOException {
        assertEquals(gitRepo.branches().size(), hgRepo.branches().size());
        assertEquals(gitRepo.tags().size() + 1, hgRepo.tags().size()); // hg alwayas has 'tip' tag

        var gitCommits = gitRepo.commits().asList();
        var hgCommits = hgRepo.commits().asList();
        assertEquals(gitCommits.size(), hgCommits.size());

        for (var i = 0; i < gitCommits.size(); i++) {
            assertCommitEquals(gitCommits.get(i), hgCommits.get(i));
        }
    }

    @Test
    void convertOneCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            gitRepo.add(readme);
            gitRepo.commit("1234567: Added README", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);

            var gitCommits = gitRepo.commits().asList();
            assertEquals(1, gitCommits.size());
            var gitCommit = gitCommits.get(0);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(hgCommit.author(), new Author("foo", null));
            assertEquals(hgCommit.message(), gitCommit.message());
            assertTrue(hgCommit.isInitialCommit());

            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertOneSponsoredCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            gitRepo.add(readme);
            gitRepo.commit("1234567: Added README", "Foo Bar", "foo@host.com",
                                                    "Baz Bar", "baz@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("baz", null), hgCommit.author());
            assertEquals(List.of("1234567: Added README", "Contributed-by: Foo Bar <foo@host.com>"),
                         hgCommit.message());
            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertRepoWithCopy() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            gitRepo.add(readme);
            gitRepo.commit("Added README", "Foo Bar", "foo@openjdk.java.net");

            var readme2 = gitRoot.path().resolve("README2.md");
            gitRepo.copy(readme, readme2);
            gitRepo.commit("Copied README", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(2, hgCommits.size());

            var hgCopyCommit = hgCommits.get(0);
            assertEquals(List.of("Copied README"), hgCopyCommit.message());
            assertFalse(hgCopyCommit.isMerge());
            var hgCopyDiff = hgCopyCommit.parentDiffs().get(0);
            assertEquals(1, hgCopyDiff.patches().size());
            var hgCopyPatch = hgCopyDiff.patches().get(0);
            assertTrue(hgCopyPatch.status().isCopied());
            assertTrue(hgCopyPatch.isEmpty());

            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertRepoWithMove() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            gitRepo.add(readme);
            gitRepo.commit("Added README", "Foo Bar", "foo@openjdk.java.net");

            var readme2 = gitRoot.path().resolve("README2.md");
            gitRepo.move(readme, readme2);
            gitRepo.commit("Moved README", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(2, hgCommits.size());

            var hgMoveCommit = hgCommits.get(0);
            assertEquals(List.of("Moved README"), hgMoveCommit.message());
            assertFalse(hgMoveCommit.isMerge());
            var hgMoveDiff = hgMoveCommit.parentDiffs().get(0);
            assertEquals(1, hgMoveDiff.patches().size());
            var hgMovePatch = hgMoveDiff.patches().get(0);
            assertTrue(hgMovePatch.status().isRenamed());
            assertTrue(hgMovePatch.isEmpty());

            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertOneCoAuthoredCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            gitRepo.add(readme);
            var message = List.of("1234567: Added README", "", "Co-authored-by: Baz Bar <baz@openjdk.java.net>");
            gitRepo.commit(String.join("\n", message), "Foo Bar", "foo@host.com",
                                                       "Baz Bar", "baz@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("baz", null), hgCommit.author());
            assertEquals(List.of("1234567: Added README", "Contributed-by: Foo Bar <foo@host.com>, Baz Bar <baz@openjdk.java.net>"),
                         hgCommit.message());
            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertCommitWithSummary() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            gitRepo.add(readme);
            var message = List.of("1234567: Added README",
                                  "",
                                  "Additional text",
                                  "",
                                  "Co-authored-by: Baz Bar <baz@openjdk.java.net>");
            gitRepo.commit(String.join("\n", message), "Foo Bar", "foo@host.com",
                                                       "Baz Bar", "baz@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("baz", null), hgCommit.author());
            assertEquals(List.of("1234567: Added README",
                                 "Summary: Additional text",
                                 "Contributed-by: Foo Bar <foo@host.com>, Baz Bar <baz@openjdk.java.net>"),
                         hgCommit.message());
            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertMergeCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "First line");
            gitRepo.add(readme);
            gitRepo.commit("First line", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Second line", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var second = gitRepo.commit("Second line", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Third line", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var third = gitRepo.commit("Third line", "Foo Bar", "foo@openjdk.java.net");

            gitRepo.checkout(second, false);

            var contributing = gitRoot.path().resolve("CONTRIBUTING.md");
            Files.writeString(contributing, "Contribute");
            gitRepo.add(contributing);
            var toMerge = gitRepo.commit("Contributing", "Foo Bar", "foo@openjdk.java.net");

            gitRepo.checkout(third, false);
            gitRepo.merge(toMerge);
            gitRepo.commit("Merge", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);
            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertMergeCommitWithP0Diff() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "First line\n");
            gitRepo.add(readme);
            gitRepo.commit("First line", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Second line", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var second = gitRepo.commit("Second line\n", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Third line\n", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var third = gitRepo.commit("Third line", "Foo Bar", "foo@openjdk.java.net");

            gitRepo.checkout(second, false);

            var contributing = gitRoot.path().resolve("CONTRIBUTING.md");
            Files.writeString(contributing, "Contribute\n");
            gitRepo.add(contributing);
            var toMerge = gitRepo.commit("Contributing", "Foo Bar", "foo@openjdk.java.net");

            gitRepo.checkout(third, false);
            gitRepo.merge(toMerge);
            Files.writeString(readme, "Fourth line\n", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            gitRepo.commit("Merge", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);
            assertReposEquals(gitRepo, hgRepo);
        }
    }

    @Test
    void convertMergeCommitWithP1Diff() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "First line\n");
            gitRepo.add(readme);
            gitRepo.commit("First line", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Second line\n", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var second = gitRepo.commit("Second line", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Third line\n", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var third = gitRepo.commit("Third line", "Foo Bar", "foo@openjdk.java.net");

            gitRepo.checkout(second, false);

            var contributing = gitRoot.path().resolve("CONTRIBUTING.md");
            Files.writeString(contributing, "Contribute\n");
            gitRepo.add(contributing);
            var toMerge = gitRepo.commit("Contributing", "Foo Bar", "foo@openjdk.java.net");

            gitRepo.checkout(third, false);
            gitRepo.merge(toMerge);
            Files.writeString(contributing, "More contributions\n", StandardOpenOption.APPEND);
            gitRepo.add(contributing);
            gitRepo.commit("Merge", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            converter.convert(gitRepo, hgRepo);
            assertReposEquals(gitRepo, hgRepo);
        }
    }
}
