/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.bots.notify.issue.IssueUpdater;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openjdk.skara.bots.notify.UpdaterTests.*;

public class IssueUpdaterTests {
    @Test
    void testIssueIdempotence(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var commitIcon = URI.create("http://www.example.com/commit.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitIcon(commitIcon)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.url(), "history");

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            var pr = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a link
            var links = issue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            assertEquals(commitIcon, link.iconUrl().orElseThrow());
            assertEquals("Commit", link.title().orElseThrow());
            assertEquals(repo.webUrl(editHash), link.uri().orElseThrow());

            // Wipe the history
            localRepo.push(historyState, repo.url(), "history", true);

            // Run it again
            TestBotRunner.runPeriodicItems(notifyBot);

            // There should be no new links
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(1, updatedIssue.links().size());
        }
    }

    @Test
    void testPullRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .prUpdaters(List.of(updater))
                                     .readyLabels(Set.of("rfr"))
                                     .readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("This is now ready")))
                                     .build();

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should not yet contain a link to the PR
            var links = issue.links();
            assertEquals(0, links.size());

            // Just a label isn't enough
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(0, links.size());

            // Neither is just a comment
            pr.removeLabel("rfr");
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(0, links.size());

            // Both are needed
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR
            links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());

            // Add another issue
            var issue2 = issueProject.createIssue("This is another issue", List.of("Yes indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            pr.setBody("\n\n### Issues\n * [" + issue.id() + "](http://www.test.test/): The issue\n * [" + issue2.id() +
                    "](http://www.test2.test/): The second issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Both issues should contain a link to the PR
            var links1 = issue.links();
            assertEquals(1, links1.size());
            assertEquals(pr.webUrl(), links1.get(0).uri().orElseThrow());
            var links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());

            // Drop the first one
            pr.setBody("\n\n### Issues\n * [" + issue2.id() + "](http://www.test2.test/): That other issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Only the second issue should now contain a link to the PR
            links1 = issue.links();
            assertEquals(0, links1.size());
            links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());
        }
    }

    @Test
    void testPullRequestNoReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .prUpdaters(List.of(updater)).readyLabels(Set.of("rfr"))
                                     .readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("This is now ready")))
                                     .build();
            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Add required label
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // And the required comment
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should still not contain a link to the PR
            var links = issue.links();
            assertEquals(0, links.size());
        }
    }

    @Test
    void testPullRequestPROnly(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var updater = IssueUpdater.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(true)
                                      .commitIcon(reviewIcon)
                                      .build();
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile(".*"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .prUpdaters(List.of(updater))
                                     .build();

            // Initialize history
            localRepo.push(localRepo.resolve("master").orElseThrow(), repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "other", "edit", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR
            var links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());

            // Simulate integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            localRepo.push(editHash, repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in another link
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            links = updatedIssue.links();
            assertEquals(2, links.size());

            // Now simulate a merge to another branch
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // No additional link should have been created
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            links = updatedIssue.links();
            assertEquals(2, links.size());
        }
    }
}
