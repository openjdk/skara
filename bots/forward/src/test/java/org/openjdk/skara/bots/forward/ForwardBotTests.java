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
package org.openjdk.skara.bots.forward;

import org.openjdk.skara.host.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SubmitBotTests {
    private static final Branch master = new Branch("master");

    @Test
    void mirrorMasterBranches(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(new HostUserDetails(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var gitConfig = toDir.resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var newFile = fromDir.resolve("this-file-cannot-exist.txt");
            Files.writeString(newFile, "Hello world\n");
            fromLocalRepo.add(newFile);
            var newHash = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(newHash, fromCommits.get(0).hash());

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());

            var storage = temp.path().resolve("storage");
            var bot = new ForwardBot(storage, fromHostedRepo, master, toHostedRepo, master);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
        }
    }

    @Test
    void mirrorDifferentBranches(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(new HostUserDetails(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var gitConfig = toDir.resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var newFile = fromDir.resolve("this-file-cannot-exist.txt");
            Files.writeString(newFile, "Hello world\n");
            fromLocalRepo.add(newFile);
            var newHash = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(newHash, fromCommits.get(0).hash());

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());

            var storage = temp.path().resolve("storage");
            var bot = new ForwardBot(storage, fromHostedRepo, master, toHostedRepo, new Branch("dev"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());

            var toBranches = toLocalRepo.branches();
            assertEquals(1, toBranches.size());
            assertEquals("dev", toBranches.get(0).name());
        }
    }
}
