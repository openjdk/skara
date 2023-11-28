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
import java.util.List;
import java.util.stream.*;
import java.util.logging.Logger;

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.vcs.*;

class IntegrityVerifier {
    private final Repository repo;
    private final URI remote;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    IntegrityVerifier(Repository repo, URI remote) {
        this.repo = repo;
        this.remote = remote;
    }

    void verifyPullRequestTarget(PullRequest pr) throws IOException {
        var targetHeadHash = pr.repository().branchHash(pr.targetRef()).orElseThrow();
        var targetHeadCommit = pr.repository().commit(targetHeadHash).orElseThrow();
        verifyBranch(pr.repository().name(), pr.targetRef(), targetHeadCommit);
    }

    void verifyBranch(String repositoryName, String branchName, Commit current) throws IOException {
        var integrityBranch = repositoryName + "-" + branchName;

        var branches = repo.remoteBranches(remote).stream().map(Reference::name).collect(Collectors.toSet());
        if (!branches.contains(integrityBranch)) {
            var masterHead = repo.fetch(remote, "master", false);
            repo.checkout(masterHead);
            var heads = repo.root().resolve("heads.txt");
            var content = List.of(current.hash().hex(), current.parents().get(0).hex());
            Files.write(heads, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            repo.add(heads);
            var head = repo.commit("Initialize heads.txt with '" + current.hash().hex() + "' for " + repositoryName + ":" + branchName,
                                   "duke",
                                   "duke@openjdk.org");
            repo.push(head, remote, integrityBranch);
            return;
        }

        var latest = repo.fetch(remote, integrityBranch, false);
        repo.checkout(latest);
        var heads = repo.root().resolve("heads.txt");
        var lines = Files.readAllLines(heads);
        if (lines.size() != 2) {
            throw new IllegalStateException("Corrupt heads.txt file for branch " + integrityBranch);
        }
        var expected = new Hash(lines.get(0));
        if (!expected.equals(current.hash())) {
            var firstParent = new Hash(lines.get(1));
            if (firstParent.equals(current.hash())) {
                // The bot must have crashed between pushing to the integrity repo and to the target repo,
                // recover integrity repository
                log.info("Resetting heads.txt from '" + expected + "' to '" + current.hash() + "'");
                var content = List.of(current.hash().hex(), current.parents().get(0).hex());
                Files.write(heads, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                repo.add(heads);
                var head = repo.commit("Resetting heads.txt from '" + expected + "' to '" + current.hash() + "'",
                                       "duke",
                                       "duke@openjdk.org");
                repo.push(head, remote, integrityBranch.toString());
            } else {
                var msg = "Expected HEAD in branch " + branchName + " in repo " + repositoryName +
                          " to be '" + expected.hex() + "', but it was '"  + current.hash().hex() + "'";
                log.severe(msg);
                throw new IllegalArgumentException(msg);
            }
        }
    }

    void updateBranch(String repositoryName, String branchName, Commit next) throws IOException {
        var integrityBranch = repositoryName + "-" + branchName;
        var latest = repo.fetch(remote, integrityBranch);
        repo.checkout(latest);
        var heads = repo.root().resolve("heads.txt");
        var lines = Files.readAllLines(heads);
        if (lines.size() != 2) {
            throw new IllegalStateException("Corrupt " + heads + " file for branch " + integrityBranch);
        }
        var current = new Hash(lines.get(0));

        var content = List.of(next.hash().hex(), next.parents().get(0).hex());
        Files.write(heads, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        repo.add(heads);
        var head = repo.commit("Updating from '" + current + "' to '" + next  + "'",
                               "duke",
                               "duke@openjdk.org");
        repo.push(head, remote, integrityBranch);
    }

    void updatePullRequestTarget(PullRequest pr, Commit next) throws IOException {
        updateBranch(pr.repository().name(), pr.targetRef(), next);
    }
}
