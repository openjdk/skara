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
package org.openjdk.skara.bots.topological;

import org.openjdk.skara.host.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.*;

class TopologicalBotTests {

    @Test
    void testTopoMerge() throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(new HostUserDetails(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var repo = Repository.init(fromDir, VCS.GIT);
            var gitConfig = repo.root().resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var hostedRepo = new TestHostedRepository(host, "test", repo);

            // make non bare
            var readme = fromDir.resolve("README.txt");
            Files.writeString(readme, "Hello world\n");
            repo.add(readme);
            repo.commit("An initial commit", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var aBranch = repo.branch(repo.head(), "A");
            // no deps -> depends on master

            var depsFileName = "deps.txt";

            var bBranch = repo.branch(repo.head(), "B");
            repo.checkout(bBranch);
            var bDeps = fromDir.resolve(depsFileName);
            Files.writeString(bDeps, "A");
            repo.add(bDeps);
            repo.commit("Adding deps file to B", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var cBranch = repo.branch(repo.head(), "C");
            repo.checkout(cBranch);
            var cDeps = fromDir.resolve(depsFileName);
            Files.writeString(cDeps, "B");
            repo.add(cDeps);
            repo.commit("Adding deps file to C", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            repo.checkout(new Branch("master"));
            var newFile = fromDir.resolve("NewFile.txt");
            Files.writeString(newFile, "Hello world\n");
            repo.add(newFile);
            var preHash = repo.commit("An additional commit", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var preCommits = repo.commits().asList();
            assertEquals(4, preCommits.size());
            assertEquals(preHash, repo.head());

            var branches = List.of("C", "A", "B").stream().map(Branch::new).collect(Collectors.toList());
            var storage = temp.path().resolve("storage");
            var bot = new TopologicalBot(storage, hostedRepo, branches, depsFileName);
            TestBotRunner.runPeriodicItems(bot);

            var postCommits = repo.commits().asList();
            assertEquals(6, postCommits.size());

            repo.checkout(aBranch);
            assertEquals(preHash, repo.head());

            repo.checkout(bBranch);
            assertNotEquals(preHash, repo.head()); // merge commit

            repo.checkout(cBranch);
            assertNotEquals(preHash, repo.head()); // merge commit
        }
    }

    @Test
    void testTopoMergeFailure() throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(new HostUserDetails(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var repo = Repository.init(fromDir, VCS.GIT);
            var gitConfig = repo.root().resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"), APPEND);
            var hostedRepo = new TestHostedRepository(host, "test", repo);

            // make non bare
            var readme = fromDir.resolve("README.txt");
            Files.writeString(readme, "Hello world\n");
            repo.add(readme);
            repo.commit("An initial commit", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var aBranch = repo.branch(repo.head(), "A");
            repo.checkout(aBranch);
            Files.writeString(readme, "A conflicting line\n", APPEND);
            repo.add(readme);
            var aStartHash = repo.commit("A conflicting commit", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var depsFileName = "deps.txt";

            var bBranch = repo.branch(repo.head(), "B");
            repo.checkout(bBranch);
            var bDeps = fromDir.resolve(depsFileName);
            Files.writeString(bDeps, "A");
            repo.add(bDeps);
            var bDepsHash = repo.commit("Adding deps file to B", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var cBranch = repo.branch(repo.head(), "C");
            repo.checkout(cBranch);
            var cDeps = fromDir.resolve(depsFileName);
            Files.writeString(cDeps, "B");
            repo.add(cDeps);
            var cDepsHash = repo.commit("Adding deps file to C", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            repo.checkout(new Branch("master"));
            Files.writeString(readme, "Goodbye world!\n", APPEND);
            repo.add(readme);
            var preHash = repo.commit("An additional commit", "duke", "duke@openjdk.org");
            repo.pushAll(hostedRepo.getUrl());

            var preCommits = repo.commits().asList();
            assertEquals(5, preCommits.size());
            assertEquals(preHash, repo.head());

            var branches = List.of("C", "A", "B").stream().map(Branch::new).collect(Collectors.toList());
            var storage = temp.path().resolve("storage");
            var bot = new TopologicalBot(storage, hostedRepo, branches, depsFileName);
            assertThrows(UncheckedIOException.class, () -> TestBotRunner.runPeriodicItems(bot));

            var postCommits = repo.commits().asList();
            assertEquals(5, postCommits.size());

            repo.checkout(aBranch);
            assertEquals(aStartHash, repo.head());

            repo.checkout(bBranch);
            assertEquals(bDepsHash, repo.head());

            repo.checkout(cBranch);
            assertEquals(cDepsHash, repo.head());
        }
    }
}
