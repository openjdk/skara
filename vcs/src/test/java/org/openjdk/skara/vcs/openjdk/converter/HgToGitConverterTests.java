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
import org.openjdk.skara.vcs.openjdk.convert.HgToGitConverter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HgToGitConverterTests {
    @Test
    void convertOneCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var readme = hgRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            hgRepo.add(readme);
            hgRepo.commit("1234567: Added README", "foo", "foo@localhost");

            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);

            var converter = new HgToGitConverter(Map.of(), Map.of(), Set.of(), Set.of(),
                                                 Map.of("foo", "Foo Bar <foo@openjdk.java.net>"), Map.of(), Map.of());
            var marks = converter.convert(hgRepo, gitRepo);
            assertEquals(1, marks.size());

            var gitCommits = gitRepo.commits().asList();
            assertEquals(1, gitCommits.size());
            var gitCommit = gitCommits.get(0);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(gitCommit.author(), new Author("Foo Bar", "foo@openjdk.java.net"));
            assertEquals(gitCommit.committer(), new Author("Foo Bar", "foo@openjdk.java.net"));
            assertEquals(hgCommit.message(), gitCommit.message());
            assertEquals(hgCommit.authored(), gitCommit.authored());
            assertEquals(hgCommit.isInitialCommit(), gitCommit.isInitialCommit());
            assertEquals(hgCommit.isMerge(), gitCommit.isMerge());
            assertEquals(hgCommit.numParents(), gitCommit.numParents());

            var hgDiffs = hgCommit.parentDiffs();
            assertEquals(1, hgDiffs.size());
            var hgDiff = hgDiffs.get(0);

            var gitDiffs = gitCommit.parentDiffs();
            assertEquals(1, gitDiffs.size());
            var gitDiff = gitDiffs.get(0);

            var hgPatches = hgDiff.patches();
            assertEquals(1, hgPatches.size());
            var hgPatch = hgPatches.get(0).asTextualPatch();

            var gitPatches = gitDiff.patches();
            assertEquals(1, gitPatches.size());
            var gitPatch = gitPatches.get(0).asTextualPatch();
            assertEquals(hgPatch.stats(), gitPatch.stats());

            assertEquals(hgPatch.source().path(), gitPatch.source().path());
            assertEquals(hgPatch.source().type(), gitPatch.source().type());

            assertEquals(hgPatch.target().path(), gitPatch.target().path());
            assertEquals(hgPatch.target().type(), gitPatch.target().type());

            assertEquals(hgPatch.status(), gitPatch.status());

            var hgHunks = hgPatch.hunks();
            assertEquals(1, hgHunks.size());
            var hgHunk = hgHunks.get(0);

            var gitHunks = gitPatch.hunks();
            assertEquals(1, gitHunks.size());
            var gitHunk = gitHunks.get(0);

            assertEquals(hgHunk.source().range(), gitHunk.source().range());
            assertEquals(hgHunk.source().lines(), gitHunk.source().lines());

            assertEquals(hgHunk.target().range(), gitHunk.target().range());
            assertEquals(hgHunk.target().lines(), gitHunk.target().lines());

            assertEquals(hgHunk.added(), gitHunk.added());
            assertEquals(hgHunk.removed(), gitHunk.removed());
            assertEquals(hgHunk.modified(), gitHunk.modified());
        }
    }

    @Test
    void convertOneSponsoredCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var readme = hgRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            hgRepo.add(readme);
            var message = List.of("1234567: Added README", "Contributed-by: baz@domain.org");
            hgRepo.commit(String.join("\n", message), "foo", "foo@host.com");

            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);

            var converter = new HgToGitConverter(Map.of(), Map.of(), Set.of(), Set.of(),
                                                 Map.of("foo", "Foo Bar <foo@openjdk.java.net>"),
                                                 Map.of("baz@domain.org", "Baz Bar <baz@domain.org>"),
                                                 Map.of("foo", List.of("foo@host.com")));
            var marks = converter.convert(hgRepo, gitRepo);
            assertEquals(1, marks.size());

            var gitCommits = gitRepo.commits().asList();
            assertEquals(1, gitCommits.size());
            var gitCommit = gitCommits.get(0);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("Baz Bar", "baz@domain.org"), gitCommit.author());
            assertEquals(new Author("Foo Bar", "foo@openjdk.java.net"), gitCommit.committer());
            assertEquals(List.of("1234567: Added README"), gitCommit.message());
        }
    }

    @Test
    void convertOneCoAuthoredCommit() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var readme = hgRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            hgRepo.add(readme);
            var message = List.of("1234567: Added README", "Contributed-by: baz@domain.org, foo@host.com");
            hgRepo.commit(String.join("\n", message), "foo", "foo@host.com");

            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);

            var converter = new HgToGitConverter(Map.of(), Map.of(), Set.of(), Set.of(),
                                                 Map.of("foo", "Foo Bar <foo@openjdk.java.net>"),
                                                 Map.of("baz@domain.org", "Baz Bar <baz@domain.org>",
                                                        "foo@host.com", "Foo Bar <foo@host.com>"),
                                                 Map.of("foo", List.of("foo@host.com")));
            var marks = converter.convert(hgRepo, gitRepo);
            assertEquals(1, marks.size());

            var gitCommits = gitRepo.commits().asList();
            assertEquals(1, gitCommits.size());
            var gitCommit = gitCommits.get(0);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("Foo Bar", "foo@openjdk.java.net"), gitCommit.author());
            assertEquals(new Author("Foo Bar", "foo@openjdk.java.net"), gitCommit.committer());
            assertEquals(List.of("1234567: Added README", "", "Co-authored-by: Baz Bar <baz@domain.org>"),
                         gitCommit.message());
        }
    }

    @Test
    void convertCommitWithSummary() throws IOException {
        try (var hgRoot = new TemporaryDirectory();
             var gitRoot = new TemporaryDirectory()) {
            var hgRepo = Repository.init(hgRoot.path(), VCS.HG);
            var readme = hgRoot.path().resolve("README.md");

            Files.writeString(readme, "Hello, world");
            hgRepo.add(readme);
            var message = List.of("1234567: Added README", "Summary: additional text", "Contributed-by: baz@domain.org, foo@host.com");
            hgRepo.commit(String.join("\n", message), "foo", "foo@host.com");

            var gitRepo = Repository.init(gitRoot.path(), VCS.GIT);

            var converter = new HgToGitConverter(Map.of(), Map.of(), Set.of(), Set.of(),
                                                 Map.of("foo", "Foo Bar <foo@openjdk.java.net>"),
                                                 Map.of("baz@domain.org", "Baz Bar <baz@domain.org>",
                                                        "foo@host.com", "Foo Bar <foo@host.com>"),
                                                 Map.of("foo", List.of("foo@host.com")));
            var marks = converter.convert(hgRepo, gitRepo);
            assertEquals(1, marks.size());

            var gitCommits = gitRepo.commits().asList();
            assertEquals(1, gitCommits.size());
            var gitCommit = gitCommits.get(0);

            var hgCommits = hgRepo.commits().asList();
            assertEquals(1, hgCommits.size());
            var hgCommit = hgCommits.get(0);

            assertEquals(new Author("Foo Bar", "foo@openjdk.java.net"), gitCommit.author());
            assertEquals(new Author("Foo Bar", "foo@openjdk.java.net"), gitCommit.committer());
            assertEquals(List.of("1234567: Added README", "", "Additional text", "", "Co-authored-by: Baz Bar <baz@domain.org>"),
                         gitCommit.message());
        }
    }
}
