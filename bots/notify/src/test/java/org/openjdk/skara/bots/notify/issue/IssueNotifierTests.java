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
import org.openjdk.skara.vcs.Branch;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.bots.notify.TestUtils.*;
import static org.openjdk.skara.issuetracker.Issue.State.OPEN;
import static org.openjdk.skara.issuetracker.Issue.State.RESOLVED;

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
                                                                                          .put("branches", "master|other")
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
                                     .readyComments(Map.of(reviewer.forge().currentUser().username(), Pattern.compile("This is now ready")))
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
                                     .readyComments(Map.of(reviewer.forge().currentUser().username(), Pattern.compile("This is now ready")))
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

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var jbsNotifierConfig = JSON.object().put("reviews", JSON.object().put("icon", reviewIcon.toString()));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

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

            // And no comments should have been made
            assertEquals(0, issue.comments().size());
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
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
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

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get("customfield_10006").asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
        }
    }

    @Test
    void testIssueBuildAfterMerge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());
            var jbsNotifierConfig2 = JSON.object().put("fixversions", JSON.object())
                                        .put("buildname", "master");
            var notifyBot2 = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig2).create("notify", JSON.object());
            var jbsNotifierConfig3 = JSON.object().put("fixversions", JSON.object())
                                         .put("buildname", "b04");
            var notifyBot3 = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig3).create("notify", JSON.object());
            var jbsNotifierConfig4 = JSON.object().put("fixversions", JSON.object())
                                         .put("buildname", "b02");
            var notifyBot4 = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig4).create("notify", JSON.object());
            var jbsNotifierConfig5 = JSON.object().put("fixversions", JSON.object())
                                         .put("buildname", "b03");
            var notifyBot5 = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig5).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);
            var blankHistory = repo.branchHash("history");

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
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

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get("customfield_10006").asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.url(), "history");
            localRepo.push(blankHistory, repo.url(), "history", true);

            // When the second notifier sees it, it should upgrade the build name
            TestBotRunner.runPeriodicItems(notifyBot2);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("master", updatedIssue.properties().get("customfield_10006").asString());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.url(), "history");
            localRepo.push(blankHistory, repo.url(), "history", true);

            // When the third notifier sees it, it should switch to a build number
            TestBotRunner.runPeriodicItems(notifyBot3);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b04", updatedIssue.properties().get("customfield_10006").asString());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.url(), "history");
            localRepo.push(blankHistory, repo.url(), "history", true);

            // When the fourth notifier sees it, it should switch to a lower build number
            TestBotRunner.runPeriodicItems(notifyBot4);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b02", updatedIssue.properties().get("customfield_10006").asString());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.url(), "history");
            localRepo.push(blankHistory, repo.url(), "history", true);

            // When the fifth notifier sees it, it should NOT switch to a higher build number
            TestBotRunner.runPeriodicItems(notifyBot5);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b02", updatedIssue.properties().get("customfield_10006").asString());
        }
    }

    @Test
    void testIssueBuildAfterTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
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

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get("customfield_10006").asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk-16+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get("customfield_10006").asString());

            // Tag it again
            localRepo.tag(editHash, "jdk-16+10", "Third tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b10", updatedIssue.properties().get("customfield_10006").asString());

            // Tag it once again
            localRepo.tag(editHash, "jdk-16+8", "Fourth tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b08", updatedIssue.properties().get("customfield_10006").asString());
        }
    }

    @Test
    void testIssueBuildAfterTagMultipleBranches(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object()
                                                                         .put("master", "16")
                                                                         .put("other", "16.0.2"))
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.push(current, repo.url(), "other");
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.of("16.0.2"));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.url(), "master");
            localRepo.push(editHash, repo.url(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment in the issue and in a new backport
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var backportIssue = updatedIssue.links().get(0).issue().orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.abbreviate()));

            var backportComments = backportIssue.comments();
            assertEquals(1, backportComments.size());
            var backportComment = backportComments.get(0);
            assertTrue(backportComment.body().contains(editHash.abbreviate()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("16.0.2"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get("customfield_10006").asString());
            assertEquals(Set.of("16"), fixVersions(backportIssue));
            assertEquals("team", backportIssue.properties().get("customfield_10006").asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            assertEquals(RESOLVED, backportIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backportIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk-16+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get("customfield_10006").asString());
            assertEquals("b110", backportIssue.properties().get("customfield_10006").asString());

            // Tag it again
            localRepo.tag(editHash, "jdk-16+10", "Third tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b10", updatedIssue.properties().get("customfield_10006").asString());
            assertEquals("b10", backportIssue.properties().get("customfield_10006").asString());

            // Tag it once again
            localRepo.tag(editHash, "jdk-16+8", "Fourth tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b08", updatedIssue.properties().get("customfield_10006").asString());
            assertEquals("b08", backportIssue.properties().get("customfield_10006").asString());
        }
    }

    @Test
    void testIssueRetryTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
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

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get("customfield_10006").asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk-16+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get("customfield_10006").asString());

            // Tag it again
            localRepo.tag(editHash, "jdk-16+10", "Third tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.url().toString()), "--tags", false);

            // Claim that it is already processed
            localRepo.fetch(repo.url(), "+history:history");
            localRepo.checkout(new Branch("history"), true);
            var historyFile = repoFolder.resolve("test.tags.txt");
            var processed = new ArrayList<>(Files.readAllLines(historyFile, StandardCharsets.UTF_8));
            processed.add("jdk-16+10 issue done");
            Files.writeString(historyFile, String.join("\n", processed), StandardCharsets.UTF_8);
            localRepo.add(historyFile);
            var updatedHash = localRepo.commit("Marking jdk-16+10 as done", "duke", "duke@openjdk.org");
            localRepo.push(updatedHash, repo.url(), "history");

            // Now let the notifier see it
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should not have been updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get("customfield_10006").asString());

            // Flag it as in need of retry
            processed.remove(processed.size() - 1);
            processed.add("jdk-16+10 issue retry");
            Files.writeString(repoFolder.resolve("test.tags.txt"), String.join("\n", processed), StandardCharsets.UTF_8);
            localRepo.add(historyFile);
            var retryHash = localRepo.commit("Marking jdk-16+10 as needing retry", "duke", "duke@openjdk.org");
            localRepo.push(retryHash, repo.url(), "history");

            // Now let the notifier see it
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should have been updated by the retry
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b10", updatedIssue.properties().get("customfield_10006").asString());
        }
    }

    @Test
    void testIssueOtherDomain(TestInfo testInfo) throws IOException {
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
            var authorEmailAddress = issueProject.issueTracker().currentUser().email().orElse(issueProject.issueTracker().currentUser().username() + "@otherjdk.org");
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
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "12.0.1"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("12-pool").add("tbd_major").add("unknown"));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been updated
            assertEquals(Set.of("12.0.1"), fixVersions(issue));
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
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "12.0.1"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("12-pool").add("tbd_major").add("unknown"));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been updated
            assertEquals(Set.of("12.0.1"), fixVersions(issue));
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
                                                        "customfield_10008", JSON.of("java.io")
                                                 ));
            issue.setProperty("fixVersions", JSON.array().add("13.0.1"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
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
            assertEquals("java.io", backport.properties().get("customfield_10008").asString());

            // Labels should not
            assertEquals(0, backport.labels().size());
        }
    }
}
