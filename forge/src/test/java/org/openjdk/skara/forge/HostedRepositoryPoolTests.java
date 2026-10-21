/*
 * Copyright (c) 2020, 2026, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge;

import org.junit.jupiter.api.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class HostedRepositoryPoolTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory();
             var cloneFolder = new TemporaryDirectory()) {
            var source = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(sourceFolder.path(), source.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, source.authenticatedUrl(), "master", true);

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.authenticatedUrl(), "master");

            var pool = new HostedRepositoryPool(seedFolder.path());
            var clone = pool.checkout(source, hash.hex(), cloneFolder.path());
            assertTrue(CheckableRepository.hasBeenEdited(clone));
        }
    }

    @Test
    void simpleBare(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory();
             var cloneFolder = new TemporaryDirectory()) {
            var source = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(sourceFolder.path(), source.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, source.authenticatedUrl(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var bareClone = pool.materializeBare(source, cloneFolder.path());

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.authenticatedUrl(), "master");

            // The commit should not appear from this
            bareClone = pool.materializeBare(source, cloneFolder.path());
            var bareCommit = bareClone.lookup(hash);
            assertEquals(Optional.empty(), bareCommit);

            // But should be possible to fetch
            bareClone.fetchAll(source.authenticatedUrl());
            bareCommit = bareClone.lookup(hash);
            assertEquals(bareCommit.get().hash(), hash);
        }
    }

    @Test
    void emptyExisting(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory();
             var cloneFolder = new TemporaryDirectory()) {
            var source = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(sourceFolder.path(), source.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, source.authenticatedUrl(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var empty = TestableRepository.init(cloneFolder.path(), VCS.GIT);
            assertThrows(IOException.class, () -> empty.checkout(new Branch("master"), true));
            var clone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(clone));
        }
    }

    @Test
    void partialExisting(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory();
             var cloneFolder = new TemporaryDirectory()) {
            var source = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(sourceFolder.path(), source.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, source.authenticatedUrl(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var clone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(clone));

            var updatedClone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(updatedClone));

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.authenticatedUrl(), "master");

            updatedClone = pool.checkout(source, "master", cloneFolder.path());
            assertTrue(CheckableRepository.hasBeenEdited(updatedClone));
        }
    }

    @Test
    void partialExistingAllowStale(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory();
             var cloneFolder = new TemporaryDirectory()) {
            var source = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(sourceFolder.path(), source.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, source.authenticatedUrl(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var clone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(clone));

            var updatedClone = pool.checkoutAllowStale(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(updatedClone));

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.authenticatedUrl(), "master");

            updatedClone = pool.checkoutAllowStale(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(updatedClone));
        }
    }

    private static String git(Path repo, Path input, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd);
        if (input != null) {
            pb.redirectInput(input.toFile());
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args));
        return output;
    }

    @Test
    void corruptSeed(TestInfo testInfo) throws IOException, InterruptedException {
        try (var credentials = new HostCredentials(testInfo);
             var sourceFolder = new TemporaryDirectory();
             var seedFolder = new TemporaryDirectory();
             var cloneFolder = new TemporaryDirectory();
             var secondCloneFolder = new TemporaryDirectory()) {
            var source = credentials.getHostedRepository();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(sourceFolder.path(), source.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, source.authenticatedUrl(), "master", true);
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.authenticatedUrl(), "master");

            var pool = new HostedRepositoryPool(seedFolder.path());
            var clone = pool.materialize(source, cloneFolder.path());
            clone.checkout(hash, true);
            assertTrue(CheckableRepository.hasBeenEdited(clone));

            // Corrupt the seed by removing an object that is only reachable from history,
            // a fetch will not bring it back
            Path seed;
            try (var seeds = Files.list(seedFolder.path())) {
                seed = seeds.filter(p -> p.getFileName().toString().endsWith(".git")).findAny().orElseThrow();
            }
            var packs = Files.createDirectory(sourceFolder.path().resolve("packs"));
            try (var packFiles = Files.list(seed.resolve("objects/pack"))) {
                for (var packFile : packFiles.toList()) {
                    Files.move(packFile, packs.resolve(packFile.getFileName()));
                }
            }
            try (var packFiles = Files.list(packs)) {
                for (var packFile : packFiles.filter(p -> p.toString().endsWith(".pack")).toList()) {
                    git(seed, packFile, "unpack-objects", "-q");
                }
            }
            var oldTree = git(seed, null, "rev-parse", masterHash.hex() + "^{tree}");
            Files.delete(seed.resolve("objects").resolve(oldTree.substring(0, 2)).resolve(oldTree.substring(2)));
            assertFalse(Repository.get(seed).orElseThrow().isHealthy());

            // A new clone should still be possible
            var secondClone = pool.materialize(source, secondCloneFolder.path().resolve("clone"));
            secondClone.checkout(hash, true);
            assertTrue(CheckableRepository.hasBeenEdited(secondClone));
            secondClone.checkout(masterHash, true);
            assertFalse(CheckableRepository.hasBeenEdited(secondClone));

            // And the corrupt seed should have been replaced
            assertTrue(pool.seedRepository(source, true).isHealthy());
        }
    }
}
