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
import java.time.DayOfWeek;
import java.time.Month;
import java.time.ZonedDateTime;
import java.time.ZoneId;

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
            assertEquals(List.of("Automatic merge of test:master into master"), merge.message());
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

    final static class TestClock implements Clock {
        ZonedDateTime now;

        TestClock() {
            this(null);
        }

        TestClock(ZonedDateTime now) {
            this.now = now;
        }

        @Override
        public ZonedDateTime now() {
            return now;
        }
    }

    @Test
    void testMergeHourly(TestInfo testInfo) throws IOException {
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

            // Merge only at most once during the first minute every hour
            var freq = MergeBot.Spec.Frequency.hourly(1);
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master, freq));

            var clock = new TestClock(ZonedDateTime.of(2020, 1, 23, 15, 0, 0, 0, ZoneId.of("GMT+1")));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs, clock);

            TestBotRunner.runPeriodicItems(bot);

            // Ensure nothing has been merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(toHashC, toCommits.get(0).hash());
            assertEquals(toHashA, toCommits.get(1).hash());

            // Set the clock to the first minute of the hour
            clock.now = ZonedDateTime.of(2020, 1, 23, 15, 1, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            // Should have merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());
            var hashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(hashes.contains(toHashA));
            assertTrue(hashes.contains(fromHashB));
            assertTrue(hashes.contains(toHashC));

            var known = Set.of(toHashA, fromHashB, toHashC);
            var merge = toCommits.stream().filter(c -> !known.contains(c.hash())).findAny().get();
            assertTrue(merge.isMerge());
            assertEquals(List.of("Automatic merge of test:master into master"), merge.message());
            assertEquals("duke", merge.author().name());
            assertEquals("duke@openjdk.org", merge.author().email());

            assertEquals(0, toHostedRepo.pullRequests().size());

            var fromFileD = fromDir.resolve("d.txt");
            Files.writeString(fromFileD, "Hello D\n");
            fromLocalRepo.add(fromFileD);
            var fromHashD = fromLocalRepo.commit("Adding d.txt", "duke", "duke@openjdk.org");

            // Since the time hasn't changed it should not merge again
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the minutes forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 23, 15, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the clock forward one hour, the bot should merge
            clock.now = ZonedDateTime.of(2020, 1, 23, 16, 1, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(6, toCommits.size());
        }
    }

    @Test
    void testMergeDaily(TestInfo testInfo) throws IOException {
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
            var toHostedRepo = new TestHostedRepository(host, "test", toLocalRepo);

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

            // Merge only at most once during the third hour every day
            var freq = MergeBot.Spec.Frequency.daily(3);
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master, freq));

            var clock = new TestClock(ZonedDateTime.of(2020, 1, 23, 2, 45, 0, 0, ZoneId.of("GMT+1")));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs, clock);

            TestBotRunner.runPeriodicItems(bot);

            // Ensure nothing has been merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(toHashC, toCommits.get(0).hash());
            assertEquals(toHashA, toCommits.get(1).hash());

            // Set the clock to the third hour of the day (minutes should not matter)
            clock.now = ZonedDateTime.of(2020, 1, 23, 3, 37, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            // Should have merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());
            var hashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(hashes.contains(toHashA));
            assertTrue(hashes.contains(fromHashB));
            assertTrue(hashes.contains(toHashC));

            var known = Set.of(toHashA, fromHashB, toHashC);
            var merge = toCommits.stream().filter(c -> !known.contains(c.hash())).findAny().get();
            assertTrue(merge.isMerge());
            assertEquals(List.of("Automatic merge of master into master"), merge.message());
            assertEquals("duke", merge.author().name());
            assertEquals("duke@openjdk.org", merge.author().email());

            assertEquals(0, toHostedRepo.pullRequests().size());

            var fromFileD = fromDir.resolve("d.txt");
            Files.writeString(fromFileD, "Hello D\n");
            fromLocalRepo.add(fromFileD);
            var fromHashD = fromLocalRepo.commit("Adding d.txt", "duke", "duke@openjdk.org");

            // Since the time hasn't changed it should not merge
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the minutes forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 23, 3, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the hours forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 23, 17, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the clock forward one day, the bot should merge
            clock.now = ZonedDateTime.of(2020, 1, 24, 3, 55, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(6, toCommits.size());
        }
    }

    @Test
    void testMergeWeekly(TestInfo testInfo) throws IOException {
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

            // Merge only at most once per week on Friday's at 12:00
            var freq = MergeBot.Spec.Frequency.weekly(DayOfWeek.FRIDAY, 12);
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master, freq));

            var clock = new TestClock(ZonedDateTime.of(2020, 1, 24, 11, 45, 0, 0, ZoneId.of("GMT+1")));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs, clock);

            TestBotRunner.runPeriodicItems(bot);

            // Ensure nothing has been merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(toHashC, toCommits.get(0).hash());
            assertEquals(toHashA, toCommits.get(1).hash());

            // Set the clock to the 12th hour of the day (minutes should not matter)
            clock.now = ZonedDateTime.of(2020, 1, 24, 12, 37, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            // Should have merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());
            var hashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(hashes.contains(toHashA));
            assertTrue(hashes.contains(fromHashB));
            assertTrue(hashes.contains(toHashC));

            var known = Set.of(toHashA, fromHashB, toHashC);
            var merge = toCommits.stream().filter(c -> !known.contains(c.hash())).findAny().get();
            assertTrue(merge.isMerge());
            assertEquals(List.of("Automatic merge of test:master into master"), merge.message());
            assertEquals("duke", merge.author().name());
            assertEquals("duke@openjdk.org", merge.author().email());

            assertEquals(0, toHostedRepo.pullRequests().size());

            var fromFileD = fromDir.resolve("d.txt");
            Files.writeString(fromFileD, "Hello D\n");
            fromLocalRepo.add(fromFileD);
            var fromHashD = fromLocalRepo.commit("Adding d.txt", "duke", "duke@openjdk.org");

            // Since the time hasn't changed it should not merge
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the hours forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 24, 13, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the days forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 25, 13, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the clock forward one week, the bot should merge
            clock.now = ZonedDateTime.of(2020, 1, 31, 12, 29, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(6, toCommits.size());
        }
    }

    @Test
    void testMergeMonthly(TestInfo testInfo) throws IOException {
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
            var toHostedRepo = new TestHostedRepository(host, "test", toLocalRepo);

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

            // Merge only at most once per month on the 17th day at at 11:00
            var freq = MergeBot.Spec.Frequency.monthly(17, 11);
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master, freq));

            var clock = new TestClock(ZonedDateTime.of(2020, 1, 16, 11, 0, 0, 0, ZoneId.of("GMT+1")));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs, clock);

            TestBotRunner.runPeriodicItems(bot);

            // Ensure nothing has been merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(toHashC, toCommits.get(0).hash());
            assertEquals(toHashA, toCommits.get(1).hash());

            // Set the clock to the 17th day and at hour 11 (minutes should not matter)
            clock.now = ZonedDateTime.of(2020, 1, 17, 11, 37, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            // Should have merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());
            var hashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(hashes.contains(toHashA));
            assertTrue(hashes.contains(fromHashB));
            assertTrue(hashes.contains(toHashC));

            var known = Set.of(toHashA, fromHashB, toHashC);
            var merge = toCommits.stream().filter(c -> !known.contains(c.hash())).findAny().get();
            assertTrue(merge.isMerge());
            assertEquals(List.of("Automatic merge of master into master"), merge.message());
            assertEquals("duke", merge.author().name());
            assertEquals("duke@openjdk.org", merge.author().email());

            assertEquals(0, toHostedRepo.pullRequests().size());

            var fromFileD = fromDir.resolve("d.txt");
            Files.writeString(fromFileD, "Hello D\n");
            fromLocalRepo.add(fromFileD);
            var fromHashD = fromLocalRepo.commit("Adding d.txt", "duke", "duke@openjdk.org");

            // Since the time hasn't changed it should not merge
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the hours forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 17, 12, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the days forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 1, 18, 11, 0, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the clock forward one month, the bot should merge
            clock.now = ZonedDateTime.of(2020, 2, 17, 11, 55, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(6, toCommits.size());
        }
    }

    @Test
    void testMergeYearly(TestInfo testInfo) throws IOException {
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
            var toHostedRepo = new TestHostedRepository(host, "test", toLocalRepo);

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

            // Merge only at most once per year on the 29th day of May at at 07:00
            var freq = MergeBot.Spec.Frequency.yearly(Month.MAY, 29, 07);
            var specs = List.of(new MergeBot.Spec(fromHostedRepo, master, master, freq));

            var clock = new TestClock(ZonedDateTime.of(2020, 5, 27, 11, 0, 0, 0, ZoneId.of("GMT+1")));
            var bot = new MergeBot(storage, toHostedRepo, toFork, specs, clock);

            TestBotRunner.runPeriodicItems(bot);

            // Ensure nothing has been merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(toHashC, toCommits.get(0).hash());
            assertEquals(toHashA, toCommits.get(1).hash());

            // Set the clock to the 29th of May and at hour 11 (minutes should not matter)
            clock.now = ZonedDateTime.of(2020, 5, 29, 7, 37, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            // Should have merged
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());
            var hashes = toCommits.stream().map(Commit::hash).collect(Collectors.toSet());
            assertTrue(hashes.contains(toHashA));
            assertTrue(hashes.contains(fromHashB));
            assertTrue(hashes.contains(toHashC));

            var known = Set.of(toHashA, fromHashB, toHashC);
            var merge = toCommits.stream().filter(c -> !known.contains(c.hash())).findAny().get();
            assertTrue(merge.isMerge());
            assertEquals(List.of("Automatic merge of master into master"), merge.message());
            assertEquals("duke", merge.author().name());
            assertEquals("duke@openjdk.org", merge.author().email());

            assertEquals(0, toHostedRepo.pullRequests().size());

            var fromFileD = fromDir.resolve("d.txt");
            Files.writeString(fromFileD, "Hello D\n");
            fromLocalRepo.add(fromFileD);
            var fromHashD = fromLocalRepo.commit("Adding d.txt", "duke", "duke@openjdk.org");

            // Since the time hasn't changed it should not merge again
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the hours forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 5, 29, 8, 45, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the days forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 5, 30, 11, 0, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the months forward, the bot should not merge
            clock.now = ZonedDateTime.of(2020, 7, 29, 7, 0, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);
            toCommits = toLocalRepo.commits().asList();
            assertEquals(4, toCommits.size());

            // Move the clock forward one year, the bot should merge
            clock.now = ZonedDateTime.of(2021, 5, 29, 7, 55, 0, 0, ZoneId.of("GMT+1"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(6, toCommits.size());
        }
    }
}
