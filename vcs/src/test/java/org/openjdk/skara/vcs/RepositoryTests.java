/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs;

import org.junit.jupiter.api.Assumptions;
import org.openjdk.skara.test.TemporaryDirectory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.file.StandardOpenOption.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class RepositoryTests {

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testExistsOnMissingDirectory(VCS vcs) throws IOException {
        var d = Paths.get("/", "this", "path", "does", "not", "exist");
        var r = Repository.get(d);
        assertTrue(r.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testExistsOnEmptyDirectory(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.get(dir.path());
            assertTrue(r.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testExistsOnInitializedRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.exists());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testExistsOnSubdir() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);
            assertTrue(r.exists());

            var subdir = Paths.get(dir.toString(), "test");
            Files.createDirectories(subdir);
            var r2 = Repository.get(subdir);
            assertTrue(r2.get().exists());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRootOnTopLevel() throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);
            assertEquals(dir.toString(), r.root().toString());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRootOnSubdirectory(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertEquals(dir.toString(), r.root().toString());

            var subdir = Paths.get(dir.toString(), "sub");
            Files.createDirectories(subdir);

            var r2 = Repository.get(subdir);
            assertEquals(dir.toString(), r2.get().root().toString());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testResolveOnEmptyRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.resolve("HEAD").isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testResolveWithHEAD(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var head = r.commit("Add README", "duke", "duke@openjdk.java.net");
            assertEquals(head, r.head());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testConfig(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            if (vcs == VCS.GIT) {
                var config = dir.path().resolve(".git").resolve("config");
                Files.write(config, List.of("[user]", "name = duke"), WRITE, APPEND);
                assertEquals(List.of("duke"), r.config("user.name"));
            } else if (vcs == VCS.HG) {
                var config = dir.path().resolve(".hg").resolve("hgrc");
                Files.write(config, List.of("[ui]", "username = duke"), WRITE, CREATE);
                assertEquals(List.of("duke"), r.config("ui.username"));
            }

            assertEquals("duke", r.username().get());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCurrentBranchOnEmptyRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertEquals(r.defaultBranch(), r.currentBranch().get());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCheckout(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));
            r.add(readme);

            var head1 = r.commit("Add README", "duke", "duke@openjdk.java.net");
            assertEquals(head1, r.head());

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);

            var head2 = r.commit("Add one more line", "duke", "duke@openjdk.java.net");
            assertEquals(head2, r.head());

            r.checkout(head1, false);
            assertEquals(head1, r.head());

            r.checkout(head2, false);
            assertEquals(head2, r.head());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testLines(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));
            r.add(readme);

            var head1 = r.commit("Add README", "duke", "duke@openjdk.java.net");
            assertEquals(List.of("Hello, readme!"),
                         r.lines(readme, head1).orElseThrow());

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);

            var head2 = r.commit("Add one more line", "duke", "duke@openjdk.java.net");
            assertEquals(List.of("Hello, readme!", "Another line"),
                         r.lines(readme, head2).orElseThrow());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testLinesInSubdir(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            Repository.init(dir.path(), vcs);

            var subdir = dir.path().resolve("sub");
            Files.createDirectories(subdir);
            var r = Repository.get(subdir).get();

            var readme = subdir.getParent().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));
            r.add(readme);

            var head = r.commit("Add README", "duke", "duke@openjdk.java.net");
            assertEquals(List.of("Hello, readme!"),
                         r.lines(readme, head).orElseThrow());

            var example = subdir.resolve("EXAMPLE");
            Files.write(example, List.of("An example"));
            r.add(example);

            var head2 = r.commit("Add EXAMPLE", "duke", "duke@openjdk.java.net");
            assertEquals(List.of("An example"),
                         r.lines(example, head2).orElseThrow());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCommitListingOnEmptyRepo(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.commits().asList().isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCommitListingWithSingleCommit(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);

            var committerName = vcs == VCS.GIT ? "bot" : "duke";
            var committerEmail = vcs == VCS.GIT ? "bot@openjdk.java.net" : "duke@openjdk.java.net";
            var hash = r.commit("Add README", "duke", "duke@openjdk.java.net", committerName, committerEmail);

            var commits = r.commits().asList();
            assertEquals(1, commits.size());

            var commit = commits.get(0);
            assertEquals("duke", commit.author().name());
            assertEquals("duke@openjdk.java.net", commit.author().email());
            assertEquals(committerName, commit.committer().name());
            assertEquals(committerEmail, commit.committer().email());

            assertEquals(List.of("Add README"), commit.message());

            assertEquals(1, commit.numParents());
            assertEquals(1, commit.parents().size());

            var nullHash = "0".repeat(40);
            var parent = commit.parents().get(0);
            assertEquals(nullHash, parent.hex());

            assertTrue(commit.isInitialCommit());
            assertFalse(commit.isMerge());
            assertEquals(hash, commit.hash());

            var diffs = commit.parentDiffs();
            assertEquals(1, diffs.size());

            var diff = diffs.get(0);
            assertEquals(nullHash, diff.from().hex());
            assertEquals(hash, diff.to());

            assertEquals(0, diff.removed());
            assertEquals(0, diff.modified());
            assertEquals(1, diff.added());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertTrue(patch.status().isAdded());

            assertTrue(patch.source().path().isEmpty());
            assertTrue(patch.source().type().isEmpty());

            assertEquals(Path.of("README"), patch.target().path().get());
            assertTrue(patch.target().type().get().isRegularNonExecutable());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(new Range(0, 0), hunk.source().range());
            assertEquals(new Range(1, 1), hunk.target().range());

            assertLinesEquals(List.of(), hunk.source().lines());
            assertLinesEquals(List.of("Hello, readme!"), hunk.target().lines());
        }
    }

    static String stripTrailingCR(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    static void assertLinesEquals(List<String> expected, List<String> actual) {
        assertEquals(expected, actual.stream().map(RepositoryTests::stripTrailingCR).collect(Collectors.toList()));
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCommitListingWithMultipleCommits(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var hash1 = r.commit("Add README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);
            var hash2 = r.commit("Modify README", "duke", "duke@openjdk.java.net");

            var commits = r.commits().asList();
            assertEquals(2, commits.size());

            var commit = commits.get(0);
            assertEquals("duke", commit.author().name());
            assertEquals("duke@openjdk.java.net", commit.author().email());

            assertEquals(List.of("Modify README"), commit.message());

            assertEquals(1, commit.numParents());
            assertEquals(1, commit.parents().size());

            var parent = commit.parents().get(0);
            assertEquals(hash1, parent);

            assertFalse(commit.isInitialCommit());
            assertFalse(commit.isMerge());
            assertEquals(hash2, commit.hash());

            var diffs = commit.parentDiffs();
            assertEquals(1, diffs.size());

            var diff = diffs.get(0);
            assertEquals(hash1, diff.from());
            assertEquals(hash2, diff.to());

            assertEquals(0, diff.removed());
            assertEquals(0, diff.modified());
            assertEquals(1, diff.added());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertTrue(patch.status().isModified());
            assertEquals(Path.of("README"), patch.source().path().get());
            assertTrue(patch.source().type().get().isRegularNonExecutable());
            assertEquals(Path.of("README"), patch.target().path().get());
            assertTrue(patch.target().type().get().isRegularNonExecutable());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(new Range(2, 0), hunk.source().range());
            assertEquals(new Range(2, 1), hunk.target().range());

            assertLinesEquals(List.of(), hunk.source().lines());
            assertLinesEquals(List.of("Another line"), hunk.target().lines());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testSquashDeletes(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var file1 = dir.path().resolve("file1.txt");
            Files.write(file1, List.of("Hello, file 1!"));
            var file2 = dir.path().resolve("file2.txt");
            Files.write(file2, List.of("Hello, file 2!"));
            var file3 = dir.path().resolve("file3.txt");
            Files.write(file3, List.of("Hello, file 3!"));

            r.add(file1, file2, file3);
            var hash1 = r.commit("Add files", "duke", "duke@openjdk.java.net");

            Files.delete(file2);
            r.remove(file2);
            var hash2 = r.commit("Remove file 2", "duke", "duke@openjdk.java.net");

            Files.delete(file3);
            r.remove(file3);
            var hash3 = r.commit("Remove file 3", "duke", "duke@openjdk.java.net");

            var refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            assertEquals(3, r.commits(refspec).asList().size());

            r.checkout(hash1, false);
            r.squash(hash3);
            r.commit("Squashed remove of file 2 and 3", "duke", "duke@openjdk.java.net");

            refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            var commits = r.commits(refspec).asList();
            assertEquals(2, commits.size());

            assertEquals(hash1, commits.get(1).hash());

            var head = commits.get(0);
            assertNotEquals(hash2, head);
            assertNotEquals(hash3, head);

            assertEquals(hash1, head.parents().get(0));
            assertFalse(head.isInitialCommit());
            assertFalse(head.isMerge());

            var diffs = head.parentDiffs();
            assertEquals(1, diffs.size());

            var diff = diffs.get(0);
            assertEquals(hash1, diff.from());
            assertEquals(head.hash(), diff.to());

            assertEquals(2, diff.removed());
            assertEquals(0, diff.modified());
            assertEquals(0, diff.added());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testSquash(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var hash1 = r.commit("Add README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);
            var hash2 = r.commit("Modify README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("A final line"), WRITE, APPEND);
            r.add(readme);
            var hash3 = r.commit("Modify README again", "duke", "duke@openjdk.java.net");

            var refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            assertEquals(3, r.commits(refspec).asList().size());

            r.checkout(hash1, false);
            r.squash(hash3);
            r.commit("Squashed commits 2 and 3", "duke", "duke@openjdk.java.net");

            refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            var commits = r.commits(refspec).asList();
            assertEquals(2, commits.size());

            assertEquals(hash1, commits.get(1).hash());

            var head = commits.get(0);
            assertNotEquals(hash2, head);
            assertNotEquals(hash3, head);

            assertEquals(hash1, head.parents().get(0));
            assertFalse(head.isInitialCommit());
            assertFalse(head.isMerge());

            var diffs = head.parentDiffs();
            assertEquals(1, diffs.size());

            var diff = diffs.get(0);
            assertEquals(hash1, diff.from());
            assertEquals(head.hash(), diff.to());

            assertEquals(0, diff.removed());
            assertEquals(0, diff.modified());
            assertEquals(2, diff.added());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertTrue(patch.status().isModified());
            assertEquals(Path.of("README"), patch.source().path().get());
            assertTrue(patch.source().type().get().isRegularNonExecutable());
            assertEquals(Path.of("README"), patch.target().path().get());
            assertTrue(patch.target().type().get().isRegularNonExecutable());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(new Range(2, 0), hunk.source().range());
            assertEquals(new Range(2, 2), hunk.target().range());

            assertLinesEquals(List.of(), hunk.source().lines());
            assertLinesEquals(List.of("Another line", "A final line"), hunk.target().lines());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testMergeBase(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var hash1 = r.commit("Add README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);
            var hash2 = r.commit("Modify README", "duke", "duke@openjdk.java.net");

            r.checkout(hash1, false);
            Files.write(readme, List.of("A conflicting line"), WRITE, APPEND);
            r.add(readme);
            var hash3 = r.commit("Branching README modification", "duke", "duke@openjdk.java.net");

            assertEquals(hash1, r.mergeBase(hash2, hash3));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRebase(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var hash1 = r.commit("Add README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);
            var hash2 = r.commit("Modify README", "duke", "duke@openjdk.java.net");

            r.checkout(hash1, false);

            var contributing = dir.path().resolve("CONTRIBUTING");
            Files.write(contributing, List.of("Keep the patches coming"));
            r.add(contributing);
            var hash3 = r.commit("Add independent change", "duke", "duke@openjdk.java.net");

            var committerName = vcs == VCS.GIT ? "bot" : "duke";
            var committerEmail = vcs == VCS.GIT ? "bot@openjdk.java.net" : "duke@openjdk.java.net";
            r.rebase(hash2, committerName, committerEmail);

            var refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            var commits = r.commits(refspec).asList();
            assertEquals(3, commits.size());
            assertEquals(hash2, commits.get(1).hash());
            assertEquals(hash1, commits.get(2).hash());

            assertEquals("duke", commits.get(0).author().name());
            assertEquals("duke@openjdk.java.net", commits.get(0).author().email());
            assertEquals(committerName, commits.get(0).committer().name());
            assertEquals(committerEmail, commits.get(0).committer().email());

            assertEquals("duke", commits.get(1).author().name());
            assertEquals("duke@openjdk.java.net", commits.get(1).author().email());
            assertEquals("duke", commits.get(1).committer().name());
            assertEquals("duke@openjdk.java.net", commits.get(1).committer().email());

            assertEquals("duke", commits.get(2).author().name());
            assertEquals("duke@openjdk.java.net", commits.get(2).author().email());
            assertEquals("duke", commits.get(2).committer().name());
            assertEquals("duke@openjdk.java.net", commits.get(2).committer().email());

            var head = commits.get(0);
            assertEquals(hash2, head.parents().get(0));
            assertEquals(List.of("Add independent change"), head.message());

            var diffs = head.parentDiffs();
            assertEquals(1, diffs.size());
            var diff = diffs.get(0);

            assertEquals(0, diff.removed());
            assertEquals(0, diff.modified());
            assertEquals(1, diff.added());

            var patches = diff.patches();
            assertEquals(1, patches.size());
            var patch = patches.get(0).asTextualPatch();
            assertEquals(Path.of("CONTRIBUTING"), patch.target().path().get());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());
            var hunk = hunks.get(0);
            assertLinesEquals(List.of("Keep the patches coming"), hunk.target().lines());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testInitializedRepositoryIsEmpty(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRepositoryWithCommitIsNonEmpty(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            r.commit("Add README", "duke", "duke@openjdk.java.net");

            assertFalse(r.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testEmptyRepositoryIsHealthy(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isHealthy());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testNonEmptyRepositoryIsHealthy(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            r.commit("Add README", "duke", "duke@openjdk.java.net");

            assertTrue(r.isHealthy());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testNonCheckedOutRepositoryIsHealthy(VCS vcs) throws IOException {
        try (var dir1 = new TemporaryDirectory();
             var dir2 = new TemporaryDirectory()) {
            var r1 = Repository.init(dir1.path(), vcs);

            var readme = dir1.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r1.add(readme);
            var hash = r1.commit("Add README", "duke", "duke@openjdk.java.net");
            r1.tag(hash, "tag", "tagging", "duke", "duke@openjdk.java.net");

            var r2 = Repository.init(dir2.path(), vcs);
            r2.fetch(r1.root().toUri(), r1.defaultBranch().name());

            assertTrue(r2.isHealthy());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testBranchesOnEmptyRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            var expected = vcs == VCS.GIT ? List.of() : List.of(new Branch("default"));
            assertEquals(List.of(), r.branches());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testBranchesOnNonEmptyRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            r.commit("Add README", "duke", "duke@openjdk.java.net");

            assertEquals(List.of(r.defaultBranch()), r.branches());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testTagsOnEmptyRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            var expected = vcs == VCS.GIT ? List.of() : List.of(new Tag("tip"));
            assertEquals(expected, r.tags());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testTagsOnNonEmptyRepository(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            r.commit("Add README", "duke", "duke@openjdk.java.net");

            var expected = vcs == VCS.GIT ? List.of() : List.of(new Tag("tip"));
            assertEquals(expected, r.tags());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testFetchAndPush(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var upstream = Repository.init(dir.path(), vcs);

            if (vcs == VCS.GIT) {
                Files.write(upstream.root().resolve(".git").resolve("config"),
                            List.of("[receive]", "denyCurrentBranch=ignore"),
                            WRITE, APPEND);
            }

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            upstream.add(readme);
            upstream.commit("Add README", "duke", "duke@openjdk.java.net");

            try (var dir2 = new TemporaryDirectory()) {
                var downstream = Repository.init(dir2.path(), vcs);

                 // note: forcing unix path separators for URI
                var upstreamURI = URI.create("file:///" + dir.toString().replace('\\', '/'));

                var fetchHead = downstream.fetch(upstreamURI, downstream.defaultBranch().name());
                downstream.checkout(fetchHead, false);

                var downstreamReadme = dir2.path().resolve("README");
                Files.write(downstreamReadme, List.of("Downstream change"), WRITE, APPEND);

                downstream.add(downstreamReadme);
                var head = downstream.commit("Modify README", "duke", "duke@openjdk.java.net");

                downstream.push(head, upstreamURI, downstream.defaultBranch().name());
            }

            upstream.checkout(upstream.resolve(upstream.defaultBranch().name()).get(), false);

            var commits = upstream.commits().asList();
            assertEquals(2, commits.size());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testClean(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            r.clean();

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var hash1 = r.commit("Add README", "duke", "duke@openjdk.java.net");

            r.clean();

            assertEquals(hash1, r.head());

            Files.write(readme, List.of("A random change"), WRITE, APPEND);

            r.clean();

            assertEquals(List.of("Hello, readme!"), Files.readAllLines(readme));

            var untracked = dir.path().resolve("UNTRACKED");
            Files.write(untracked, List.of("Random text"));

            r.clean();

            assertFalse(Files.exists(untracked));

            // Mercurial cannot currently deal with this situation
            if (vcs != VCS.HG) {
                var subRepo = Repository.init(dir.path().resolve("submodule"), vcs);
                var subRepoFile = subRepo.root().resolve("file.txt");
                Files.write(subRepoFile, List.of("Looks like a file in a submodule"));

                r.clean();

                assertFalse(Files.exists(subRepoFile));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCleanIgnored(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            r.clean();

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));
            Files.write(dir.path().resolve(".gitignore"), List.of("*.txt"));
            Files.write(dir.path().resolve(".hgignore"), List.of(".*txt"));

            r.add(readme);
            var hash1 = r.commit("Add README", "duke", "duke@openjdk.java.net");

            var ignored = dir.path().resolve("ignored.txt");
            Files.write(ignored, List.of("Random text"));

            r.clean();

            assertFalse(Files.exists(ignored));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffBetweenCommits(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            r.add(readme);
            var first = r.commit("Add README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("Add one more line", "duke", "duke@openjdk.java.net");

            var diff = r.diff(first, second);
            assertEquals(first, diff.from());
            assertEquals(second, diff.to());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertEquals(Path.of("README"), patch.source().path().get());
            assertEquals(Path.of("README"), patch.target().path().get());
            assertTrue(patch.source().type().get().isRegularNonExecutable());
            assertTrue(patch.target().type().get().isRegularNonExecutable());
            assertTrue(patch.status().isModified());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(2, hunk.source().range().start());
            assertEquals(0, hunk.source().range().count());
            assertEquals(0, hunk.source().lines().size());

            assertEquals(2, hunk.target().range().start());
            assertEquals(1, hunk.target().range().count());
            assertLinesEquals(List.of("One more line"), hunk.target().lines());

            assertEquals(1, hunk.added());
            assertEquals(0, hunk.removed());
            assertEquals(0, hunk.modified());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffBetweenCommitsWithMultiplePatches(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            var building = dir.path().resolve("BUILDING");
            Files.write(building, List.of("make"));

            r.add(readme);
            r.add(building);
            var first = r.commit("Add README and BUILDING", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("Hello, Skara!"), WRITE, TRUNCATE_EXISTING);
            Files.write(building, List.of("make images"), WRITE, TRUNCATE_EXISTING);
            r.add(readme);
            r.add(building);
            var second = r.commit("Modify README and BUILDING", "duke", "duke@openjdk.java.net");

            var diff = r.diff(first, second);
            assertEquals(first, diff.from());
            assertEquals(second, diff.to());

            var patches = diff.patches();
            assertEquals(2, patches.size());

            var patch1 = patches.get(0).asTextualPatch();
            assertEquals(Path.of("BUILDING"), patch1.source().path().get());
            assertEquals(Path.of("BUILDING"), patch1.target().path().get());
            assertTrue(patch1.source().type().get().isRegularNonExecutable());
            assertTrue(patch1.target().type().get().isRegularNonExecutable());
            assertTrue(patch1.status().isModified());

            var hunks1 = patch1.hunks();
            assertEquals(1, hunks1.size());

            var hunk1 = hunks1.get(0);
            assertEquals(1, hunk1.source().range().start());
            assertEquals(1, hunk1.source().range().count());
            assertLinesEquals(List.of("make"), hunk1.source().lines());

            assertEquals(1, hunk1.target().range().start());
            assertEquals(1, hunk1.target().range().count());
            assertLinesEquals(List.of("make images"), hunk1.target().lines());

            var patch2 = patches.get(1).asTextualPatch();
            assertEquals(Path.of("README"), patch2.source().path().get());
            assertEquals(Path.of("README"), patch2.target().path().get());
            assertTrue(patch2.source().type().get().isRegularNonExecutable());
            assertTrue(patch2.target().type().get().isRegularNonExecutable());
            assertTrue(patch2.status().isModified());

            var hunks2 = patch2.hunks();
            assertEquals(1, hunks2.size());

            var hunk2 = hunks2.get(0);
            assertEquals(1, hunk2.source().range().start());
            assertEquals(1, hunk2.source().range().count());
            assertLinesEquals(List.of("Hello, readme!"), hunk2.source().lines());

            assertEquals(1, hunk2.target().range().start());
            assertEquals(1, hunk2.target().range().count());
            assertLinesEquals(List.of("Hello, Skara!"), hunk2.target().lines());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffBetweenCommitsWithMultipleHunks(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var abc = dir.path().resolve("abc.txt");
            Files.write(abc, List.of("A", "B", "C"));

            r.add(abc);
            var first = r.commit("Added ABC", "duke", "duke@openjdk.java.net");

            Files.write(abc, List.of("1", "2", "B", "3"), WRITE, TRUNCATE_EXISTING);
            r.add(abc);
            var second = r.commit("Modify A and C", "duke", "duke@openjdk.java.net");

            var diff = r.diff(first, second);
            assertEquals(first, diff.from());
            assertEquals(second, diff.to());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertEquals(Path.of("abc.txt"), patch.source().path().get());
            assertEquals(Path.of("abc.txt"), patch.target().path().get());
            assertTrue(patch.source().type().get().isRegularNonExecutable());
            assertTrue(patch.target().type().get().isRegularNonExecutable());
            assertTrue(patch.status().isModified());

            var hunks = patch.hunks();
            assertEquals(2, hunks.size());

            var hunk1 = hunks.get(0);
            assertEquals(1, hunk1.source().range().start());
            assertEquals(1, hunk1.source().range().count());
            assertLinesEquals(List.of("A"), hunk1.source().lines());

            assertEquals(1, hunk1.target().range().start());
            assertEquals(2, hunk1.target().range().count());
            assertLinesEquals(List.of("1", "2"), hunk1.target().lines());

            assertEquals(1, hunk1.added());
            assertEquals(0, hunk1.removed());
            assertEquals(1, hunk1.modified());

            var hunk2 = hunks.get(1);
            assertEquals(3, hunk2.source().range().start());
            assertEquals(1, hunk2.source().range().count());
            assertLinesEquals(List.of("C"), hunk2.source().lines());

            assertEquals(4, hunk2.target().range().start());
            assertEquals(1, hunk2.target().range().count());
            assertLinesEquals(List.of("3"), hunk2.target().lines());

            assertEquals(0, hunk2.added());
            assertEquals(0, hunk2.removed());
            assertEquals(1, hunk2.modified());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffWithRemoval(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));

            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.delete(readme);
            r.remove(readme);
            var second = r.commit("Removed README", "duke", "duke@openjdk.java.net");

            var diff = r.diff(first, second);
            assertEquals(first, diff.from());
            assertEquals(second, diff.to());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertEquals(Path.of("README"), patch.source().path().get());
            assertTrue(patch.target().path().isEmpty());
            assertTrue(patch.source().type().get().isRegularNonExecutable());
            assertTrue(patch.target().type().isEmpty());
            assertTrue(patch.status().isDeleted());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(1, hunk.source().range().start());
            assertEquals(1, hunk.source().range().count());
            assertLinesEquals(List.of("Hello, world!"), hunk.source().lines());

            assertEquals(0, hunk.target().range().start());
            assertEquals(0, hunk.target().range().count());
            assertLinesEquals(List.of(), hunk.target().lines());

            assertEquals(0, hunk.added());
            assertEquals(1, hunk.removed());
            assertEquals(0, hunk.modified());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffWithAddition(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));

            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            var building = dir.path().resolve("BUILDING");
            Files.write(building, List.of("make"));
            r.add(building);
            var second = r.commit("Added BUILDING", "duke", "duke@openjdk.java.net");

            var diff = r.diff(first, second);
            assertEquals(first, diff.from());
            assertEquals(second, diff.to());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertTrue(patch.source().path().isEmpty());
            assertEquals(Path.of("BUILDING"), patch.target().path().get());
            assertTrue(patch.source().type().isEmpty());
            assertTrue(patch.target().type().get().isRegularNonExecutable());
            assertTrue(patch.status().isAdded());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(0, hunk.source().range().start());
            assertEquals(0, hunk.source().range().count());
            assertLinesEquals(List.of(), hunk.source().lines());

            assertEquals(1, hunk.target().range().start());
            assertEquals(1, hunk.target().range().count());
            assertLinesEquals(List.of("make"), hunk.target().lines());

            assertEquals(1, hunk.added());
            assertEquals(0, hunk.removed());
            assertEquals(0, hunk.modified());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffWithWorkingDir(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));

            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            var diff = r.diff(first);

            assertEquals(first, diff.from());
            assertNull(diff.to());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            assertEquals(Path.of("README"), patch.source().path().get());
            assertEquals(Path.of("README"), patch.target().path().get());
            assertTrue(patch.source().type().get().isRegularNonExecutable());
            assertTrue(patch.target().type().get().isRegularNonExecutable());
            assertTrue(patch.status().isModified());

            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(2, hunk.source().range().start());
            assertEquals(0, hunk.source().range().count());
            assertLinesEquals(List.of(), hunk.source().lines());

            assertEquals(2, hunk.target().range().start());
            assertEquals(1, hunk.target().range().count());
            assertLinesEquals(List.of("One more line"), hunk.target().lines());

            assertEquals(1, hunk.added());
            assertEquals(0, hunk.removed());
            assertEquals(0, hunk.modified());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCommitMetadata(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("Modified README", "duke", "duke@openjdk.java.net");

            var metadata = r.commitMetadata();
            assertEquals(2, metadata.size());

            assertEquals(second, metadata.get(0).hash());
            assertEquals(List.of("Modified README"), metadata.get(0).message());

            assertEquals(first, metadata.get(1).hash());
            assertEquals(List.of("Added README"), metadata.get(1).message());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCommitMetadataWithFiles(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme1 = dir.path().resolve("README_1");
            Files.write(readme1, List.of("1"));
            r.add(readme1);
            var first = r.commit("Added README_1", "duke", "duke@openjdk.java.net");

            var readme2 = dir.path().resolve("README_2");
            Files.write(readme2, List.of("2"));
            r.add(readme2);
            var second = r.commit("Added README_2", "duke", "duke@openjdk.java.net");

            Files.write(readme2, List.of("3"), WRITE, APPEND);
            r.add(readme2);
            var third = r.commit("Modified README_2", "duke", "duke@openjdk.java.net");

            var metadata = r.commitMetadata(List.of(Path.of("README_1")));
            assertEquals(1, metadata.size());
            assertEquals(first, metadata.get(0).hash());

            metadata = r.commitMetadata(List.of(Path.of("README_2")));
            assertEquals(2, metadata.size());
            assertEquals(third, metadata.get(0).hash());
            assertEquals(second, metadata.get(1).hash());

            metadata = r.commitMetadata(List.of(Path.of("README_1"), Path.of("README_2")));
            assertEquals(3, metadata.size());
            assertEquals(third, metadata.get(0).hash());
            assertEquals(second, metadata.get(1).hash());
            assertEquals(first, metadata.get(2).hash());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testCommitMetadataWithReverse(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("Modified README", "duke", "duke@openjdk.java.net");

            var metadata = r.commitMetadata();
            assertEquals(2, metadata.size());
            assertEquals(second, metadata.get(0).hash());
            assertEquals(first, metadata.get(1).hash());

            metadata = r.commitMetadata(true);
            assertEquals(2, metadata.size());
            assertEquals(first, metadata.get(0).hash());
            assertEquals(second, metadata.get(1).hash());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testTrivialMerge(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("Modified README", "duke", "duke@openjdk.java.net");

            r.checkout(first, false);

            var contributing = dir.path().resolve("CONTRIBUTING");
            Files.write(contributing, List.of("Send those patches!"));
            r.add(contributing);
            var third = r.commit("Added contributing", "duke", "duke@openjdk.java.net");

            r.merge(second);
            r.commit("Merge", "duke", "duke@openjdk.java.net");

            var refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            var commits = r.commits(refspec).asList();

            assertEquals(4, commits.size());

            var merge = commits.get(0);
            assertEquals(List.of("Merge"), merge.message());

            var parents = new HashSet<>(merge.parents());
            assertEquals(2, parents.size());
            assertTrue(parents.contains(second));
            assertTrue(parents.contains(third));

            var diffs = merge.parentDiffs();
            assertEquals(2, diffs.size());

            var diff1 = diffs.get(0);
            assertEquals(merge.hash(), diff1.to());
            assertEquals(0, diff1.patches().size());
            assertTrue(parents.contains(diff1.from()));

            var diff2 = diffs.get(1);
            assertEquals(merge.hash(), diff2.to());
            assertEquals(0, diff2.patches().size());
            assertTrue(parents.contains(diff2.from()));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testMergeWithEdit(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("Modified README", "duke", "duke@openjdk.java.net");

            r.checkout(first, false);

            var contributing = dir.path().resolve("CONTRIBUTING");
            Files.write(contributing, List.of("Send those patches!"));
            r.add(contributing);
            var third = r.commit("Added contributing", "duke", "duke@openjdk.java.net");

            r.merge(second);

            Files.write(readme, List.of("One last line"), WRITE, APPEND);
            r.add(readme);
            r.commit("Merge", "duke", "duke@openjdk.java.net");

            var refspec = vcs == VCS.GIT ? r.head().hex() : r.head().hex() + ":0";
            var commits = r.commits(refspec).asList();

            assertEquals(4, commits.size());

            var merge = commits.get(0);
            assertEquals(List.of("Merge"), merge.message());

            var parents = new HashSet<>(merge.parents());
            assertEquals(2, parents.size());
            assertTrue(parents.contains(second));
            assertTrue(parents.contains(third));

            var diffs = merge.parentDiffs();
            assertEquals(2, diffs.size());

            var secondDiff = diffs.stream().filter(d -> d.from().equals(second)).findFirst().get();
            assertEquals(merge.hash(), secondDiff.to());
            assertEquals(1, secondDiff.patches().size());
            var secondPatch = secondDiff.patches().get(0).asTextualPatch();

            assertEquals(Path.of("README"), secondPatch.source().path().get());
            assertEquals(Path.of("README"), secondPatch.target().path().get());
            assertTrue(secondPatch.status().isModified());
            assertEquals(1, secondPatch.hunks().size());

            var secondHunk = secondPatch.hunks().get(0);
            assertLinesEquals(List.of(), secondHunk.source().lines());
            assertLinesEquals(List.of("One last line"), secondHunk.target().lines());

            assertEquals(3, secondHunk.source().range().start());
            assertEquals(0, secondHunk.source().range().count());
            assertEquals(3, secondHunk.target().range().start());
            assertEquals(1, secondHunk.target().range().count());

            var thirdDiff = diffs.stream().filter(d -> d.from().equals(third)).findFirst().get();
            assertEquals(merge.hash(), thirdDiff.to());
            assertEquals(1, thirdDiff.patches().size());
            var thirdPatch = thirdDiff.patches().get(0).asTextualPatch();

            assertEquals(Path.of("README"), thirdPatch.source().path().get());
            assertEquals(Path.of("README"), thirdPatch.target().path().get());
            assertTrue(thirdPatch.status().isModified());
            assertEquals(1, thirdPatch.hunks().size());

            var thirdHunk = thirdPatch.hunks().get(0);
            assertLinesEquals(List.of(), thirdHunk.source().lines());
            assertLinesEquals(List.of("One more line", "One last line"), thirdHunk.target().lines());

            assertEquals(2, thirdHunk.source().range().start());
            assertEquals(0, thirdHunk.source().range().count());
            assertEquals(2, thirdHunk.target().range().start());
            assertEquals(2, thirdHunk.target().range().count());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDefaultBranch(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            var expected = vcs == VCS.GIT ? "master" : "default";
            assertEquals(expected, r.defaultBranch().name());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testPaths(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            var remote = vcs == VCS.GIT ? "origin" : "default";
            r.setPaths(remote, "http://pull", "http://push");
            assertEquals("http://pull", r.pullPath(remote));
            assertEquals("http://push", r.pushPath(remote));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testIsValidRevisionRange(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertFalse(r.isValidRevisionRange("foo"));

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            r.commit("Added README", "duke", "duke@openjdk.java.net");

            assertTrue(r.isValidRevisionRange(r.defaultBranch().toString()));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDefaultTag(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            var expected = vcs == VCS.GIT ? Optional.empty() : Optional.of(new Tag("tip"));
            assertEquals(expected, r.defaultTag());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testTag(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            r.tag(first, "test", "Tagging test", "duke", "duke@openjdk.java.net");
            var defaultTag = r.defaultTag().orElse(null);
            var nonDefaultTags = r.tags().stream()
                                  .filter(tag -> !tag.equals(defaultTag))
                                  .map(Tag::toString)
                                  .collect(Collectors.toList());
            assertEquals(List.of("test"), nonDefaultTags);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testIsClean(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            assertFalse(r.isClean());

            r.add(readme);
            assertFalse(r.isClean());

            r.commit("Added README", "duke", "duke@openjdk.java.net");
            assertTrue(r.isClean());

            Files.delete(readme);
            assertFalse(r.isClean());

            Files.write(readme, List.of("Hello, world!"));
            assertTrue(r.isClean());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testShowOnExecutableFiles(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var readOnlyExecutableFile = dir.path().resolve("hello.sh");
            Files.write(readOnlyExecutableFile, List.of("echo 'hello'"));
            if (readOnlyExecutableFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = PosixFilePermissions.fromString("r-xr-xr-x");
                Files.setPosixFilePermissions(readOnlyExecutableFile, permissions);
            }
            r.add(readOnlyExecutableFile);
            var hash = r.commit("Added read only executable file", "duke", "duke@openjdk.java.net");
            assertEquals(Optional.of(List.of("echo 'hello'")), r.lines(readOnlyExecutableFile, hash));

            var readWriteExecutableFile = dir.path().resolve("goodbye.sh");
            Files.write(readWriteExecutableFile, List.of("echo 'goodbye'"));
            if (readOnlyExecutableFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = PosixFilePermissions.fromString("rwxrwxrwx");
                Files.setPosixFilePermissions(readWriteExecutableFile, permissions);
            }
            r.add(readWriteExecutableFile);
            var hash2 = r.commit("Added read-write executable file", "duke", "duke@openjdk.java.net");
            assertEquals(Optional.of(List.of("echo 'goodbye'")), r.lines(readWriteExecutableFile, hash2));
        }
    }

    @Test
    void testGetAndExistsOnNonExistingDirectory() throws IOException {
        var nonExistingDirectory = Path.of("this", "does", "not", "exist");
        assertEquals(Optional.empty(), Repository.get(nonExistingDirectory));
        assertEquals(false, Repository.exists(nonExistingDirectory));
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffOnFilenamesWithSpace(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var fileWithSpaceInName = dir.path().resolve("hello world.txt");
            Files.writeString(fileWithSpaceInName, "Hello world\n");
            r.add(fileWithSpaceInName);
            var hash1 = r.commit("Added file with space in name", "duke", "duke@openjdk.java.net");
            Files.writeString(fileWithSpaceInName, "Goodbye world\n");
            r.add(fileWithSpaceInName);
            var hash2 = r.commit("Modified file with space in name", "duke", "duke@openjdk.java.net");
            var diff = r.diff(hash1, hash2);
            var patches = diff.patches();
            assertEquals(1, patches.size());
            var patch = patches.get(0);
            assertTrue(patch.target().path().isPresent());
            var path = patch.target().path().get();
            assertEquals(Path.of("hello world.txt"), path);
        }
    }

    @Test
    void testSingleEmptyCommit() throws IOException, InterruptedException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);
            assertTrue(r.isClean());

            // must ust git directly to be able to pass --allow-empty
            var pb = new ProcessBuilder("git", "commit", "--message", "An empty commit", "--allow-empty");
            pb.environment().put("GIT_AUTHOR_NAME", "duke");
            pb.environment().put("GIT_AUTHOR_EMAIL", "duke@openjdk.org");
            pb.environment().put("GIT_COMMITTER_NAME", "duke");
            pb.environment().put("GIT_COMMITTER_EMAIL", "duke@openjdk.org");
            pb.directory(dir.path().toFile());

            var res = pb.start().waitFor();
            assertEquals(0, res);

            var commits = r.commits().asList();
            assertEquals(1, commits.size());
            var commit = commits.get(0);
            assertEquals("duke", commit.author().name());
            assertEquals("duke@openjdk.org", commit.author().email());
            assertEquals("duke", commit.committer().name());
            assertEquals("duke@openjdk.org", commit.committer().email());
            assertEquals(List.of("An empty commit"), commit.message());
        }
    }

    @Test
    void testEmptyCommitWithParent() throws IOException, InterruptedException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), VCS.GIT);
            assertTrue(r.isClean());

            var f = Files.createFile(dir.path().resolve("hello.txt"));
            Files.writeString(f, "Hello world\n");
            r.add(f);
            r.commit("Initial commit", "duke", "duke@openjdk.org");

            // must ust git directly to be able to pass --allow-empty
            var pb = new ProcessBuilder("git", "commit", "--message", "An empty commit", "--allow-empty");
            pb.environment().put("GIT_AUTHOR_NAME", "duke");
            pb.environment().put("GIT_AUTHOR_EMAIL", "duke@openjdk.org");
            pb.environment().put("GIT_COMMITTER_NAME", "duke");
            pb.environment().put("GIT_COMMITTER_EMAIL", "duke@openjdk.org");
            pb.directory(dir.path().toFile());

            var res = pb.start().waitFor();
            assertEquals(0, res);

            var commits = r.commits().asList();
            assertEquals(2, commits.size());
            var commit = commits.get(0);
            assertEquals("duke", commit.author().name());
            assertEquals("duke@openjdk.org", commit.author().email());
            assertEquals("duke", commit.committer().name());
            assertEquals("duke@openjdk.org", commit.committer().email());
            assertEquals(List.of("An empty commit"), commit.message());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testAmend(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            r.commit("Initial commit", "duke", "duke@openjdk.org");

            Files.writeString(f, "Hello, world\n");
            r.add(f);
            r.amend("Initial commit corrected", "duke", "duke@openjdk.java.net");
            var commits = r.commits().asList();
            assertEquals(1, commits.size());
            var commit = commits.get(0);
            assertEquals(List.of("Initial commit corrected"), commit.message());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRevert(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            var initial = r.commit("Initial commit", "duke", "duke@openjdk.org");

            Files.writeString(f, "Hello, world\n");
            r.revert(initial);
            Files.writeString(f, "Goodbye, world\n");
            r.add(f);
            var hash = r.commit("Second commit", "duke", "duke@openjdk.org");
            var commit = r.lookup(hash).orElseThrow();
            var patches = commit.parentDiffs().get(0).patches();
            assertEquals(1, patches.size());
            var patch = patches.get(0).asTextualPatch();
            assertEquals(1, patch.hunks().size());
            var hunk = patch.hunks().get(0);
            assertEquals(List.of("Goodbye, world"), hunk.target().lines());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testFiles(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            var initial = r.commit("Initial commit", "duke", "duke@openjdk.org");

            var entries = r.files(initial);
            assertEquals(1, entries.size());
            var entry = entries.get(0);
            assertEquals(Path.of("README"), entry.path());
            assertTrue(entry.type().isRegularNonExecutable());

            var f2 = dir.path().resolve("CONTRIBUTING");
            Files.writeString(f2, "Hello\n");
            r.add(f2);
            var second = r.commit("Second commit", "duke", "duke@openjdk.org");

            entries = r.files(second);
            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> e.type().isRegularNonExecutable()));
            var paths = entries.stream().map(FileEntry::path).collect(Collectors.toSet());
            assertTrue(paths.contains(Path.of("README")));
            assertTrue(paths.contains(Path.of("CONTRIBUTING")));

            entries = r.files(second, Path.of("README"));
            assertEquals(1, entries.size());
            entry = entries.get(0);
            assertEquals(Path.of("README"), entry.path());
            assertTrue(entry.type().isRegularNonExecutable());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDump(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            var initial = r.commit("Initial commit", "duke", "duke@openjdk.org");

            var readme = r.files(initial).get(0);

            var tmp = Files.createTempFile("README", "txt");
            r.dump(readme, tmp);
            assertEquals("Hello\n", Files.readString(tmp));
            Files.delete(tmp);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testStatus(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            var initial = r.commit("Initial commit", "duke", "duke@openjdk.org");

            var f2 = dir.path().resolve("CONTRIBUTING");
            Files.writeString(f2, "Goodbye\n");
            r.add(f2);
            var second = r.commit("Second commit", "duke", "duke@openjdk.org");

            var entries = r.status(initial, second);
            assertEquals(1, entries.size());
            var entry = entries.get(0);
            assertTrue(entry.status().isAdded());
            assertTrue(entry.source().path().isEmpty());
            assertTrue(entry.source().type().isEmpty());

            assertTrue(entry.target().path().isPresent());
            assertEquals(Path.of("CONTRIBUTING"), entry.target().path().get());
            assertTrue(entry.target().type().get().isRegular());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testTrackLineEndings(VCS vcs) throws IOException, InterruptedException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            if (vcs == VCS.GIT) { // turn of git's meddling
                int exitCode = new ProcessBuilder()
                        .command("git", "config", "--local", "core.autocrlf", "false")
                        .directory(dir.path().toFile())
                        .start()
                        .waitFor();
                assertEquals(0, exitCode);
            }

            var readme = dir.path().resolve("README");
            Files.writeString(readme, "Line with Unix line ending\n");
            Files.writeString(readme, "Line with Windows line ending\r\n", APPEND);

            r.add(readme);
            r.commit("Add README", "duke", "duke@openjdk.java.net");

            var commits = r.commits().asList();
            assertEquals(1, commits.size());

            var commit = commits.get(0);
            var diffs = commit.parentDiffs();
            var diff = diffs.get(0);
            assertEquals(2, diff.added());

            var patches = diff.patches();
            assertEquals(1, patches.size());

            var patch = patches.get(0).asTextualPatch();
            var hunks = patch.hunks();
            assertEquals(1, hunks.size());

            var hunk = hunks.get(0);
            assertEquals(new Range(0, 0), hunk.source().range());
            assertEquals(new Range(1, 2), hunk.target().range());

            assertEquals(
                    List.of("Line with Unix line ending", "Line with Windows line ending\r"),
                    hunk.target().lines());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testContains(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            var initial = r.commit("Initial commit", "duke", "duke@openjdk.org");

            assertTrue(r.contains(r.defaultBranch(), initial));

            Files.writeString(f, "Hello again\n");
            r.add(f);
            var second = r.commit("Second commit", "duke", "duke@openjdk.org");

            assertTrue(r.contains(r.defaultBranch(), initial));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testAbortMerge(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var r = Repository.init(dir.path(), vcs);
            assertTrue(r.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            r.add(f);
            var initial = r.commit("Initial commit", "duke", "duke@openjdk.org");

            Files.writeString(f, "Hello again\n");
            r.add(f);
            var second = r.commit("Second commit", "duke", "duke@openjdk.org");

            r.checkout(initial);
            Files.writeString(f, "Conflicting hello\n");
            r.add(f);
            var third = r.commit("Third commit", "duke", "duke@openjdk.org");

            assertThrows(IOException.class, () -> { r.merge(second); });

            r.abortMerge();
            assertTrue(r.isClean());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testReset(VCS vcs) throws IOException {
        assumeTrue(vcs == VCS.GIT); // FIXME reset is not yet implemented for HG

        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            assertTrue(repo.isClean());

            var f = dir.path().resolve("README");
            Files.writeString(f, "Hello\n");
            repo.add(f);
            var initial = repo.commit("Initial commit", "duke", "duke@openjdk.org");

            Files.writeString(f, "Hello again\n");
            repo.add(f);
            var second = repo.commit("Second commit", "duke", "duke@openjdk.org");

            assertEquals(second, repo.head());
            assertEquals(2, repo.commits().asList().size());

            repo.reset(initial, true);

            assertEquals(initial, repo.head());
            assertEquals(1, repo.commits().asList().size());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRemotes(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            assertEquals(List.of(), repo.remotes());
            repo.addRemote("foobar", "https://foo/bar");
            assertEquals(List.of("foobar"), repo.remotes());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testRemoteBranches(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var upstream = Repository.init(dir.path().resolve("upstream"), vcs);
            var readme = upstream.root().resolve("README");
            Files.writeString(readme, "Hello\n");
            upstream.add(readme);
            var head = upstream.commit("Added README", "duke", "duke@openjdk.org");

            var fork = Repository.init(dir.path().resolve("fork"), vcs);
            fork.addRemote("upstream", upstream.root().toUri().toString());
            var refs = fork.remoteBranches("upstream");
            assertEquals(1, refs.size());
            var ref = refs.get(0);
            assertEquals(head, ref.hash());
            assertEquals(upstream.defaultBranch().name(), ref.name());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testSubmodulesOnEmptyRepo(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            assertEquals(List.of(), repo.submodules());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testSubmodulesOnRepoWithNoSubmodules(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path().resolve("repo"), vcs);
            var readme = repo.root().resolve("README");
            Files.writeString(readme, "Hello\n");
            repo.add(readme);
            repo.commit("Added README", "duke", "duke@openjdk.org");
            assertEquals(List.of(), repo.submodules());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testSubmodulesOnRepoWithSubmodule(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var submodule = Repository.init(dir.path().resolve("submodule"), vcs);
            var readme = submodule.root().resolve("README");
            Files.writeString(readme, "Hello\n");
            submodule.add(readme);
            var head = submodule.commit("Added README", "duke", "duke@openjdk.org");

            var repo = Repository.init(dir.path().resolve("repo"), vcs);
            var pullPath = submodule.root().toAbsolutePath().toString();
            repo.addSubmodule(pullPath, Path.of("sub"));
            repo.commit("Added submodule", "duke", "duke@openjdk.org");

            var submodules = repo.submodules();
            assertEquals(1, submodules.size());
            var module = submodules.get(0);
            assertEquals(Path.of("sub"), module.path());
            assertEquals(head, module.hash());
            assertEquals(pullPath, module.pullPath());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testAnnotateTag(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory(false)) {
            var repo = Repository.init(dir.path(), vcs);
            var readme = repo.root().resolve("README");
            var now = ZonedDateTime.now();
            Files.writeString(readme, "Hello\n");
            repo.add(readme);
            var head = repo.commit("Added README", "duke", "duke@openjdk.org");
            var tag = repo.tag(head, "1.0", "Added tag 1.0 for HEAD\n", "duke", "duke@openjdk.org");
            var annotated = repo.annotate(tag).get();

            assertEquals("1.0", annotated.name());
            assertEquals(head, annotated.target());
            assertEquals(new Author("duke", "duke@openjdk.org"), annotated.author());
            assertEquals(now.getYear(), annotated.date().getYear());
            assertEquals(now.getMonth(), annotated.date().getMonth());
            assertEquals(now.getDayOfYear(), annotated.date().getDayOfYear());
            assertEquals(now.getHour(), annotated.date().getHour());
            assertEquals(now.getOffset(), annotated.date().getOffset());
            assertEquals("Added tag 1.0 for HEAD\n", annotated.message());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testAnnotateTagOnMissingTag(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            var readme = repo.root().resolve("README");
            var now = ZonedDateTime.now();
            Files.writeString(readme, "Hello\n");
            repo.add(readme);
            var head = repo.commit("Added README", "duke", "duke@openjdk.org");

            assertEquals(Optional.empty(), repo.annotate(new Tag("unknown")));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testAnnotateTagOnEmptyRepo(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            assertEquals(Optional.empty(), repo.annotate(new Tag("unknown")));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testDiffWithFileList(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            var readme = repo.root().resolve("README");
            Files.writeString(readme, "Hello\n");
            repo.add(readme);

            var contribute = repo.root().resolve("CONTRIBUTE");
            Files.writeString(contribute, "1. Make changes\n");
            repo.add(contribute);

            var first = repo.commit("Added README and CONTRIBUTE", "duke", "duke@openjdk.org");
            Files.writeString(readme, "World\n", WRITE, APPEND);
            Files.writeString(contribute, "2. Run git commit", WRITE, APPEND);

            var diff = repo.diff(first, List.of(Path.of("README")));
            assertEquals(1, diff.added());
            assertEquals(0, diff.modified());
            assertEquals(0, diff.removed());
            var patches = diff.patches();
            assertEquals(1, patches.size());
            var patch = patches.get(0);
            assertTrue(patch.isTextual());
            assertTrue(patch.status().isModified());
            assertEquals(Path.of("README"), patch.source().path().get());
            assertEquals(Path.of("README"), patch.target().path().get());

            repo.add(readme);
            repo.add(contribute);
            var second = repo.commit("Updates to both README and CONTRIBUTE", "duke", "duke@openjdk.org");

            diff = repo.diff(first, second, List.of(Path.of("CONTRIBUTE")));
            assertEquals(1, diff.added());
            assertEquals(0, diff.modified());
            assertEquals(0, diff.removed());
            patches = diff.patches();
            assertEquals(1, patches.size());
            patch = patches.get(0);
            assertTrue(patch.isTextual());
            assertTrue(patch.status().isModified());
            assertEquals(Path.of("CONTRIBUTE"), patch.source().path().get());
            assertEquals(Path.of("CONTRIBUTE"), patch.target().path().get());

            diff = repo.diff(first, second, List.of(Path.of("DOES_NOT_EXIST")));
            assertEquals(0, diff.patches().size());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testWritingConfigValue(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var repo = Repository.init(dir.path(), vcs);
            assertEquals(List.of(), repo.config("test.key"));
            repo.config("test", "key", "value");
            assertEquals(List.of("value"), repo.config("test.key"));
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testFetchRemote(VCS vcs) throws IOException {
        try (var dir = new TemporaryDirectory()) {
            var upstream = Repository.init(dir.path(), vcs);
            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            upstream.add(readme);
            upstream.commit("Add README", "duke", "duke@openjdk.java.net");

            try (var dir2 = new TemporaryDirectory()) {
                var downstream = Repository.init(dir2.path(), vcs);

                 // note: forcing unix path separators for URI
                var upstreamURI = URI.create("file:///" + dir.toString().replace('\\', '/'));
                downstream.addRemote("upstream", upstreamURI.toString());
                downstream.addRemote("foobar", "file:///this/path/does/not/exist");
                downstream.fetchRemote("upstream");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testPrune(VCS vcs) throws IOException {
        assumeTrue(vcs == VCS.GIT); // FIXME hard to test with hg due to bookmarks and branches
        try (var dir = new TemporaryDirectory(false)) {
            var upstreamDir = dir.path().resolve("upstream" + (vcs == VCS.GIT ? ".git" : ".hg"));
            var upstream = Repository.init(upstreamDir, vcs);

            Files.write(upstream.root().resolve(".git").resolve("config"),
                        List.of("[receive]", "denyCurrentBranch=ignore"),
                        WRITE, APPEND);

            var readme = upstreamDir.resolve("README");
            Files.write(readme, List.of("Hello, readme!"));

            upstream.add(readme);
            var head = upstream.commit("Add README", "duke", "duke@openjdk.java.net");
            var branch = upstream.branch(head, "foo");
            var upstreamBranches = upstream.branches();
            assertEquals(2, upstreamBranches.size());
            assertTrue(upstreamBranches.contains(branch));

            var upstreamURI = URI.create("file:///" + upstreamDir.toString().replace('\\', '/'));
            var downstreamDir = dir.path().resolve("downstream");
            var downstream = Repository.clone(upstreamURI, downstreamDir);

            // Ensure that 'foo' branch is materialized downstream
            downstream.checkout(branch);
            downstream.checkout(downstream.defaultBranch());

            var remotes = downstream.remotes();
            assertEquals(1, remotes.size());
            var downstreamBranches = downstream.branches();
            assertEquals(2, downstreamBranches.size());
            assertEquals(downstreamBranches, upstreamBranches);

            downstream.prune(branch, remotes.get(0));

            downstreamBranches = downstream.branches();
            assertEquals(1, downstreamBranches.size());
            assertEquals(List.of(downstream.defaultBranch()), downstreamBranches);

            upstreamBranches = upstream.branches();
            assertEquals(1, upstreamBranches.size());
            assertEquals(List.of(upstream.defaultBranch()), upstreamBranches);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void testUnmergedStatus(VCS vcs) throws IOException {
        assumeTrue(vcs == VCS.GIT);
        try (var dir = new TemporaryDirectory(false)) {
            var r = Repository.init(dir.path(), vcs);

            var readme = dir.path().resolve("README");
            Files.write(readme, List.of("Hello, world!"));
            r.add(readme);
            var first = r.commit("Added README", "duke", "duke@openjdk.java.net");

            Files.write(readme, List.of("One more line"), WRITE, APPEND);
            r.add(readme);
            var second = r.commit("Modified README", "duke", "duke@openjdk.java.net");

            r.checkout(first, false);

            Files.write(readme, List.of("Another line"), WRITE, APPEND);
            r.add(readme);
            var third = r.commit("Modified README again", "duke", "duke@openjdk.java.net");

            assertThrows(IOException.class, () -> { r.merge(second); });

            var status = r.status();
            for (var s : status) {
                System.out.println(s.status() + " " + s.source().path().get());
            }
            assertEquals(2, status.size());
            var statusEntry = status.get(0);
            assertTrue(statusEntry.status().isUnmerged());
            assertEquals(Path.of("README"), statusEntry.source().path().get());
            assertEquals(Optional.empty(), statusEntry.source().type());
            assertEquals("0".repeat(40), statusEntry.source().hash().hex());
        }
    }
}
