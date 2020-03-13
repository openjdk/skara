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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.vcs.Tag;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UpdateHistoryTests {
    private String resetHostedRepository(HostedRepository repository) throws IOException {
        var folder = Files.createTempDirectory("updatehistory");
        var localRepository = Repository.init(folder, repository.repositoryType());
        var firstFile = folder.resolve("first.txt");
        Files.writeString(firstFile, "First file to commit");
        localRepository.add(firstFile);
        var firstCommit = localRepository.commit("First commit", "Duke", "duke@openjdk.java.net");
        localRepository.push(firstCommit, repository.url(), localRepository.defaultBranch().toString(), true);
        return localRepository.defaultBranch().toString();
    }

    private UpdateHistory createHistory(HostedRepository repository, String ref) throws IOException {
        var folder = Files.createTempDirectory("updatehistory");
        var tagStorage = new StorageBuilder<Tag>("tags.txt")
                                       .remoteRepository(repository, ref, "Duke", "duke@openjdk.java.net", "Updated tags");
        var branchStorage = new StorageBuilder<ResolvedBranch>("branches.txt")
                .remoteRepository(repository, ref, "Duke", "duke@openjdk.java.net", "Updated branches");
        return UpdateHistory.create(tagStorage,folder.resolve("tags"), branchStorage, folder.resolve("branches"));
    }

    @Test
    void tagsRetained(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repository = credentials.getHostedRepository();
            var ref = resetHostedRepository(repository);
            var history = createHistory(repository, ref);

            history.addTags(List.of(new Tag("1"), new Tag("2")));

            assertTrue(history.hasTag(new Tag("1")));
            assertTrue(history.hasTag(new Tag("2")));

            var newHistory = createHistory(repository, ref);

            assertTrue(newHistory.hasTag(new Tag("1")));
            assertTrue(newHistory.hasTag(new Tag("2")));
        }
    }

    @Test
    void branchesRetained(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repository = credentials.getHostedRepository();
            var ref = resetHostedRepository(repository);

            var history = createHistory(repository, ref);

            history.setBranchHash(new Branch("1"), "a", new Hash("a"));
            history.setBranchHash(new Branch("2"), "a", new Hash("b"));
            history.setBranchHash(new Branch("1"), "a", new Hash("c"));

            assertEquals(new Hash("c"), history.branchHash(new Branch("1"), "a").orElseThrow());
            assertEquals(new Hash("b"), history.branchHash(new Branch("2"), "a").orElseThrow());

            var newHistory = createHistory(repository, ref);

            assertEquals(new Hash("c"), newHistory.branchHash(new Branch("1"), "a").orElseThrow());
            assertEquals(new Hash("b"), newHistory.branchHash(new Branch("2"), "a").orElseThrow());
        }
    }

    @Test
    void branchesSeparateUpdaters(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repository = credentials.getHostedRepository();
            var ref = resetHostedRepository(repository);

            var history = createHistory(repository, ref);

            history.setBranchHash(new Branch("1"), "a", new Hash("a"));
            history.setBranchHash(new Branch("2"), "a", new Hash("b"));
            history.setBranchHash(new Branch("1"), "b", new Hash("c"));
            history.setBranchHash(new Branch("2"), "a", new Hash("d"));

            assertEquals(new Hash("a"), history.branchHash(new Branch("1"), "a").orElseThrow());
            assertEquals(new Hash("d"), history.branchHash(new Branch("2"), "a").orElseThrow());
            assertEquals(new Hash("c"), history.branchHash(new Branch("1"), "b").orElseThrow());

            var newHistory = createHistory(repository, ref);

            assertEquals(new Hash("a"), newHistory.branchHash(new Branch("1"), "a").orElseThrow());
            assertEquals(new Hash("d"), newHistory.branchHash(new Branch("2"), "a").orElseThrow());
            assertEquals(new Hash("c"), newHistory.branchHash(new Branch("1"), "b").orElseThrow());
        }
    }

    @Test
    void tagsConcurrentModification(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repository = credentials.getHostedRepository();
            var ref = resetHostedRepository(repository);
            var history = createHistory(repository, ref);

            history.addTags(List.of(new Tag("1"), new Tag("2")));

            assertTrue(history.hasTag(new Tag("1")));
            assertTrue(history.hasTag(new Tag("2")));

            var history1 = createHistory(repository, ref);
            assertTrue(history1.hasTag(new Tag("1")));
            assertTrue(history1.hasTag(new Tag("2")));
            assertFalse(history1.hasTag(new Tag("3")));
            assertFalse(history1.hasTag(new Tag("4")));

            var history2 = createHistory(repository, ref);
            assertTrue(history2.hasTag(new Tag("1")));
            assertTrue(history2.hasTag(new Tag("2")));
            assertFalse(history2.hasTag(new Tag("3")));
            assertFalse(history2.hasTag(new Tag("4")));

            history1.addTags(Set.of(new Tag("3")));
            history2.addTags(Set.of(new Tag("4")));

            assertTrue(history1.hasTag(new Tag("3")));
            assertFalse(history1.hasTag(new Tag("4")));
            assertTrue(history2.hasTag(new Tag("3")));
            assertTrue(history2.hasTag(new Tag("4")));
        }
    }
}
