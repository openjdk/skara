/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.test.TemporaryDirectory;

class IntegrityTests {
    @BeforeEach
    void disableConfig() {
        Repository.ignoreConfiguration();
    }

    private static Repository createRepo(TemporaryDirectory tmp) throws IOException {
        var dir = tmp.path().resolve("repo");
        return Repository.init(dir, VCS.GIT);
    }

    private static Commit createCommit(Repository repo) throws IOException {
        var f = repo.root().resolve("README.txt");
        Files.write(f, "This is just to create a commit\n".getBytes(), WRITE, APPEND, CREATE);
        repo.add(f);
        var hash = repo.commit("A commit", "duke", "duke@openjdk.org");
        return repo.lookup(hash).orElseThrow();
    }

    private static Repository createRemoteIntegrityRepo(TemporaryDirectory tmp) throws IOException {
        var dir = tmp.path().resolve("remote");
        var repo = Repository.init(dir, VCS.GIT);
        Files.write(dir.resolve(".git").resolve("config"),
                    List.of("[receive]", "denyCurrentBranch=ignore"),
                    WRITE, APPEND);
        createCommit(repo);
        return repo;
    }

    private static Repository createLocalIntegrityRepo(TemporaryDirectory tmp) throws IOException {
        var dir = tmp.path().resolve("local");
        var repo = Repository.init(dir, VCS.GIT);
        return repo;
    }

    @Test
    void missingIntegrityBranch() throws Exception {
        try (var dir = new TemporaryDirectory(false)) {
            var local = createLocalIntegrityRepo(dir);
            var remote = createRemoteIntegrityRepo(dir);

            var repo = createRepo(dir);
            var current = createCommit(repo);

            var verifier = new IntegrityVerifier(local, remote.root().toUri());
            verifier.verifyBranch("repo", "branch", current);

            var integrityBranch = new Branch("repo-branch");
            assertTrue(remote.branches().contains(integrityBranch));

            var head = local.fetch(remote.root().toUri(), integrityBranch.name());
            local.checkout(head);
            var heads = Files.readAllLines(local.root().resolve("heads.txt"));
            assertEquals(List.of(current.hash().hex(), current.parents().get(0).hex()), heads);
        }
    }

    @Test
    void verifyingIsIdempotent() throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var local = createLocalIntegrityRepo(dir);
            var remote = createRemoteIntegrityRepo(dir);

            var repo = createRepo(dir);
            var current = createCommit(repo);

            var verifier = new IntegrityVerifier(local, remote.root().toUri());
            verifier.verifyBranch("repo", "branch", current);
            verifier.verifyBranch("repo", "branch", current);

            var integrityBranch = new Branch("repo-branch");
            assertTrue(remote.branches().contains(integrityBranch));

            var head = local.fetch(remote.root().toUri(), integrityBranch.name());
            local.checkout(head);
            var heads = Files.readAllLines(local.root().resolve("heads.txt"));
            assertEquals(List.of(current.hash().hex(), current.parents().get(0).hex()), heads);
        }
    }

    @Test
    void updateIntegrityBranch() throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var local = createLocalIntegrityRepo(dir);
            var remote = createRemoteIntegrityRepo(dir);

            var repo = createRepo(dir);
            var current = createCommit(repo);
            var next = createCommit(repo);

            var verifier = new IntegrityVerifier(local, remote.root().toUri());

            verifier.verifyBranch("repo", "branch", current);
            verifier.updateBranch("repo", "branch", next);

            // Next should now be "current", so should be able to verify
            verifier.verifyBranch("repo", "branch", next);

            var integrityBranch = new Branch("repo-branch");
            assertTrue(remote.branches().contains(integrityBranch));

            var head = local.fetch(remote.root().toUri(), integrityBranch.name());
            local.checkout(head);
            var heads = Files.readAllLines(local.root().resolve("heads.txt"));
            assertEquals(List.of(next.hash().hex(), current.hash().hex()), heads);
        }
    }

    @Test
    void recoverAbortedPush() throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var local = createLocalIntegrityRepo(dir);
            var remote = createRemoteIntegrityRepo(dir);

            var repo = createRepo(dir);
            var current = createCommit(repo);
            var next = createCommit(repo);

            var verifier = new IntegrityVerifier(local, remote.root().toUri());

            verifier.verifyBranch("repo", "branch", current);
            verifier.updateBranch("repo", "branch", next);

            // Simulate a integration being aborted after updateBranch has been
            // called but before "git push" has been executed
            var unpushed = createCommit(repo);
            verifier.verifyBranch("repo", "branch", next);
            verifier.updateBranch("repo", "branch", unpushed);

            // The verifier should now *not* throw when verifying next
            // (since next is the parent of unpushed)
            verifier.verifyBranch("repo", "branch", next);

            var integrityBranch = new Branch("repo-branch");
            assertTrue(remote.branches().contains(integrityBranch));

            var head = local.fetch(remote.root().toUri(), integrityBranch.name());
            local.checkout(head);
            var heads = Files.readAllLines(local.root().resolve("heads.txt"));
            assertEquals(List.of(next.hash().hex(), current.hash().hex()), heads);
        }
    }

    @Test
    void unexpectedTargetHeadThrows() throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var local = createLocalIntegrityRepo(dir);
            var remote = createRemoteIntegrityRepo(dir);

            var repo = createRepo(dir);
            var current = createCommit(repo);
            var next = createCommit(repo);

            var verifier = new IntegrityVerifier(local, remote.root().toUri());

            verifier.verifyBranch("repo", "branch", current);
            verifier.updateBranch("repo", "branch", next);

            var unexpected = createCommit(repo);
            assertThrows(IllegalArgumentException.class,
                         () -> { verifier.verifyBranch("repo", "branch", unexpected); });
        }
    }
}
