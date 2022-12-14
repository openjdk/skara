/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.bots.notify.TestUtils.createBranchStorage;
import static org.openjdk.skara.bots.notify.TestUtils.createTagStorage;

public class RepositoryWorkItemTests {

    private static class TestNotifier implements RepositoryListener {

        private final List<Tag> newTags = new ArrayList<>();

        @Override
        public void onNewTagCommit(HostedRepository repository, Repository localRepository,
                                   Path scratchPath, Commit commit, Tag tag, Tag.Annotated annotation) {
            newTags.add(tag);
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public boolean idempotent() {
            return true;
        }
    }

    /**
     * Tests that the NotifierBot skips notifying on tags that only show up in
     * pr branches.
     */
    @Test
    void filterTagsInNonPrBranches(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var notifyBot = NotifyBot.newBuilder()
                    .repository(repo)
                    .storagePath(storageFolder)
                    .branches(Pattern.compile("master"))
                    .tagStorageBuilder(tagStorage)
                    .branchStorageBuilder(branchStorage)
                    .integratorId(repo.forge().currentUser().id())
                    .build();
            var testNotifier = new TestNotifier();
            notifyBot.registerRepositoryListener(testNotifier);

            // Create an initial tag to start history tracking. The notifier will never notify the first tag
            var masterHash = localRepo.head();
            localRepo.tag(masterHash, "initial-tag", "Tagging initial tag", "testauthor", "ta@none.none");
            localRepo.push(masterHash, repo.url(), "master", false, true);

            // Run bot to initialize notification history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create a "pr"-branch with a commit in it and tag that commit
            var prBranchHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Change in pr branch");
            localRepo.tag(prBranchHash, "pr-tag", "Tagging change in pr branch", "testauthor", "ta@none.none");
            localRepo.push(prBranchHash, repo.url(), "pr/4711", false, true);

            // Run the bot and verify that notifier is not called
            TestBotRunner.runPeriodicItems(notifyBot);
            assertTrue(testNotifier.newTags.isEmpty(), "Notifier called on pr branch: " + testNotifier.newTags);

            // Create a commit in master branch and tag it
            localRepo.checkout(masterHash);
            var masterTaggedHash = CheckableRepository.appendAndCommit(localRepo, "Master line", "Change in master branch");
            localRepo.tag(masterTaggedHash, "master-tag", "Tagging change in master branch", "testauthor", "ta@none.none");
            localRepo.push(masterTaggedHash, repo.url(), "master", false, true);

            // Run the bot and verify that notifier is called for master branch
            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(testNotifier.newTags.size(), 1, "Notifier not called on master branch: " + testNotifier.newTags);
            assertEquals("master-tag", testNotifier.newTags.get(0).name(), "Notified wrong tag");
        }
    }
}
