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

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Tag;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.notify.TestUtils.*;

public class UpdaterTests {
    private static class TestRepositoryListener implements Notifier, RepositoryListener {
        private final String name;
        private final boolean idempotent;
        private int updateCount = 0;
        private boolean shouldFail = false;

        TestRepositoryListener(String name, boolean idempotent) {
            this.name = name;
            this.idempotent = idempotent;
        }

        @Override
        public void onNewCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits,
                                 Branch branch) throws NonRetriableException {
            updateCount++;
            if (shouldFail) {
                if (idempotent) {
                    throw new RuntimeException("induced failure");
                } else {
                    throw new NonRetriableException(new RuntimeException("unretriable failure"));
                }
            }
        }

        @Override
        public void onNewOpenJDKTagCommits(HostedRepository repository, Repository localRepository,
                                           Path scratchPath, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) {
            throw new RuntimeException("unexpected");
        }

        @Override
        public void onNewTagCommit(HostedRepository repository, Repository localRepository, Path scratchPath, Commit commit, Tag tag,
                                   Tag.Annotated annotation) {
            throw new RuntimeException("unexpected");
        }

        @Override
        public void onNewBranch(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits,
                                Branch parent, Branch branch) {
            throw new RuntimeException("unexpected");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean idempotent() {
            return idempotent;
        }

        @Override
        public void attachTo(Emitter e) {
            e.registerRepositoryListener(this);
        }
    }

    @Test
    void testIdempotenceMix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .build();

            var idempotent = new TestRepositoryListener("i", true);
            idempotent.attachTo(notifyBot);

            var nonIdempotent = new TestRepositoryListener("ni", false);
            nonIdempotent.attachTo(notifyBot);

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fix stuff");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Both updaters should have run
            assertEquals(2, idempotent.updateCount);
            assertEquals(2, nonIdempotent.updateCount);

            var nextEditHash = CheckableRepository.appendAndCommit(localRepo, "Yet another line", "Fix more stuff");
            localRepo.push(nextEditHash, repo.url(), "master");

            idempotent.shouldFail = true;
            nonIdempotent.shouldFail = true;
            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(notifyBot));

            // Both updaters should have run again
            assertEquals(3, idempotent.updateCount);
            assertEquals(3, nonIdempotent.updateCount);

            assertThrows(RuntimeException.class, () -> TestBotRunner.runPeriodicItems(notifyBot));

            // But now only the idempotent one should have been retried
            assertEquals(4, idempotent.updateCount);
            assertEquals(3, nonIdempotent.updateCount);
        }
    }
}
