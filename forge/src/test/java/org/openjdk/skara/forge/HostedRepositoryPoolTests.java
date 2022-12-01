/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Optional;

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
            localRepo.push(masterHash, source.url(), "master", true);

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.url(), "master");

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
            localRepo.push(masterHash, source.url(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var bareClone = pool.materializeBare(source, cloneFolder.path());

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.url(), "master");

            // The commit should not appear from this
            bareClone = pool.materializeBare(source, cloneFolder.path());
            var bareCommit = bareClone.lookup(hash);
            assertEquals(Optional.empty(), bareCommit);

            // But should be possible to fetch
            bareClone.fetchAll(source.url());
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
            localRepo.push(masterHash, source.url(), "master", true);

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
            localRepo.push(masterHash, source.url(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var clone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(clone));

            var updatedClone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(updatedClone));

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.url(), "master");

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
            localRepo.push(masterHash, source.url(), "master", true);

            var pool = new HostedRepositoryPool(seedFolder.path());
            var clone = pool.checkout(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(clone));

            var updatedClone = pool.checkoutAllowStale(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(updatedClone));

            // Push something else
            var hash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(hash, source.url(), "master");

            updatedClone = pool.checkoutAllowStale(source, "master", cloneFolder.path());
            assertFalse(CheckableRepository.hasBeenEdited(updatedClone));
        }
    }
}
