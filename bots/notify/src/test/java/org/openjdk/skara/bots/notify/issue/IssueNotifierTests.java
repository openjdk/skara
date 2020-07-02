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
package org.openjdk.skara.bots.notify.issue;

import org.junit.jupiter.api.*;
import org.openjdk.skara.bots.notify.NotifyBot;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.notify.TestUtils.*;
import static org.openjdk.skara.issuetracker.Issue.State.*;

public class IssueNotifierTests {
    private Set<String> fixVersions(Issue issue) {
        if (!issue.properties().containsKey("fixVersions")) {
            return Set.of();
        }
        return issue.properties().get("fixVersions").stream()
                    .map(JSONValue::asString)
                    .collect(Collectors.toSet());
    }

    private TestBotFactory testBotBuilder(HostedRepository hostedRepository, IssueProject issueProject, Path storagePath, JSONObject notifierConfig) throws IOException {
        if (!notifierConfig.contains("project")) {
            notifierConfig.put("project", "issueproject");
        }
        return TestBotFactory.newBuilder()
                             .addHostedRepository("hostedrepo", hostedRepository)
                             .addIssueProject("issueproject", issueProject)
                             .storagePath(storagePath)
                             .addConfiguration("database", JSON.object()
                                                               .put("repository", "hostedrepo:history")
                                                               .put("name", "duke")
                                                               .put("email", "duke@openjdk.org"))
                             .addConfiguration("ready", JSON.object()
                                                            .put("labels", JSON.array())
                                                            .put("comments", JSON.array()))
                             .addConfiguration("integrator", JSON.of(hostedRepository.forge().currentUser().id()))
                             .addConfiguration("repositories", JSON.object()
                                                                   .put("hostedrepo", JSON.object()
                                                                                          .put("basename", "test")
                                                                                          .put("branches", "master")
                                                                                          .put("issue", notifierConfig)))
                             .build();
    }

    @Test
    void testIssueLinkIdempotence(TestInfo testInfo) throws IOException {
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

            var issueProject = credentials.getIssueProject();
            var commitIcon = URI.create("http://www.example.com/commit.png");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .integratorId(repo.forge().currentUser().id())
                                     .build();
            var updater = IssueNotifier.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .commitIcon(commitIcon)
                                      .build();
            updater.attachTo(notifyBot);

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
            pr.addLabel("integrated");
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
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("This is now ready")))
                                     .build();
            var updater = IssueNotifier.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .build();
            updater.attachTo(notifyBot);

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
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .readyComments(Map.of(reviewer.forge().currentUser().userName(), Pattern.compile("This is now ready")))
                                     .build();
            var updater = IssueNotifier.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewLink(false)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(false)
                                      .build();
            updater.attachTo(notifyBot);

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
            var prStateStorage = createPullRequestStateStorage(repo);
            var storageFolder = tempFolder.path().resolve("storage");

            var issueProject = credentials.getIssueProject();
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile(".*"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prStateStorageBuilder(prStateStorage)
                                     .integratorId(repo.forge().currentUser().id())
                                     .build();
            var updater = IssueNotifier.newBuilder()
                                      .issueProject(issueProject)
                                      .reviewIcon(reviewIcon)
                                      .commitLink(true)
                                      .commitIcon(reviewIcon)
                                      .build();
            updater.attachTo(notifyBot);

            // Initialize history
            localRepo.push(localRepo.resolve("master").orElseThrow(), repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "other", "edit", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR
            var links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());

            // Simulate integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            pr.addLabel("integrated");
            pr.setState(Issue.State.CLOSED);
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

    @Test
    void testIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object());
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().userName() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // As well as a fixVersion
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
        }
    }

    @Test
    void testIssueNoVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object());
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // But not in the fixVersion
            assertEquals(Set.of(), fixVersions(issue));
        }
    }

    @Test
    void testIssueConfiguredVersionNoCommit(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "2.0"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should not reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // As well as a fixVersion - but not the one from the repo
            assertEquals(Set.of("2.0"), fixVersions(issue));

            // And no commit link
            var links = issue.links();
            assertEquals(0, links.size());
        }
    }

    @Test
    void testIssueIdempotence(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object());
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.url(), "history");

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // As well as a fixVersion
            assertEquals(Set.of("0.1"), fixVersions(issue));

            // Wipe the history
            localRepo.push(historyState, repo.url(), "history", true);

            // Run it again
            TestBotRunner.runPeriodicItems(notifyBot);

            // There should be no new comments or fixVersions
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(1, updatedIssue.comments().size());
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
        }
    }

    @Test
    void testIssuePoolVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "12u14"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("12-pool").add("tbd13").add("unknown"));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been updated
            assertEquals(Set.of("12u14"), fixVersions(issue));
        }
    }

    @Test
    void testIssuePoolOpenVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "12u14"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("12-pool").add("tbd13").add("unknown"));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been updated
            assertEquals(Set.of("12u14"), fixVersions(issue));
        }
    }

    @Test
    void testIssueBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "12.0.2"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                                                 Map.of("issuetype", JSON.of("Enhancement"),
                                                        "customfield_10008", JSON.object()
                                                                                 .put("id", 244)
                                                                                 .put("name", "java.io"),
                                                        "customfield_10005", JSON.array()
                                                                                 .add(JSON.object()
                                                                                          .put("id", "17010")
                                                                                          .put("value", "generic"))
                                                                                 .add(JSON.object()
                                                                                          .put("id", "17019")
                                                                                          .put("value", "other"))
                                                 ));
            issue.setProperty("fixVersions", JSON.array().add("13.0.1"));
            issue.setProperty("priority", JSON.of("1"));

            var authorEmailAddress = issueProject.issueTracker().currentUser().userName() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("13.0.1"), fixVersions(updatedIssue));
            assertEquals(OPEN, updatedIssue.state());
            assertEquals(List.of(), updatedIssue.assignees());

            // There should be a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The backport issue should have a correct fixVersion and assignee
            assertEquals(Set.of("12.0.2"), fixVersions(backport));
            assertEquals(RESOLVED, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

            // Custom properties should also propagate
            assertEquals("1", backport.properties().get("priority").asString());
            assertEquals(244, backport.properties().get("customfield_10008").get("id").asInt());
            assertEquals("java.io", backport.properties().get("customfield_10008").get("name").asString());
            assertEquals(2, backport.properties().get("customfield_10005").asArray().size());
        }
    }

    @Test
    void testSyncLabels(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "8u192"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize database
            TestBotRunner.runPeriodicItems(notifyBot);

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("issuetype", JSON.of("Bug"));

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u162")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue1.addLink(Link.create(issue2, "backported by").build());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("10")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue1.addLink(Link.create(issue3, "backported by").build());

            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("11")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue1.addLink(Link.create(issue4, "backported by").build());

            // Mention one of the issues
            var commit = CheckableRepository.appendAndCommit(localRepo, "Hello there", issue1.id() + ": A fix");
            localRepo.push(commit, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            assertEquals(List.of("hgupdater-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdater-sync"), issue4.labels());
        }
    }
}
