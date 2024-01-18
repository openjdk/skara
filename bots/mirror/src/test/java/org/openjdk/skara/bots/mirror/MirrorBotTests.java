/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mirror;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.openjdk.skara.host.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MirrorBotTests {
    @Test
    void mirrorMasterBranch(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
        }
    }

    @Test
    void mirrorMultipleBranches(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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

            fromLocalRepo.branch(newHash, "second");
            fromLocalRepo.branch(newHash, "third");

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());
            assertEquals(0, toLocalRepo.branches().size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
            var toBranches = toLocalRepo.branches();
            assertEquals(3, toBranches.size());
            assertTrue(toBranches.contains(new Branch("master")));
            assertTrue(toBranches.contains(new Branch("second")));
            assertTrue(toBranches.contains(new Branch("third")));
        }
    }

    /**
     * Tests mirrorEverything with multiple tags
     */
    @Test
    void mirrorEverythingMultipleTags(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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

            fromLocalRepo.tag(newHash, "first", "add first tag", "duke", "duk@openjdk.org");
            fromLocalRepo.tag(newHash, "second", "add second tag", "duke", "duk@openjdk.org");

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());
            assertEquals(0, toLocalRepo.tags().size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
            var toTags = toLocalRepo.tags();
            assertEquals(2, toTags.size());
            assertTrue(toTags.contains(new Tag("first")));
            assertTrue(toTags.contains(new Tag("second")));

            // Add another tag and go again
            fromLocalRepo.tag(newHash, "third", "add third tag", "duke", "duk@openjdk.org");

            TestBotRunner.runPeriodicItems(bot);
            toTags = toLocalRepo.tags();
            assertEquals(3, toTags.size());
            assertTrue(toTags.contains(new Tag("first")));
            assertTrue(toTags.contains(new Tag("second")));
            assertTrue(toTags.contains(new Tag("third")));
        }
    }

    /**
     * Tests mirroring a single branch, including tags
     */
    @Test
    void mirrorSingleBranchAndTags(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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

            fromLocalRepo.tag(newHash, "first", "add first tag", "duke", "duk@openjdk.org");
            fromLocalRepo.tag(newHash, "second", "add second tag", "duke", "duk@openjdk.org");

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());
            assertEquals(0, toLocalRepo.tags().size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(Pattern.compile("master")), true, false, List.of());
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
            var toTags = toLocalRepo.tags();
            assertEquals(2, toTags.size());
            assertTrue(toTags.contains(new Tag("first")));
            assertTrue(toTags.contains(new Tag("second")));

            // Add another tag and go again
            fromLocalRepo.tag(newHash, "third", "add third tag", "duke", "duk@openjdk.org");

            TestBotRunner.runPeriodicItems(bot);
            toTags = toLocalRepo.tags();
            assertEquals(3, toTags.size());
            assertTrue(toTags.contains(new Tag("first")));
            assertTrue(toTags.contains(new Tag("second")));
            assertTrue(toTags.contains(new Tag("third")));

            // Change a tag and go again
            Files.writeString(newFile, "Hello world again\n", StandardOpenOption.APPEND);
            fromLocalRepo.add(newFile);
            var secondHash = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var firstTag = fromLocalRepo.tag(secondHash, "first", "add first tag again", "duke", "duk@openjdk.org", null, true);

            TestBotRunner.runPeriodicItems(bot);
            toTags = toLocalRepo.tags();
            assertEquals(3, toTags.size());
            assertEquals(fromLocalRepo.annotate(firstTag), toLocalRepo.annotate(firstTag), "First tag not correctly mirrored");
        }
    }

    /**
     * Tests mirroring a single branch without including tags
     */
    @Test
    void mirrorSingleBranchNoTags(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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

            fromLocalRepo.tag(newHash, "first", "add first tag", "duke", "duk@openjdk.org");

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());
            assertEquals(0, toLocalRepo.tags().size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(Pattern.compile("master")), false, false, List.of());
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
            var toTags = toLocalRepo.tags();
            assertEquals(0, toTags.size());

            // Go a second time
            TestBotRunner.runPeriodicItems(bot);
            toTags = toLocalRepo.tags();
            assertEquals(0, toTags.size());
        }
    }

    @Test
    void mirrorRemovingBranch(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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

            fromLocalRepo.branch(newHash, "second");
            fromLocalRepo.branch(newHash, "third");

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());
            assertEquals(0, toLocalRepo.branches().size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
            var toBranches = toLocalRepo.branches();
            assertEquals(3, toBranches.size());
            assertTrue(toBranches.contains(new Branch("master")));
            assertTrue(toBranches.contains(new Branch("second")));
            assertTrue(toBranches.contains(new Branch("third")));

            fromLocalRepo.delete(new Branch("second"));
            assertEquals(2, fromLocalRepo.branches().size());

            TestBotRunner.runPeriodicItems(bot);
            toBranches = toLocalRepo.branches();
            assertEquals(2, toBranches.size());
            assertTrue(toBranches.contains(new Branch("master")));
            assertTrue(toBranches.contains(new Branch("third")));
        }
    }

    @Test
    void mirrorSelectedBranches(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
            var gitConfig = toDir.resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                        StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var newFile = fromDir.resolve("this-file-cannot-exist.txt");
            Files.writeString(newFile, "Hello world\n");
            fromLocalRepo.add(newFile);
            var first = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var featureBranch = fromLocalRepo.branch(first, "feature");
            fromLocalRepo.checkout(featureBranch, false);
            assertEquals(Optional.of(featureBranch), fromLocalRepo.currentBranch());

            Files.writeString(newFile, "Hello again\n", StandardOpenOption.APPEND);
            fromLocalRepo.add(newFile);
            var second = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");

            assertEquals(Optional.of(first), fromLocalRepo.resolve("master"));
            assertEquals(Optional.of(second), fromLocalRepo.resolve("feature"));

            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(2, fromCommits.size());

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(Pattern.compile("master")), false, false, List.of());
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(first, toCommits.get(0).hash());
            assertEquals(List.of(new Branch("master")), toLocalRepo.branches());
        }
    }

    @Test
    void mirrorSelectedBranchPattern(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
            var gitConfig = toDir.resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                    StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var newFile = fromDir.resolve("this-file-cannot-exist.txt");
            Files.writeString(newFile, "Hello world\n");
            fromLocalRepo.add(newFile);
            var first = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var featureBranch = fromLocalRepo.branch(first, "feature");
            fromLocalRepo.checkout(featureBranch, false);
            assertEquals(Optional.of(featureBranch), fromLocalRepo.currentBranch());

            Files.writeString(newFile, "Hello again\n", StandardOpenOption.APPEND);
            fromLocalRepo.add(newFile);
            var second = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");

            assertEquals(Optional.of(first), fromLocalRepo.resolve("master"));
            assertEquals(Optional.of(second), fromLocalRepo.resolve("feature"));

            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(2, fromCommits.size());

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(Pattern.compile("f.*")), false, false, List.of());
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(second, toCommits.get(0).hash());
            assertEquals(List.of(new Branch("feature")), toLocalRepo.branches());
        }
    }

    @Test
    void mirrorMasterBranchWithExistingCloneDirectory(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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
            var sanitizedUrl =
                    URLEncoder.encode(toHostedRepo.webUrl().toString(), StandardCharsets.UTF_8);
            var temporaryDir = storage.resolve(sanitizedUrl);
            Files.createDirectories(temporaryDir);
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo);
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
        }
    }

    /**
     * Tests mirroring only tags
     */
    @Test
    void mirrorOnlyTags(TestInfo testInfo) throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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

            fromLocalRepo.tag(newHash, "first", "add first tag", "duke", "duk@openjdk.org");
            fromLocalRepo.tag(newHash, "second", "add second tag", "duke", "duk@openjdk.org");

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());
            assertEquals(0, toLocalRepo.tags().size());
            assertEquals(0, toLocalRepo.branches().size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(), true, true, List.of());
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
            var toTags = toLocalRepo.tags();
            assertEquals(2, toTags.size());
            assertTrue(toTags.contains(new Tag("first")));
            assertTrue(toTags.contains(new Tag("second")));
            assertEquals(0, toLocalRepo.branches().size());

            // Add another tag and go again
            fromLocalRepo.tag(newHash, "third", "add third tag", "duke", "duk@openjdk.org");

            TestBotRunner.runPeriodicItems(bot);
            toTags = toLocalRepo.tags();
            assertEquals(3, toTags.size());
            assertTrue(toTags.contains(new Tag("first")));
            assertTrue(toTags.contains(new Tag("second")));
            assertTrue(toTags.contains(new Tag("third")));
            assertEquals(0, toLocalRepo.branches().size());
            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());

            // Change a tag and go again
            Files.writeString(newFile, "Hello world again\n", StandardOpenOption.APPEND);
            fromLocalRepo.add(newFile);
            var secondHash = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var firstTag = fromLocalRepo.tag(secondHash, "first", "add first tag again", "duke", "duk@openjdk.org", null, true);

            TestBotRunner.runPeriodicItems(bot);
            assertEquals(0, toLocalRepo.branches().size());
            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            toTags = toLocalRepo.tags();
            assertEquals(3, toTags.size());
            assertEquals(fromLocalRepo.annotate(firstTag), toLocalRepo.annotate(firstTag), "First tag not correctly mirrored");
        }
    }

    @Test
    void mirrorRefspecs() throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
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
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(), false, false,
                    List.of("refs/heads/master:refs/heads/master"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(1, toCommits.size());
            assertEquals(newHash, toCommits.get(0).hash());
        }
    }

    @Test
    void mirrorMultipleRefspecs() throws IOException {
        try (var temp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));

            var fromDir = temp.path().resolve("from.git");
            var fromLocalRepo = TestableRepository.init(fromDir, VCS.GIT);
            var fromHostedRepo = new TestHostedRepository(host, "test", fromLocalRepo);

            var toDir = temp.path().resolve("to.git");
            var toLocalRepo = TestableRepository.init(toDir, VCS.GIT);
            var gitConfig = toDir.resolve(".git").resolve("config");
            Files.write(gitConfig, List.of("[receive]", "denyCurrentBranch = ignore"),
                    StandardOpenOption.APPEND);
            var toHostedRepo = new TestHostedRepository(host, "test-mirror", toLocalRepo);

            var newFile = fromDir.resolve("this-file-cannot-exist.txt");
            Files.writeString(newFile, "Hello world\n");
            fromLocalRepo.add(newFile);
            var first = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");
            var featureBranch = fromLocalRepo.branch(first, "feature");
            fromLocalRepo.checkout(featureBranch, false);
            assertEquals(Optional.of(featureBranch), fromLocalRepo.currentBranch());

            Files.writeString(newFile, "Hello again\n", StandardOpenOption.APPEND);
            fromLocalRepo.add(newFile);
            var second = fromLocalRepo.commit("An additional commit", "duke", "duke@openjdk.org");

            assertEquals(Optional.of(first), fromLocalRepo.resolve("master"));
            assertEquals(Optional.of(second), fromLocalRepo.resolve("feature"));

            fromLocalRepo.tag(first, "firstTag", "add first tag", "duke", "duk@openjdk.org");
            fromLocalRepo.tag(second, "secondTag", "add second tag", "duke", "duk@openjdk.org");

            var fromCommits = fromLocalRepo.commits().asList();
            assertEquals(2, fromCommits.size());

            var toCommits = toLocalRepo.commits().asList();
            assertEquals(0, toCommits.size());

            var storage = temp.path().resolve("storage");
            var bot = new MirrorBot(storage, fromHostedRepo, toHostedRepo, List.of(), false, false,
                    List.of("refs/heads/m*:refs/heads/m*", "refs/tags/s*:refs/tags/s*"));
            TestBotRunner.runPeriodicItems(bot);

            toCommits = toLocalRepo.commits().asList();
            assertEquals(2, toCommits.size());
            assertEquals(second, toCommits.get(0).hash());
            assertEquals(List.of(new Branch("master")), toLocalRepo.branches());
            assertEquals(List.of(new Tag("secondTag")), toLocalRepo.tags());
        }
    }
}
