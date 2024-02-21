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
package org.openjdk.skara.bots.notify.notes;

import org.junit.jupiter.api.*;
import org.openjdk.skara.bots.notify.NotifyBot;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openjdk.skara.bots.notify.TestUtils.*;

public class CommitNoteNotiferTests {
    @Test
    void testCommitNote(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .integratorId(repo.forge().currentUser().id())
                                     .build();
            // Register a RepositoryListener to make history initialize on the first run
            notifyBot.registerRepositoryListener(new NullRepositoryListener());
            var notifier = new CommitNoteNotifier(issueProject);
            notifier.attachTo(notifyBot);

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history");

            // "Fake" an integrated pull request
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fix an issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            var pr = credentials.createPullRequest(repo, "master", "master", "Fix an issue");
            pr.setBody("I made a fix");
            pr.addLabel("integrated");
            pr.addComment("More text!\n\n@user Pushed as commit " + editHash.hex() + ". Even more text.\n\nAnd some additional text.");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Check commit note
            var remoteCommit = repo.commit(editHash).orElseThrow();
            localRepo.fetch(repo.authenticatedUrl(), "refs/notes/*:refs/notes/*");
            var note = localRepo.notes(editHash);
            assertEquals(List.of("Commit: " + remoteCommit.webUrl(),
                                 "Review: " + pr.webUrl()),
                         note);
        }
    }

    @Test
    void testCommitNoteWithIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("A title",
                                                 List.of("A description"),
                                                 Map.of("issuetype", JSON.of("Enhancement")));
            var commitMessageTitle = issue.id() + ": A title";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Change", commitMessageTitle);

            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .integratorId(repo.forge().currentUser().id())
                                     .build();
            // Register a RepositoryListener to make history initialize on the first run
            notifyBot.registerRepositoryListener(new NullRepositoryListener());
            var notifier = new CommitNoteNotifier(issueProject);
            notifier.attachTo(notifyBot);

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history");

            // "Fake" a fix
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            var pr = credentials.createPullRequest(repo, "master", "master", commitMessageTitle);
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](https://openjdk.org): The issue");
            pr.addLabel("integrated");
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Check commit note
            var remoteCommit = repo.commit(editHash).orElseThrow();
            localRepo.fetch(repo.authenticatedUrl(), "refs/notes/*:refs/notes/*");
            var note = localRepo.notes(editHash);
            assertEquals(List.of("Commit: " + remoteCommit.webUrl(),
                                 "Review: " + pr.webUrl(),
                                 "Issues:",
                                 "- " + issue.webUrl()),
                         note);
        }
    }
}
