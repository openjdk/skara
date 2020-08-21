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
import org.openjdk.skara.vcs.openjdk.convert.Mark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.net.URI;
import java.util.stream.Collectors;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class GitToHgConverterTests {
    void assertCommitEquals(ReadOnlyRepository gitRepo, Commit gitCommit, ReadOnlyRepository hgRepo, Commit hgCommit) throws IOException {
        System.out.println("git commit: " + gitCommit.hash() + ", hg commit: " + hgCommit.hash());
        assertEquals(gitCommit.authored(), hgCommit.authored());
        assertEquals(gitCommit.isInitialCommit(), hgCommit.isInitialCommit());
        assertEquals(gitCommit.isMerge(), hgCommit.isMerge());
        assertEquals(gitCommit.numParents(), hgCommit.numParents());

        var gitFiles = gitRepo.files(gitCommit.hash());
        var gitFileToHash = new HashMap<Path, Hash>();
        for (var entry : gitFiles) {
            gitFileToHash.put(entry.path(), entry.hash());
        }

        var hgFiles = hgRepo.files(hgCommit.hash());
        var hgFileToHash = new HashMap<Path, Hash>();
        for (var entry : hgFiles) {
            hgFileToHash.put(entry.path(), entry.hash());
        }

        var hgtags = Path.of(".hgtags");
        assertEquals(gitFiles.size(), hgFiles.size());
        for (var entry : gitFiles) {
            var path = entry.path();
            if (path.equals(hgtags)) {
                continue;
            }
            var gitHash = gitFileToHash.get(path);
            var hgHash = hgFileToHash.get(path);
            assertEquals(gitHash, hgHash, "filename: " + path);
        }
    }

    void assertReposEquals(List<Mark> marks, ReadOnlyRepository gitRepo, ReadOnlyRepository hgRepo) throws IOException {
        var gitTagNames = gitRepo.tags().stream().map(Tag::name).collect(Collectors.toSet());
        gitTagNames.add("tip"); // hg always has "tip" tag
        var hgTagNames = hgRepo.tags().stream().map(Tag::name).collect(Collectors.toSet());
        assertEquals(gitTagNames, hgTagNames);

        var gitCommits = gitRepo.commits("master").asList();

        var gitHashes = new HashSet<Hash>();
        for (var commit : gitCommits) {
            gitHashes.add(commit.hash());
        }
        for (var mark : marks) {
            gitHashes.remove(mark.git());
        }
        assertEquals(Set.of(), gitHashes);

        var hgCommits = hgRepo.commits().asList();
        assertTrue(hgCommits.size() >= gitCommits.size(), hgCommits.size() + " < " + gitCommits.size());
        assertEquals(gitCommits.size(), marks.size());

        var gitHashToCommit = new HashMap<Hash, Commit>();
        for (var commit : gitCommits) {
            gitHashToCommit.put(commit.hash(), commit);
        }
        var hgHashToCommit = new HashMap<Hash, Commit>();
        for (var commit : hgCommits) {
            hgHashToCommit.put(commit.hash(), commit);
        }
        for (var mark : marks) {
            assertCommitEquals(gitRepo, gitHashToCommit.get(mark.git()), hgRepo, hgHashToCommit.get(mark.hg()));
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
            var marks = converter.convert(gitRepo, hgRepo);

            var gitCommits = gitRepo.commits().asList();
            assertEquals(1, gitCommits.size());
            var gitCommit = gitCommits.get(0);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(hgCommit.author(), new Author("foo", null));
            assertEquals(hgCommit.message(), gitCommit.message());
            assertTrue(hgCommit.isInitialCommit());

            assertReposEquals(marks, gitRepo, hgRepo);
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
            var marks = converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("baz", null), hgCommit.author());
            assertEquals(List.of("1234567: Added README", "Contributed-by: Foo Bar <foo@host.com>"),
                         hgCommit.message());
            assertReposEquals(marks, gitRepo, hgRepo);
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
            var marks = converter.convert(gitRepo, hgRepo);

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

            assertReposEquals(marks, gitRepo, hgRepo);
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
            var marks = converter.convert(gitRepo, hgRepo);

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

            assertReposEquals(marks, gitRepo, hgRepo);
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
            var marks = converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("baz", null), hgCommit.author());
            assertEquals(List.of("1234567: Added README", "Contributed-by: Foo Bar <foo@host.com>, Baz Bar <baz@openjdk.java.net>"),
                         hgCommit.message());
            assertReposEquals(marks, gitRepo, hgRepo);
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
            var marks = converter.convert(gitRepo, hgRepo);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("baz", null), hgCommit.author());
            assertEquals(List.of("1234567: Added README",
                                 "Summary: Additional text",
                                 "Contributed-by: Foo Bar <foo@host.com>, Baz Bar <baz@openjdk.java.net>"),
                         hgCommit.message());
            assertReposEquals(marks, gitRepo, hgRepo);
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

            gitRepo.checkout(gitRepo.defaultBranch(), false);
            gitRepo.merge(toMerge);
            gitRepo.commit("Merge", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            var marks = converter.convert(gitRepo, hgRepo);
            assertReposEquals(marks, gitRepo, hgRepo);
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

            gitRepo.checkout(gitRepo.defaultBranch(), false);
            gitRepo.merge(toMerge);
            Files.writeString(readme, "Fourth line\n", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            gitRepo.commit("Merge", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            var marks = converter.convert(gitRepo, hgRepo);
            assertReposEquals(marks, gitRepo, hgRepo);
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

            gitRepo.checkout(gitRepo.defaultBranch(), false);
            gitRepo.merge(toMerge);
            Files.writeString(contributing, "More contributions\n", StandardOpenOption.APPEND);
            gitRepo.add(contributing);
            gitRepo.commit("Merge", "Foo Bar", "foo@openjdk.java.net");

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            var marks = converter.convert(gitRepo, hgRepo);
            assertReposEquals(marks, gitRepo, hgRepo);
        }
    }

    private void cloneAndConvertAndVerify(String repo) throws IOException {
        try (var hgRoot = new TemporaryDirectory(false);
             var gitRoot = new TemporaryDirectory(false)) {
            var gitRepo = Repository.clone(URI.create("https://git.openjdk.java.net/" + repo + ".git"), gitRoot.path());
            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter(new Branch("master"));
            var marks = converter.convert(gitRepo, hgRepo);
            assertReposEquals(marks, gitRepo, hgRepo);
        }
    }

    @Test
    void convertGitTag() throws IOException {
        try (var hgRoot = new TemporaryDirectory(false);
             var gitRoot = new TemporaryDirectory(false)) {
            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);
            var readme = gitRoot.path().resolve("README.md");

            Files.writeString(readme, "First line\n");
            gitRepo.add(readme);
            gitRepo.commit("First line", "Foo Bar", "foo@openjdk.java.net");

            Files.writeString(readme, "Second line\n", StandardOpenOption.APPEND);
            gitRepo.add(readme);
            var second = gitRepo.commit("Second line", "Foo Bar", "foo@openjdk.java.net");
            var tagDate = ZonedDateTime.parse("2020-08-24T11:30:32+02:00");
            var tag = gitRepo.tag(second, "1.0", "Added tag 1.0", "Foo Bar", "foo@openjdk.java.net", tagDate);

            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var converter = new GitToHgConverter();
            var marks = converter.convert(gitRepo, hgRepo);
            var lastMark = marks.get(marks.size() - 1);
            assertEquals(second, lastMark.git());
            assertTrue(lastMark.tag().isPresent());

            Files.writeString(readme, "Third line\n");
            gitRepo.add(readme);
            gitRepo.commit("Third line", "Foo Bar", "foo@openjdk.java.net");

            converter = new GitToHgConverter();
            var newMarks = converter.convert(gitRepo, hgRepo, marks);
            var hgCommits = hgRepo.commitMetadata(true);
            assertEquals(4, hgCommits.size());
            assertEquals(List.of("First line"), hgCommits.get(0).message());
            assertEquals(List.of("Second line"), hgCommits.get(1).message());
            assertEquals(List.of("Added tag 1.0"), hgCommits.get(2).message());
            assertEquals(List.of("Third line"), hgCommits.get(3).message());
            assertEquals(List.of(new Tag("tip"), new Tag("1.0")), hgRepo.tags());

            var annotated = hgRepo.annotate(new Tag("1.0"));
            assertTrue(annotated.isPresent());
            assertEquals("foo", annotated.get().author().name());
            assertEquals(tagDate, annotated.get().date());
            assertEquals("Added tag 1.0", annotated.get().message());
        }
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertDefpath() throws IOException {
        cloneAndConvertAndVerify("defpath");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertTrees() throws IOException {
        cloneAndConvertAndVerify("trees");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertWebrev() throws IOException {
        cloneAndConvertAndVerify("webrev");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertAsmtools() throws IOException {
        cloneAndConvertAndVerify("asmtools");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertJcov() throws IOException {
        cloneAndConvertAndVerify("jcov");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertJtharness() throws IOException {
        cloneAndConvertAndVerify("jtharness");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertJtreg() throws IOException {
        cloneAndConvertAndVerify("jtreg");
    }

    @Disabled("Depends on internet connection")
    @Test
    void convertJmc() throws IOException {
        cloneAndConvertAndVerify("jmc");
    }
}
