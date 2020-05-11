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
package org.openjdk.skara.webrev;

import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebrevTests {
    void assertContains(Path file, String text) throws IOException {
        var contents = Files.readString(file);
        assertTrue(contents.contains(text));
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void simple(VCS vcs) throws IOException {
        try (var repoFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory()) {
            var repo = Repository.init(repoFolder.path(), vcs);
            var file = repoFolder.path().resolve("x.txt");
            Files.writeString(file, "1\n2\n3\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash1 = repo.commit("Commit", "a", "a@a.a");
            Files.writeString(file, "1\n2\n3\n4\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash2 = repo.commit("Commit 2", "a", "a@a.a");

            new Webrev.Builder(repo, webrevFolder.path()).generate(hash1, hash2);
            assertContains(webrevFolder.path().resolve("x.txt"), "1\n2\n3\n4\n");
            assertContains(webrevFolder.path().resolve("index.html"), "<td>1 lines changed; 1 ins; 0 del; 0 mod; 3 unchg</td>");
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void middle(VCS vcs) throws IOException {
        try (var repoFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory()) {
            var repo = Repository.init(repoFolder.path(), vcs);
            var file = repoFolder.path().resolve("x.txt");
            Files.writeString(file, "1\n2\n3\n4\n5\n6\n7\n8\n9\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash1 = repo.commit("Commit", "a", "a@a.a");
            Files.writeString(file, "1\n2\n3\n4\n5\n5.1\n5.2\n6\n7\n8\n9\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash2 = repo.commit("Commit 2", "a", "a@a.a");

            new Webrev.Builder(repo, webrevFolder.path()).generate(hash1, hash2);
            assertContains(webrevFolder.path().resolve("index.html"), "<td>2 lines changed; 2 ins; 0 del; 0 mod; 9 unchg</td>");
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void emptySourceHunk(VCS vcs) throws IOException {
        try (var repoFolder = new TemporaryDirectory();
        var webrevFolder = new TemporaryDirectory()) {
            var repo = Repository.init(repoFolder.path(), vcs);
            var file = repoFolder.path().resolve("x.txt");
            Files.writeString(file, "1\n2\n3\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash1 = repo.commit("Commit", "a", "a@a.a");
            Files.writeString(file, "0\n1\n2\n3\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash2 = repo.commit("Commit 2", "a", "a@a.a");

            new Webrev.Builder(repo, webrevFolder.path()).generate(hash1, hash2);
            assertContains(webrevFolder.path().resolve("index.html"), "<td>1 lines changed; 1 ins; 0 del; 0 mod; 3 unchg</td>");
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void removedHeader(VCS vcs) throws IOException {
        try (var repoFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory()) {
            var repo = Repository.init(repoFolder.path(), vcs);
            var file = repoFolder.path().resolve("x.txt");
            Files.writeString(file, "1\n2\n3\n4\n5\n6\n7\n8\n9\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash1 = repo.commit("Commit", "a", "a@a.a");
            Files.writeString(file, "5\n6\n7\n8\n9\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash2 = repo.commit("Commit 2", "a", "a@a.a");

            new Webrev.Builder(repo, webrevFolder.path()).generate(hash1, hash2);
            assertContains(webrevFolder.path().resolve("index.html"), "<td>4 lines changed; 0 ins; 4 del; 0 mod; 1 unchg</td>");
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void removeBinaryFile(VCS vcs) throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var repo = Repository.init(tmp.path().resolve("repo"), vcs);
            var binaryFile = repo.root().resolve("x.jpg");
            byte[] contents = {0x1, 0x2, 0x3, 0x4, 0x5, 0x0, 0x2, 0x3, 0x4, 0x5};
            Files.write(binaryFile, contents);
            repo.add(binaryFile);
            var hash1 = repo.commit("Added binary file", "a", "a@a.a");
            repo.remove(binaryFile);
            var hash2 = repo.commit("Removed binary file", "a", "a@a.a");

            new Webrev.Builder(repo, tmp.path().resolve("webrev")).generate(hash1, hash2);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void addBinaryFile(VCS vcs) throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var repo = Repository.init(tmp.path().resolve("repo"), vcs);
            var readme = repo.root().resolve("README");
            Files.writeString(readme, "Hello\n");
            repo.add(readme);
            var hash1 = repo.commit("Added readme", "a", "a@a");

            var binaryFile = repo.root().resolve("x.jpg");
            byte[] contents = {0x1, 0x2, 0x3, 0x4, 0x5, 0x0, 0x2, 0x3, 0x4, 0x5};
            Files.write(binaryFile, contents);
            repo.add(binaryFile);
            var hash2 = repo.commit("Added binary file", "a", "a@a.a");

            new Webrev.Builder(repo, tmp.path().resolve("webrev")).generate(hash1, hash2);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void modifyBinaryFile(VCS vcs) throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var repo = Repository.init(tmp.path().resolve("repo"), vcs);
            var readme = repo.root().resolve("README");
            var binaryFile = repo.root().resolve("x.jpg");
            byte[] contents = {0x1, 0x2, 0x3, 0x4, 0x5, 0x0, 0x2, 0x3, 0x4, 0x5};
            Files.write(binaryFile, contents);
            repo.add(binaryFile);
            var hash1 = repo.commit("Added binary file", "a", "a@a.a");

            byte[] newContent =  {0x1, 0x2, 0x3, 0x4, 0x5, 0x0, 0x2, 0x3, 0x4, 0x5, 0x6};
            Files.write(binaryFile, newContent);
            repo.add(binaryFile);
            var hash2 = repo.commit("Modified binary file", "a", "a@a.a");

            new Webrev.Builder(repo, tmp.path().resolve("webrev")).generate(hash1, hash2);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void reservedName(VCS vcs) throws IOException {
        try (var repoFolder = new TemporaryDirectory();
             var webrevFolder = new TemporaryDirectory()) {
            var repo = Repository.init(repoFolder.path(), vcs);
            var file = repoFolder.path().resolve("index.html");
            Files.writeString(file, "1\n2\n3\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash1 = repo.commit("Commit", "a", "a@a.a");
            Files.writeString(file, "1\n2\n3\n4\n", StandardCharsets.UTF_8);
            repo.add(file);
            var hash2 = repo.commit("Commit 2", "a", "a@a.a");

            new Webrev.Builder(repo, webrevFolder.path()).generate(hash1, hash2);
            assertContains(webrevFolder.path().resolve("_index.html"), "1\n2\n3\n4\n");
            assertContains(webrevFolder.path().resolve("index.html"), "<td>1 lines changed; 1 ins; 0 del; 0 mod; 3 unchg</td>");
        }
    }

}
