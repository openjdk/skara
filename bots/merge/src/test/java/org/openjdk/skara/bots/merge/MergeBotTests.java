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
package org.openjdk.skara.bots.merge;

import org.openjdk.skara.host.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MergeBotTests {
    @Test
    void mergeMasterBranch(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory(false)) {
            var host = TestHost.createNew(List.of(new HostUser(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var toGitConfig = toDir.resolve(".git").resolve("config");
            Files.write(toGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var forkDir = temp.path().resolve("fork.git");
            var forkLocalRepo = Repository.init(forkDir, VCS.GIT);
            var forkGitConfig = forkDir.resolve(".git").resolve("config");
            Files.write(forkGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toFork = new TestHostedRepository(host, "test-mirror-fork", forkLocalRepo);

            var now = ZonedDateTime.now();
            var fromFileA = fromDir.resolve("a.txt");
            Files.writeString(fromFileA, "Hello A\n");
            fromLocalRepo.add(fromFileA);
            var fromHashA = fromLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(fromHashA, fromCommits.get(0).hash());

            var toFileA = toDir.resolve("a.txt");
            Files.writeString(toFileA, "Hello A\n");
            toLocalRepo.add(toFileA);
            var toHashA = toLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(toHashA, toCommits.get(0).hash());
            assertEquals(fromHashA, toHashA);

            var fromFileB = fromDir.resolve("b.txt");
            Files.writeString(fromFileB, "Hello B\n");
            fromLocalRepo.add(fromFileB);
            var fromHashB = fromLocalRepo.commit("Adding b.txt", "duke", "duke@openjdk.org");

            var toFileC = toDir.resolve("c.txt");
            Files.writeString(toFileC, "Hello C\n");
            toLocalRepo.add(toFileC);
            var toHashC = toLocalRepo.commit("Adding c.txt", "duke", "duke@openjdk.org");

            var storage = temp.path().resolve("storage");
            var master = new Branch("master");
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());
            var hashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(hashes.contains(toHashA));
            assertTrue(hashes.contains(fromHashB));
            assertTrue(hashes.contains(toHashC));

            var known = Set.of(toHashA, fromHashB, toHashC);
            var merge = toCommits.stream().filter(c -> !known.contains(c.hash())).findAny().get();
            assertTrue(merge.isMerge());
            assertEquals(List.of("Merge"), merge.message());
            assertEquals("duke", merge.author().name());
            assertEquals("duke@openjdk.org", merge.author().email());

            assertEquals(0, toHostedRepo.pullRequests().size());
        }
    }

    @Test
    void failingMergeTest(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory(false)) {
            var host = TestHost.createNew(List.of(new HostUser(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var toGitConfig = toDir.resolve(".git").resolve("config");
            Files.write(toGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var forkDir = temp.path().resolve("fork.git");
            var forkLocalRepo = Repository.init(forkDir, VCS.GIT);
            var forkGitConfig = forkDir.resolve(".git").resolve("config");
            Files.write(forkGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toFork = new TestHostedRepository(host, "test-mirror-fork", forkLocalRepo);

            var now = ZonedDateTime.now();
            var fromFileA = fromDir.resolve("a.txt");
            Files.writeString(fromFileA, "Hello A\n");
            fromLocalRepo.add(fromFileA);
            var fromHashA = fromLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(fromHashA, fromCommits.get(0).hash());

            var toFileA = toDir.resolve("a.txt");
            Files.writeString(toFileA, "Hello A\n");
            toLocalRepo.add(toFileA);
            var toHashA = toLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(toHashA, toCommits.get(0).hash());
            assertEquals(fromHashA, toHashA);

            var fromFileB = fromDir.resolve("b.txt");
            Files.writeString(fromFileB, "Hello B1\n");
            fromLocalRepo.add(fromFileB);
            var fromHashB = fromLocalRepo.commit("Adding b1.txt", "duke", "duke@openjdk.org");

            var toFileB = toDir.resolve("b.txt");
            Files.writeString(toFileB, "Hello B2\n");
            toLocalRepo.add(toFileB);
            var toHashB = toLocalRepo.commit("Adding b2.txt", "duke", "duke@openjdk.org");

            var storage = temp.path().resolve("storage");
            var master = new Branch("master");
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            var toHashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(toHashes.contains(toHashA));
            assertTrue(toHashes.contains(toHashB));

            var pullRequests = toHostedRepo.pullRequests();
            assertEquals(1, pullRequests.size());
            var pr = pullRequests.get(0);
            assertEquals("Cannot automatically merge test:master to master", pr.title());
        }
    }

    @Test
    void failingMergeShouldResultInOnlyOnePR(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory(false)) {
            var host = TestHost.createNew(List.of(new HostUser(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var toGitConfig = toDir.resolve(".git").resolve("config");
            Files.write(toGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var forkDir = temp.path().resolve("fork.git");
            var forkLocalRepo = Repository.init(forkDir, VCS.GIT);
            var forkGitConfig = forkDir.resolve(".git").resolve("config");
            Files.write(forkGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toFork = new TestHostedRepository(host, "test-mirror-fork", forkLocalRepo);

            var now = ZonedDateTime.now();
            var fromFileA = fromDir.resolve("a.txt");
            Files.writeString(fromFileA, "Hello A\n");
            fromLocalRepo.add(fromFileA);
            var fromHashA = fromLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(fromHashA, fromCommits.get(0).hash());

            var toFileA = toDir.resolve("a.txt");
            Files.writeString(toFileA, "Hello A\n");
            toLocalRepo.add(toFileA);
            var toHashA = toLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(toHashA, toCommits.get(0).hash());
            assertEquals(fromHashA, toHashA);

            var fromFileB = fromDir.resolve("b.txt");
            Files.writeString(fromFileB, "Hello B1\n");
            fromLocalRepo.add(fromFileB);
            var fromHashB = fromLocalRepo.commit("Adding b1.txt", "duke", "duke@openjdk.org");

            var toFileB = toDir.resolve("b.txt");
            Files.writeString(toFileB, "Hello B2\n");
            toLocalRepo.add(toFileB);
            var toHashB = toLocalRepo.commit("Adding b2.txt", "duke", "duke@openjdk.org");

            var storage = temp.path().resolve("storage");
            var master = new Branch("master");
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs);
            TestBotRunner.runPeriodicItems(bot);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            var toHashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(toHashes.contains(toHashA));
            assertTrue(toHashes.contains(toHashB));

            var pullRequests = toHostedRepo.pullRequests();
            assertEquals(1, pullRequests.size());
            var pr = pullRequests.get(0);
            assertEquals("Cannot automatically merge test:master to master", pr.title());
        }
    }

    @Test
    void resolvedMergeConflictShouldResultInClosedPR(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory(false)) {
            var host = TestHost.createNew(List.of(new HostUser(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var toGitConfig = toDir.resolve(".git").resolve("config");
            Files.write(toGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var forkDir = temp.path().resolve("fork.git");
            var forkLocalRepo = Repository.init(forkDir, VCS.GIT);
            var forkGitConfig = forkDir.resolve(".git").resolve("config");
            Files.write(forkGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toFork = new TestHostedRepository(host, "test-mirror-fork", forkLocalRepo);

            var now = ZonedDateTime.now();
            var fromFileA = fromDir.resolve("a.txt");
            Files.writeString(fromFileA, "Hello A\n");
            fromLocalRepo.add(fromFileA);
            var fromHashA = fromLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(fromHashA, fromCommits.get(0).hash());

            var toFileA = toDir.resolve("a.txt");
            Files.writeString(toFileA, "Hello A\n");
            toLocalRepo.add(toFileA);
            var toHashA = toLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(toHashA, toCommits.get(0).hash());
            assertEquals(fromHashA, toHashA);

            var fromFileB = fromDir.resolve("b.txt");
            Files.writeString(fromFileB, "Hello B1\n");
            fromLocalRepo.add(fromFileB);
            var fromHashB = fromLocalRepo.commit("Adding b1.txt", "duke", "duke@openjdk.org", now);

            var toFileB = toDir.resolve("b.txt");
            Files.writeString(toFileB, "Hello B2\n");
            toLocalRepo.add(toFileB);
            var toHashB = toLocalRepo.commit("Adding b2.txt", "duke", "duke@openjdk.org", now);

            var storage = temp.path().resolve("storage");
            var master = new Branch("master");
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs);
            TestBotRunner.runPeriodicItems(bot);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            var toHashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(toHashes.contains(toHashA));
            assertTrue(toHashes.contains(toHashB));

            var pullRequests = toHostedRepo.pullRequests();
            assertEquals(1, pullRequests.size());
            var pr = pullRequests.get(0);
            assertEquals("Cannot automatically merge test:master to master", pr.title());

            var fetchHead = toLocalRepo.fetch(fromHostedRepo.webUrl(), "master");
            toLocalRepo.merge(fetchHead, "ours");
            toLocalRepo.commit("Merge", "duke", "duke@openjdk.org", now);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            TestBotRunner.runPeriodicItems(bot);
            pullRequests = toHostedRepo.pullRequests();
            assertEquals(0, pullRequests.size());
        }
    }

    @Test
    void resolvedMergeConflictAndThenNewConflict(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory(false)) {
            var host = TestHost.createNew(List.of(new HostUser(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = Repository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = Repository.init(toDir, VCS.GIT);
            var toGitConfig = toDir.resolve(".git").resolve("config");
            Files.write(toGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var forkDir = temp.path().resolve("fork.git");
            var forkLocalRepo = Repository.init(forkDir, VCS.GIT);
            var forkGitConfig = forkDir.resolve(".git").resolve("config");
            Files.write(forkGitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toFork = new TestHostedRepository(host, "test-mirror-fork", forkLocalRepo);

            var now = ZonedDateTime.now();
            var fromFileA = fromDir.resolve("a.txt");
            Files.writeString(fromFileA, "Hello A\n");
            fromLocalRepo.add(fromFileA);
            var fromHashA = fromLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(1, fromCommits.size());
            assertEquals(fromHashA, fromCommits.get(0).hash());

            var toFileA = toDir.resolve("a.txt");
            Files.writeString(toFileA, "Hello A\n");
            toLocalRepo.add(toFileA);
            var toHashA = toLocalRepo.commit("Adding a.txt", "duke", "duke@openjdk.org", now);
            var toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(toHashA, toCommits.get(0).hash());
            assertEquals(fromHashA, toHashA);

            var fromFileB = fromDir.resolve("b.txt");
            Files.writeString(fromFileB, "Hello B1\n");
            fromLocalRepo.add(fromFileB);
            var fromHashB = fromLocalRepo.commit("Adding b1.txt", "duke", "duke@openjdk.org", now);

            var toFileB = toDir.resolve("b.txt");
            Files.writeString(toFileB, "Hello B2\n");
            toLocalRepo.add(toFileB);
            var toHashB = toLocalRepo.commit("Adding b2.txt", "duke", "duke@openjdk.org", now);

            var storage = temp.path().resolve("storage");
            var master = new Branch("master");
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs);
            TestBotRunner.runPeriodicItems(bot);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            var toHashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(toHashes.contains(toHashA));
            assertTrue(toHashes.contains(toHashB));

            var pullRequests = toHostedRepo.pullRequests();
            assertEquals(1, pullRequests.size());
            var pr = pullRequests.get(0);
            assertEquals("Cannot automatically merge test:master to master", pr.title());

            var fetchHead = toLocalRepo.fetch(fromHostedRepo.webUrl(), "master");
            toLocalRepo.merge(fetchHead, "ours");
            toLocalRepo.commit("Merge", "duke", "duke@openjdk.org", now);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            TestBotRunner.runPeriodicItems(bot);
            pullRequests = toHostedRepo.pullRequests();
            assertEquals(0, pullRequests.size());

            var fromFileC = fromDir.resolve("c.txt");
            Files.writeString(fromFileC, "Hello C1\n");
            fromLocalRepo.add(fromFileC);
            fromLocalRepo.commit("Adding c1", "duke", "duke@openjdk.org", now);

            var toFileC = toDir.resolve("c.txt");
            Files.writeString(toFileC, "Hello C2\n");
            toLocalRepo.add(toFileC);
            toLocalRepo.commit("Adding c2", "duke", "duke@openjdk.org", now);

            TestBotRunner.runPeriodicItems(bot);
            pullRequests = toHostedRepo.pullRequests();
            assertEquals(1, pullRequests.size());
            assertEquals("Cannot automatically merge test:master to master", pr.title());
        }
    }
}
