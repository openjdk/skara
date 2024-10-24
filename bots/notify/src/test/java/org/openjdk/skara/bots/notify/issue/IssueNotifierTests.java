/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.bots.notify.CommitFormatters;
import org.openjdk.skara.bots.notify.NotifyBot;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Branch;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.notify.TestUtils.*;
import static org.openjdk.skara.issuetracker.Issue.State.OPEN;
import static org.openjdk.skara.issuetracker.Issue.State.RESOLVED;
import static org.openjdk.skara.issuetracker.jira.JiraProject.RESOLVED_IN_BUILD;
import static org.openjdk.skara.issuetracker.jira.JiraProject.SUBCOMPONENT;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

import static org.openjdk.skara.bots.common.PullRequestConstants.*;

public class IssueNotifierTests {
    private static final String pullRequestTip = "A pull request was submitted for review.";

    private Set<String> fixVersions(IssueTrackerIssue issue) {
        if (!issue.properties().containsKey("fixVersions")) {
            return Set.of();
        }
        return issue.properties().get("fixVersions").stream()
                    .map(JSONValue::asString)
                    .collect(Collectors.toSet());
    }

    private TestBotFactory.TestBotFactoryBuilder testBotBuilderFactory(HostedRepository hostedRepository, IssueProject issueProject, Path storagePath, JSONObject notifierConfig) throws IOException {
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
                                                                                          .put("branches", "master|other|other2")
                                                                                          .put("issue", notifierConfig)));
    }

    private TestBotFactory testBotBuilder(HostedRepository hostedRepository, IssueProject issueProject, Path storagePath, JSONObject notifierConfig) throws IOException {
        return testBotBuilderFactory(hostedRepository, issueProject, storagePath, notifierConfig).build();
    }

    @Test
    void testIssueLinkIdempotence(TestInfo testInfo) throws IOException {
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
            // Register a RepositoryListener to make history initialize on the first run
            notifyBot.registerRepositoryListener(new NullRepositoryListener());
            updater.attachTo(notifyBot);

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
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
            assertEquals("Commit(master)", link.title().orElseThrow());
            assertEquals(repo.webUrl(editHash), link.uri().orElseThrow());

            // Wipe the history
            localRepo.push(historyState, repo.authenticatedUrl(), "history", true);

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
            localRepo.pushAll(repo.authenticatedUrl());

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
            localRepo.push(editHash, repo.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should not yet contain a link to the PR or a comment which contains the link to the PR
            var links = issue.links();
            assertEquals(0, links.size());
            var comments = issue.comments();
            assertEquals(0, comments.size());

            // Just a label isn't enough
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(0, links.size());
            comments = issue.comments();
            assertEquals(0, comments.size());

            // Neither is just a comment
            pr.removeLabel("rfr");
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(0, links.size());
            comments = issue.comments();
            assertEquals(0, comments.size());

            // Both are needed
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR and a comment which contains the link to the PR
            links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());
            comments = issue.comments();
            assertEquals(1, comments.size());
            assertTrue(comments.get(0).body().contains(pullRequestTip));
            assertTrue(comments.get(0).body().contains(pr.webUrl().toString()));

            // Add another issue
            var issue2 = issueProject.createIssue("This is another issue", List.of("Yes indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            pr.setBody("\n\n### Issues\n * [" + issue.id() + "](http://www.test.test/): The issue\n * [" + issue2.id() +
                    "](http://www.test2.test/): The second issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Both issues should contain a link to the PR and a comment which contains the link to the PR
            var links1 = issue.links();
            assertEquals(1, links1.size());
            assertEquals(pr.webUrl(), links1.get(0).uri().orElseThrow());
            var comments1 = issue.comments();
            assertEquals(1, comments1.size());
            assertTrue(comments1.get(0).body().contains(pullRequestTip));
            assertTrue(comments1.get(0).body().contains(pr.webUrl().toString()));

            var links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());
            var comments2 = issue2.comments();
            assertEquals(1, comments2.size());
            assertTrue(comments2.get(0).body().contains(pullRequestTip));
            assertTrue(comments2.get(0).body().contains(pr.webUrl().toString()));

            // Drop the first one
            pr.setBody("\n\n### Issues\n * [" + issue2.id() + "](http://www.test2.test/): That other issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Only the second issue should now contain a link to the PR and a comment which contains the link to the PR
            links1 = issue.links();
            assertEquals(0, links1.size());
            comments1 = issue.comments();
            assertEquals(0, comments1.size());

            links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());
            comments2 = issue2.comments();
            assertEquals(1, comments2.size());
            assertTrue(comments2.get(0).body().contains(pullRequestTip));
            assertTrue(comments2.get(0).body().contains(pr.webUrl().toString()));

            // test line separator "\r"
            pr.setBody("\r\r### Issues\r * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Only the first issue should now contain a link to the PR and a comment which contains the link to the PR
            links1 = issue.links();
            assertEquals(1, links1.size());
            assertEquals(pr.webUrl(), links1.get(0).uri().orElseThrow());
            comments1 = issue.comments();
            assertEquals(1, comments1.size());
            assertTrue(comments1.get(0).body().contains(pullRequestTip));
            assertTrue(comments1.get(0).body().contains(pr.webUrl().toString()));

            links2 = issue2.links();
            assertEquals(0, links2.size());
            comments2 = issue2.comments();
            assertEquals(0, comments2.size());

            // test line separator "\r\n"
            pr.setBody("\r\n\r\n### Issues\r\n * [" + issue2.id() + "](http://www.test2.test/): That other issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Only the second issue should now contain a link to the PR and a comment which contains the link to the PR
            links1 = issue.links();
            assertEquals(0, links1.size());
            comments1 = issue.comments();
            assertEquals(0, comments1.size());

            links2 = issue2.links();
            assertEquals(1, links2.size());
            assertEquals(pr.webUrl(), links2.get(0).uri().orElseThrow());
            comments2 = issue2.comments();
            assertEquals(1, comments2.size());
            assertTrue(comments2.get(0).body().contains(pullRequestTip));
            assertTrue(comments2.get(0).body().contains(pr.webUrl().toString()));
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
            localRepo.pushAll(repo.authenticatedUrl());

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
            localRepo.push(editHash, repo.authenticatedUrl(), "edit", true);
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

            // The issue should still not contain a link to the PR or a comment which contains the link to the PR
            var links = issue.links();
            assertEquals(0, links.size());
            var comments = issue.comments();
            assertEquals(1, comments.size());
            assertTrue(comments.get(0).body().contains(pullRequestTip));
            assertTrue(comments.get(0).body().contains(pr.webUrl().toString()));
        }
    }

    @Test
    void testCsrIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, JSON.object()).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and its csr issue.
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var csrIssue = issueProject.createIssue("This is a csr issue", List.of("Indeed"), Map.of("issuetype", JSON.of("CSR")));
            issue.addLink(Link.create(csrIssue, "csr for").build());
            var withdrawnCsrIssue = issueProject.createIssue("This is a withdrawn csr issue", List.of("Indeed"), Map.of("issuetype", JSON.of("CSR")));
            issue.addLink(Link.create(withdrawnCsrIssue, "csr for").build());

            // Push a commit and create a pull request
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line",
                            issue.id() + ": This is an issue\n", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": This is an issue");
            pr.setBody("\n\n### Issues\n" +
                    " * [" + issue.id() + "](http://www.test.test/): This is an issue\n" +
                    " * [" + csrIssue.id() + "](http://www.test2.test/): This is a csr issue (**CSR**)\n" +
                    " * [" + withdrawnCsrIssue.id() + "](http://www.test3.test/): This is a withdrawn csr issue (**CSR**) (Withdrawn)\n"
            );
            pr.addLabel("rfr");
            pr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Get the issues.
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var updatedCsrIssue = issueProject.issue(csrIssue.id()).orElseThrow();
            var updatedWithdrawnCsrIssue = issueProject.issue(withdrawnCsrIssue.id()).orElseThrow();

            // Non-csr issue should have the PR link and PR comment.
            var issueLinks = updatedIssue.links();
            assertEquals(3, issueLinks.size());
            assertEquals("csr for", issueLinks.get(0).relationship().orElseThrow());
            assertEquals("csr for", issueLinks.get(1).relationship().orElseThrow());
            assertEquals(pr.webUrl(), issueLinks.get(2).uri().orElseThrow());

            var issueComments = updatedIssue.comments();
            assertEquals(1, issueComments.size());
            assertTrue(issueComments.get(0).body().contains(pullRequestTip));
            assertTrue(issueComments.get(0).body().contains(pr.webUrl().toString()));

            // csr issue shouldn't have the PR link or PR comment.
            var csrIssueLinks = updatedCsrIssue.links();
            assertEquals(1, csrIssueLinks.size());
            assertEquals("csr of", csrIssueLinks.get(0).relationship().orElseThrow());

            var csrIssueComments = updatedCsrIssue.comments();
            assertEquals(0, csrIssueComments.size());

            // Withdrawn csr issue shouldn't have the PR link or PR comment.
            var withdrawnCsrIssueLinks = updatedWithdrawnCsrIssue.links();
            assertEquals(1, withdrawnCsrIssueLinks.size());
            assertEquals("csr of", withdrawnCsrIssueLinks.get(0).relationship().orElseThrow());

            var withdrawnCsrIssueComments = updatedWithdrawnCsrIssue.comments();
            assertEquals(0, withdrawnCsrIssueComments.size());
        }
    }

    @Test
    void testJepIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, JSON.object()).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a jep issue.
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Issue body"),
                    Map.of("issuetype", JSON.of("Enhancement")));
            var jepIssue = issueProject.createIssue("This is a jep", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Submitted"), JEP_NUMBER, JSON.of("123")));

            // Push a commit and create a pull request
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line",
                    issue.id() + ": This is an issue\n", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": This is an issue");
            pr.setBody("\n\n### Issues\n" +
                    " * [" + issue.id() + "](http://www.test.test/): This is an issue\n" +
                    " * [" + jepIssue.id() + "](http://www.test2.test/): This is a jep (**JEP**)");
            pr.addLabel("rfr");
            pr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Get the issues.
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var updatedJepIssue = issueProject.issue(jepIssue.id()).orElseThrow();

            // Non-jep issue should have the PR link and PR comment.
            var issueLinks = updatedIssue.links();
            assertEquals(1, issueLinks.size());
            assertEquals(pr.webUrl(), issueLinks.get(0).uri().orElseThrow());

            var issueComments = updatedIssue.comments();
            assertEquals(1, issueComments.size());
            assertTrue(issueComments.get(0).body().contains(pullRequestTip));
            assertTrue(issueComments.get(0).body().contains(pr.webUrl().toString()));

            // jep issue shouldn't have the PR link or PR comment.
            var jepIssueLinks = updatedJepIssue.links();
            assertEquals(0, jepIssueLinks.size());

            var jepIssueComments = updatedJepIssue.comments();
            assertEquals(0, jepIssueComments.size());
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
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var reviewIcon = URI.create("http://www.example.com/review.png");
            var jbsNotifierConfig = JSON.object().put("reviews", JSON.object().put("icon", reviewIcon.toString()));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            localRepo.push(localRepo.resolve("master").orElseThrow(), repo.authenticatedUrl(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and a pull request to fix it
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(repo, "other", "edit", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The issue should now contain a link to the PR and a comment which contains the link to the PR
            var links = issue.links();
            assertEquals(1, links.size());
            assertEquals(pr.webUrl(), links.get(0).uri().orElseThrow());
            assertEquals(reviewIcon, links.get(0).iconUrl().orElseThrow());
            var comments = issue.comments();
            assertEquals(1, comments.size());
            assertTrue(comments.get(0).body().contains(pullRequestTip));
            assertTrue(comments.get(0).body().contains(pr.webUrl().toString()));

            // Simulate integration
            pr.addComment("Pushed as commit " + editHash.hex() + ".");
            pr.addLabel("integrated");
            pr.setState(Issue.State.CLOSED);
            localRepo.push(editHash, repo.authenticatedUrl(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in another link
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            links = updatedIssue.links();
            assertEquals(2, links.size());

            // The issue should only contain a comment which contains the link to the PR
            comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            assertTrue(comments.get(0).body().contains(pullRequestTip));
            assertTrue(comments.get(0).body().contains(pr.webUrl().toString()));

            // Now simulate a merge to another branch
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // No additional link should have been created
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            links = updatedIssue.links();
            assertEquals(2, links.size());

            // The issue should only contain a comment which contains the link to the PR
            comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            assertTrue(comments.get(0).body().contains(pullRequestTip));
            assertTrue(comments.get(0).body().contains(pr.webUrl().toString()));
        }
    }

    @Test
    void testMultipleIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue1 = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var issue2 = issueProject.createIssue("This is another issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var issue3 = issueProject.createIssue("This is yet another issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line",
                                                               issue1.id() + ": Fix that issue\n" +
                                                                       issue1.id() + ": Fix that issue\n" +
                                                                       issue2.id() + ": And fix the other issue\n" +
                                                                       issue3.id() + ": As well as this one\n",
                                                               "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue1 = issueProject.issue(issue1.id()).orElseThrow();
            var updatedIssue2 = issueProject.issue(issue2.id()).orElseThrow();
            var updatedIssue3 = issueProject.issue(issue3.id()).orElseThrow();

            var comments1 = updatedIssue1.comments();
            assertEquals(1, comments1.size());
            var comment1 = comments1.get(0);
            assertTrue(comment1.body().contains(editHash.toString()));
            var comments2 = updatedIssue2.comments();
            assertEquals(1, comments2.size());
            var comment2 = comments2.get(0);
            assertTrue(comment2.body().contains(editHash.toString()));
            var comments3 = updatedIssue3.comments();
            assertEquals(1, comments3.size());
            var comment3 = comments3.get(0);
            assertTrue(comment3.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue1));
            assertEquals("team", updatedIssue1.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue2));
            assertEquals("team", updatedIssue2.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue1.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue1.assignees());
            assertEquals(RESOLVED, updatedIssue2.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue2.assignees());
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
            localRepo.pushAll(repo.authenticatedUrl());

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
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

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
            localRepo.pushAll(repo.authenticatedUrl());

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
            var blankHistory = repo.branchHash("history").orElseThrow();

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();
            localRepo.push(blankHistory, repo.authenticatedUrl(), "history", true);

            // When the second notifier sees it, it should upgrade the build name
            TestBotRunner.runPeriodicItems(notifyBot2);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("master", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();
            localRepo.push(blankHistory, repo.authenticatedUrl(), "history", true);

            // When the third notifier sees it, it should switch to a build number
            TestBotRunner.runPeriodicItems(notifyBot3);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b04", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();
            localRepo.push(blankHistory, repo.authenticatedUrl(), "history", true);

            // When the fourth notifier sees it, it should switch to a lower build number
            TestBotRunner.runPeriodicItems(notifyBot4);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b02", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Restore the history to simulate looking at another repository
            localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();
            localRepo.push(blankHistory, repo.authenticatedUrl(), "history", true);

            // When the fifth notifier sees it, it should NOT switch to a higher build number
            TestBotRunner.runPeriodicItems(notifyBot5);

            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b02", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
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
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "16"))
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("16"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk-16+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it again
            localRepo.tag(editHash, "jdk-16+10", "Third tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b10", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it once again
            localRepo.tag(editHash, "jdk-16+8", "Fourth tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b08", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
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
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object()
                            .put("master", "16-foo")
                            .put("other", "16.0.2"))
                    .put("buildname", "team")
                    .put("tag", JSON.object().put("ignoreopt", JSON.array().add("foo")));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.push(current, repo.authenticatedUrl(), "other");
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.of("16.0.2"));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            localRepo.push(editHash, repo.authenticatedUrl(), "other");
            // Add an extra branch that is not configured with any fixVersion
            localRepo.push(editHash, repo.authenticatedUrl(), "extra");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment in the issue and in a new backport
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var backportIssue = updatedIssue.links().get(0).issue().orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            var backportComments = backportIssue.comments();
            assertEquals(1, backportComments.size());
            var backportComment = backportComments.get(0);
            assertTrue(backportComment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("16.0.2"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals(Set.of("16-foo"), fixVersions(backportIssue));
            assertEquals("team", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            assertEquals(RESOLVED, backportIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backportIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk-16+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b110", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
            // But not in the update backport
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it again
            localRepo.tag(editHash, "jdk-16+10", "Third tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b10", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it once again
            localRepo.tag(editHash, "jdk-16+8", "Fourth tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b08", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
        }
    }

    @Test
    void testTagIgnorePrefixAndOpt(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object()
                            .put("master", "foo16-bar"))
                    .put("buildname", "team")
                    .put("tag", JSON.object().put("ignoreopt", JSON.array().add("bar")));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.push(current, repo.authenticatedUrl(), "other");
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.of("foo16-bar"));
            issue.setState(RESOLVED);
            issue.setProperty(RESOLVED_IN_BUILD, JSON.of("master"));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");

            // Tag it
            localRepo.tag(editHash, "16+1", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b01", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
        }
    }

    @Test
    void testIssueBuildAfterTagOpenjdk8u(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object()
                            .put("master", "openjdk8u352")
                            .put("other", "openjdk8u342"))
                    .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.push(current, repo.authenticatedUrl(), "other");
            localRepo.tag(current, "jdk8u342-b00", "First tag", "duke", "duke@openjdk.org");
            localRepo.tag(current, "jdk9u352-b00", "First unrelated tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.of("openjdk8u352"));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            localRepo.push(editHash, repo.authenticatedUrl(), "other");
            // Add an extra branch that is not configured with any fixVersion
            localRepo.push(editHash, repo.authenticatedUrl(), "extra");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment in the issue and in a new backport
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var backportIssue = updatedIssue.links().get(0).issue().orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            var backportComments = backportIssue.comments();
            assertEquals(1, backportComments.size());
            var backportComment = backportComments.get(0);
            assertTrue(backportComment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("openjdk8u352"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals(Set.of("openjdk8u342"), fixVersions(backportIssue));
            assertEquals("team", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            assertEquals(RESOLVED, backportIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backportIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk8u342-b01", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b01", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
            // But not in the update backport
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it with an unrelated tag
            localRepo.tag(editHash, "jdk9u352-b01", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should not change
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b01", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
            // But not in the update backport
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
        }
    }

    @Test
    void testIssueBuildAfterTagJdk8uSuffix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object()
                            .put("maste.", "8u341")
                            .put("othe.", "8u341-foo"))
                    .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.push(current, repo.authenticatedUrl(), "other");
            localRepo.tag(current, "jdk8u341-b00", "First tag", "duke", "duke@openjdk.org");
            localRepo.tag(current, "jdk8u341-foo-b00", "First foo tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.of("8u341"));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            localRepo.push(editHash, repo.authenticatedUrl(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment in the issue and in a new backport
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var backportIssue = updatedIssue.links().get(0).issue().orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            var backportComments = backportIssue.comments();
            assertEquals(1, backportComments.size());
            var backportComment = backportComments.get(0);
            assertTrue(backportComment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("8u341"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals(Set.of("8u341-foo"), fixVersions(backportIssue));
            assertEquals("team", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            assertEquals(RESOLVED, backportIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backportIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk8u341-b01", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b01", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
            // But not in the update backport
            assertEquals("team", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it with a properly formatted tag for the foo version
            localRepo.tag(editHash, "jdk8u341-foo-b01", "Second foo tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b01", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
        }
    }

    /**
     * Tests the optional functionality of requiring a version prefix to be matched
     * when evaluating tags against fixVersions
     */
    @Test
    void testIssueBuildAfterTagJdk8uPrefix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object()
                            .put("master", "8u341")
                            .put("other", "foo8u341"))
                    .put("buildname", "team")
                    .put("tag", JSON.object()
                            .put("matchprefix", true));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.push(current, repo.authenticatedUrl(), "other");
            localRepo.tag(current, "jdk8u341-b00", "First tag", "duke", "duke@openjdk.org");
            localRepo.tag(current, "foo8u341-b00", "First foo tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.of("8u341"));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            localRepo.push(editHash, repo.authenticatedUrl(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment in the issue and in a new backport
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            var backportIssue = updatedIssue.links().get(0).issue().orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            var backportComments = backportIssue.comments();
            assertEquals(1, backportComments.size());
            var backportComment = backportComments.get(0);
            assertTrue(backportComment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("8u341"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
            assertEquals(Set.of("foo8u341"), fixVersions(backportIssue));
            assertEquals("team", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            assertEquals(RESOLVED, backportIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backportIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk8u341-b01", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The notifier requires prefix to be matched, but the default tag prefix of "jdk"
            // can't be overridden, so fixVersion "8u341" does still match tag "jdk8u341-b01".
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b01", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
            // But not in the update backport
            assertEquals("team", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it with a properly formatted tag for the foo version
            localRepo.tag(editHash, "foo8u341-b02", "Second foo tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            backportIssue = issueProject.issue(backportIssue.id()).orElseThrow();
            assertEquals("b02", backportIssue.properties().get(RESOLVED_IN_BUILD).asString());
            // And the main issue should stay the same
            assertEquals("b01", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
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
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "16"))
                                        .put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.tag(current, "jdk-16+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("16"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // Tag it
            localRepo.tag(editHash, "jdk-16+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should now be updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Tag it again
            localRepo.tag(editHash, "jdk-16+10", "Third tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);

            // Claim that it is already processed
            localRepo.fetch(repo.authenticatedUrl(), "+history:history").orElseThrow();
            localRepo.checkout(new Branch("history"), true);
            var historyFile = repoFolder.resolve("test.tags.txt");
            var processed = new ArrayList<>(Files.readAllLines(historyFile));
            processed.add("jdk-16+10 issue done");
            Files.writeString(historyFile, String.join("\n", processed));
            localRepo.add(historyFile);
            var updatedHash = localRepo.commit("Marking jdk-16+10 as done", "duke", "duke@openjdk.org");
            localRepo.push(updatedHash, repo.authenticatedUrl(), "history");

            // Now let the notifier see it
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should not have been updated
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b110", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // Flag it as in need of retry
            processed.removeLast();
            processed.add("jdk-16+10 issue retry");
            Files.writeString(repoFolder.resolve("test.tags.txt"), String.join("\n", processed));
            localRepo.add(historyFile);
            var retryHash = localRepo.commit("Marking jdk-16+10 as needing retry", "duke", "duke@openjdk.org");
            localRepo.push(retryHash, repo.authenticatedUrl(), "history");

            // Now let the notifier see it
            TestBotRunner.runPeriodicItems(notifyBot);

            // The build should have been updated by the retry
            updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals("b10", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());
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
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(issueProject.issueTracker().currentUser().username());

            var storageFolder = tempFolder.path().resolve("storage");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                                        .put("census", JSON.of("census:master"))
                                        .put("namespace", "test");
            var notifyBotFactory = testBotBuilderFactory(repo, issueProject, storageFolder, jbsNotifierConfig);
            notifyBotFactory.addHostedRepository("census", censusBuilder.build());
            var notifyBot = notifyBotFactory.build().create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = "integrationreviewer1@otherjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

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
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object());
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // But not in the fixVersion
            assertEquals(Set.of(), fixVersions(issue));
        }
    }

    @Test
    void testIssueHeadVersion(TestInfo testInfo) throws IOException {
        headVersionHelper(testInfo, true);
    }
    @Test
    void testIssueHeadVersionFalse(TestInfo testInfo) throws IOException {
        headVersionHelper(testInfo, false);
    }

    private void headVersionHelper(TestInfo testInfo, boolean useHeadVersion) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), "1");
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());
            var baseHash = localRepo.resolve("HEAD").orElseThrow();

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object()
                    .put("fixversions", JSON.object())
                    .put("headversion", JSON.of(useHeadVersion));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");

            // Update the fix version in a change parallel to the fix and then merge them together
            localRepo.checkout(baseHash);
            var jcheckConfFile = repoFolder.resolve(".jcheck/conf");
            var jcheckConfContents = Files.readAllLines(jcheckConfFile).stream()
                    .map(l -> l.startsWith("version=") ? "version=2" : l)
                    .toList();
            Files.write(jcheckConfFile, jcheckConfContents);
            localRepo.add(jcheckConfFile);
            var newVersionHash = localRepo.commit("Update fixversion", "testauthor", "ta@none.none");
            localRepo.checkout(new Branch("master"));
            localRepo.merge(newVersionHash);
            var mergeHash = localRepo.commit("Merge", "testauthor", "ta@none.none");
            localRepo.push(mergeHash, repo.authenticatedUrl(), "master");

            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // The fixVersion should be 1 or 2 depending useHeadVersion
            if (useHeadVersion) {
                assertEquals(Set.of("2"), fixVersions(issue));
            } else {
                assertEquals(Set.of("1"), fixVersions(issue));
            }
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
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "2.0"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should not reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

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
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object());
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The changeset should be reflected in a comment
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));

            // As well as a fixVersion
            assertEquals(Set.of("0.1"), fixVersions(issue));

            // Wipe the history
            localRepo.push(historyState, repo.authenticatedUrl(), "history", true);

            // Run it again
            TestBotRunner.runPeriodicItems(notifyBot);

            // There should be no new comments or fixVersions
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(1, updatedIssue.comments().size());
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
        }
    }

    /**
     * The format used for commit URLs in bug comments and elsewhere was changed
     * to use the full hash instead of an abbreviated hash. This test verifies
     * that the idempotence of the IssueNotifier holds true even when
     * encountering old bug comments containing the old commit URL format.
     */
    @Test
    void testIssueIdempotenceOldUrlFormat(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object());
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");

            // Add a comment for the fix with the old url hash format
            var lastCommit = localRepo.commits().stream().findFirst().orElseThrow();
            issue.addComment(CommitFormatters.toTextBrief(repo, lastCommit, new Branch("master")).replace(editHash.toString(), editHash.abbreviate()));
            TestBotRunner.runPeriodicItems(notifyBot);

            // Verify that the planted comment is still the only one
            var comments = issue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            // We expect the abbreviated hash in the planted comment
            assertTrue(comment.body().contains(editHash.abbreviate()));

            // As well as a fixVersion
            assertEquals(Set.of("0.1"), fixVersions(issue));

            // Wipe the history
            localRepo.push(historyState, repo.authenticatedUrl(), "history", true);

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
            localRepo.pushAll(repo.authenticatedUrl());

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
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
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
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "12.0.2"));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            var level = issue.properties().get("security");
            issue.setProperty("fixVersions", JSON.array().add("13.0.1"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
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
            assertEquals("java.io", backport.properties().get(SUBCOMPONENT).asString());
            assertFalse(backport.properties().containsKey(RESOLVED_IN_BUILD));

            // Labels should not
            assertEquals(0, backport.labelNames().size());
        }
    }

    @Test
    void testIssueBackportDefaultSecurity(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());
            // Initialize other branches
            var initialHead = localRepo.head();
            localRepo.push(initialHead, repo.authenticatedUrl(), "other");
            localRepo.push(initialHead, repo.authenticatedUrl(), "other2");

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object()
                    .put("fixversions", JSON.object()
                            .put(".*aster", "20.0.2")
                            .put("other", "20.0.1")
                            .put("other2", "19.0.2"))
                    .put("defaultsecurity", JSON.object()
                            .put("othe.*", "100"));

            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            var level = issue.properties().get("security");
            issue.setProperty("fixVersions", JSON.array().add("21"));
            issue.setProperty("priority", JSON.of("1"));

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            {
                // The fixVersion should not have been updated
                var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
                assertEquals(Set.of("21"), fixVersions(updatedIssue));
                assertEquals(OPEN, updatedIssue.state());
                assertEquals(List.of(), updatedIssue.assignees());

                // There should be a link
                var links = updatedIssue.links();
                assertEquals(1, links.size());
                var link = links.get(0);
                var backport = link.issue().orElseThrow();

                // The backport issue should have a correct fixVersion and no security
                assertEquals(Set.of("20.0.2"), fixVersions(backport));
                assertNull(backport.properties().get("security"));
            }

            // Push the fix to other branch
            localRepo.push(editHash, repo.authenticatedUrl(), "other");
            TestBotRunner.runPeriodicItems(notifyBot);

            {
                // Find the new backport
                var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
                var links = updatedIssue.links();
                assertEquals(2, links.size());
                var backport = links.get(1).issue().orElseThrow();

                // The backport issue should have a correct fixVersion and security
                assertEquals(Set.of("20.0.1"), fixVersions(backport));
                assertEquals("100", backport.properties().get("security").asString());
            }

            // Set security on the original issue
            issue.setProperty("security", JSON.of("200"));
            // Push to another branch
            localRepo.push(editHash, repo.authenticatedUrl(), "other2");
            TestBotRunner.runPeriodicItems(notifyBot);

            {
                // Find the new backport
                var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
                var links = updatedIssue.links();
                assertEquals(3, links.size());
                var backport = links.get(2).issue().orElseThrow();

                // The backport issue should have a correct fixVersion and security
                assertEquals(Set.of("19.0.2"), fixVersions(backport));
                assertEquals("200", backport.properties().get("security").asString());
            }
        }
    }

    @Test
    void testIssueOriginalRepo(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {

            var repo = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var issueProject = credentials.getIssueProject();
            var storageFolder = tempFolder.path().resolve("storage");
            var originalRepo = credentials.getHostedRepository("original");
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object())
                    .put("buildname", "team")
                    .put("originalrepository", "original")
                    .put("repoonly", JSON.of(true));
            var testBotFactoryBuilder = testBotBuilderFactory(repo, issueProject, storageFolder, jbsNotifierConfig);
            testBotFactoryBuilder.addHostedRepository("original", originalRepo);
            var notifyBot = testBotFactoryBuilder.build().create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            // Also create a pull request that should not get processed due to repoonly being set
            localRepo.push(editHash, repo.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(repo, "edit", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            pr.addLabel("rfr");
            var reviewPr = reviewer.pullRequest(pr.id());
            reviewPr.addComment("This is now ready");
            TestBotRunner.runPeriodicItems(notifyBot);

            // No PR link should have been added
            var links = issue.links();
            assertEquals(0, links.size());

            // The changeset should be reflected in a comment
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();

            var comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));
            // Verify that the 'original' repo URL is used in the comment and not the main one
            assertTrue(comment.body().contains(originalRepo.url().toString()));
            assertFalse(comment.body().contains(repo.url().toString()));

            // As well as a fixVersion and a resolved in build
            assertEquals(Set.of("0.1"), fixVersions(updatedIssue));
            assertEquals("team", updatedIssue.properties().get(RESOLVED_IN_BUILD).asString());

            // The issue should be assigned and resolved
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
        }
    }

    @Test
    void testAltFixVersionsNoMatch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "jdk-cpu"));
            jbsNotifierConfig.put("altfixversions", JSON.object().put("master", JSON.array().add("18")));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("17"));
            issue.setState(RESOLVED);

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("17"), fixVersions(updatedIssue));
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(), updatedIssue.assignees());

            // There should be a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The backport issue should have a correct fixVersion and assignee
            assertEquals(Set.of("jdk-cpu"), fixVersions(backport));
            assertEquals(RESOLVED, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

        }
    }

    @Test
    void testAltFixVersionsMatch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "jdk-cpu"));
            jbsNotifierConfig.put("altfixversions", JSON.object().put("master", JSON.array().add("18")));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("18"));
            issue.setState(RESOLVED);

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("18"), fixVersions(updatedIssue));
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            // A commit comment should have been added
            List<Comment> comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));
            assertTrue(comment.body().contains(repo.url().toString()));

            // There should be no link
            var links = updatedIssue.links();
            assertEquals(0, links.size());
        }
    }

    @Test
    void testAltFixVersionsMatchRegex(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put("master", "jdk-cpu"));
            jbsNotifierConfig.put("altfixversions", JSON.object().put("m.*", JSON.array().add("1[78]")));
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement")));
            issue.setProperty("fixVersions", JSON.array().add("18"));
            issue.setState(RESOLVED);

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("18"), fixVersions(updatedIssue));
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            // A commit comment should have been added
            List<Comment> comments = updatedIssue.comments();
            assertEquals(1, comments.size());
            var comment = comments.get(0);
            assertTrue(comment.body().contains(editHash.toString()));
            assertTrue(comment.body().contains(repo.url().toString()));

            // There should be no link
            var links = updatedIssue.links();
            assertEquals(0, links.size());
        }
    }

    @Test
    void testIssueBackportWithTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "12.0.2")).put("buildname", "team");
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            var current = localRepo.resolve("master").orElseThrow();
            localRepo.tag(current, "jdk-12.0.2+9", "First tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            var level = issue.properties().get("security");
            issue.setProperty("fixVersions", JSON.array().add("13.0.1"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");

            // Tag it
            localRepo.tag(editHash, "jdk-12.0.2+110", "Second tag", "duke", "duke@openjdk.org");
            localRepo.push(new Branch(repo.authenticatedUrl().toString()), "--tags", false);
            TestBotRunner.runPeriodicItems(notifyBot);

            // Same RepositoryWorkItem handles both tag and the commit
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
            assertEquals("java.io", backport.properties().get(SUBCOMPONENT).asString());

            // Labels should not
            assertEquals(0, backport.labelNames().size());

            // Resolved in Build should be updated
            assertEquals("b110", backport.properties().get(RESOLVED_IN_BUILD).asString());
        }
    }

    @Test
    void testFailedIssue(TestInfo testInfo) throws IOException {
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
            var reviewIcon = URI.create("http://www.example.com/review.png");
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
                    .reviewIcon(reviewIcon)
                    .commitLink(false)
                    .build();
            // Register a RepositoryListener to make history initialize on the first run
            notifyBot.registerRepositoryListener(new NullRepositoryListener());
            updater.attachTo(notifyBot);

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            var pr = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");
            pr.setBody("\n\n### Issue\n * " + "⚠️ Temporary failure when trying to retrieve information on issue `" + issue.id() + "`." + TEMPORARY_ISSUE_FAILURE_MARKER);
            pr.addLabel("rfr");
            TestBotRunner.runPeriodicItems(notifyBot);

            // There should not be any links in the issue
            var links = issue.links();
            assertEquals(0, links.size());

            //Resolve the temporary issue failure
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);
            links = issue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            assertEquals(reviewIcon, link.iconUrl().orElseThrow());
            assertEquals("Review(master)", link.title().orElseThrow());
            assertEquals(pr.webUrl(), link.uri().orElseThrow());

            // Wipe the history
            localRepo.push(historyState, repo.authenticatedUrl(), "history", true);

            TestBotRunner.runPeriodicItems(notifyBot);

            // There should be no new links
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(1, updatedIssue.links().size());
        }
    }

    @Test
    void testAvoidForwardports(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "22")).put("avoidforwardports", true);
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            issue.setProperty("fixVersions", JSON.array().add("21"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should have been set to 22
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("22"), fixVersions(updatedIssue));
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());

            // There should be a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The backport issue should have the issue's fixVersions
            assertEquals(Set.of("21"), fixVersions(backport));
            assertEquals(OPEN, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

            // Custom properties should also propagate
            assertEquals("1", backport.properties().get("priority").asString());
            assertEquals("java.io", backport.properties().get(SUBCOMPONENT).asString());
            assertFalse(backport.properties().containsKey(RESOLVED_IN_BUILD));

            // Labels should not
            assertEquals(0, backport.labelNames().size());
        }
    }

    @Test
    void testAvoidForwardportsShouldCreateBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "21")).put("avoidforwardports", true);
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            issue.setProperty("fixVersions", JSON.array().add("22"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("22"), fixVersions(updatedIssue));
            assertEquals(OPEN, updatedIssue.state());
            assertEquals(List.of(), updatedIssue.assignees());

            // There should be a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The backport issue should have the repository's fixVersions
            assertEquals(Set.of("21"), fixVersions(backport));
            assertEquals(RESOLVED, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

            // Custom properties should also propagate
            assertEquals("1", backport.properties().get("priority").asString());
            assertEquals("java.io", backport.properties().get(SUBCOMPONENT).asString());
            assertFalse(backport.properties().containsKey(RESOLVED_IN_BUILD));

            // Labels should not
            assertEquals(0, backport.labelNames().size());
        }
    }

    @Test
    void testAvoidForwardportsShouldUseExistingForwardport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "22")).put("avoidforwardports", true);
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            issue.setProperty("fixVersions", JSON.array().add("21"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            // Create an explicit "forwardport"
            var forwardPort = issueProject.createIssue("This is a forwardport", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Backport")));
            forwardPort.setProperty("fixVersions", JSON.array().add("22"));

            issue.addLink(Link.create(forwardPort, "backported by").build());
            forwardPort.addLink(Link.create(issue, "backport of").build());

            // Commit a fix for the issue
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("21"), fixVersions(updatedIssue));
            assertEquals(OPEN, updatedIssue.state());
            assertEquals(List.of(), updatedIssue.assignees());

            // There should still be just a single link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The forwardport issue should have the repository's fixVersions
            assertEquals(Set.of("22"), fixVersions(backport));
            assertEquals(RESOLVED, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

            // No properties should have propagated
            assertFalse(backport.properties().containsKey(SUBCOMPONENT));
            assertFalse(backport.properties().containsKey(RESOLVED_IN_BUILD));

            // Not Labels should have propagated
            assertEquals(0, backport.labelNames().size());
        }
    }

    @Test
    void testAvoidForwardportsShouldUseExistingBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "21")).put("avoidforwardports", true);
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            issue.setProperty("fixVersions", JSON.array().add("22"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");

            // Create an explicit backport
            var backport = issueProject.createIssue("This is a backport", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Backport")));
            backport.setProperty("fixVersions", JSON.array().add("21"));

            issue.addLink(Link.create(backport, "backported by").build());
            backport.addLink(Link.create(issue, "backport of").build());

            // Commit a fix for the issue
            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion should not have been updated
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("22"), fixVersions(updatedIssue));
            assertEquals(OPEN, updatedIssue.state());
            assertEquals(List.of(), updatedIssue.assignees());

            // There should still be just a single link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var updatedBackport = link.issue().orElseThrow();

            // The backport issue should have the repository's fixVersions
            assertEquals(Set.of("21"), fixVersions(updatedBackport));
            assertEquals(RESOLVED, updatedBackport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedBackport.assignees());

            // No properties should have propagated
            assertFalse(updatedBackport.properties().containsKey(SUBCOMPONENT));
            assertFalse(updatedBackport.properties().containsKey(RESOLVED_IN_BUILD));

            // Not Labels should have propagated
            assertEquals(0, updatedBackport.labelNames().size());
        }
    }

    @Test
    void testAvoidForwardportsOnResolvedIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType(), Path.of("appendable.txt"), Set.of(), null);
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.authenticatedUrl());

            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var jbsNotifierConfig = JSON.object().put("fixversions", JSON.object().put(".*aster", "22")).put("avoidforwardports", true);
            var notifyBot = testBotBuilder(repo, issueProject, storageFolder, jbsNotifierConfig).create("notify", JSON.object());

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"),
                    Map.of("issuetype", JSON.of("Enhancement"),
                            SUBCOMPONENT, JSON.of("java.io"),
                            RESOLVED_IN_BUILD, JSON.of("b07")
                    ));
            issue.setProperty("fixVersions", JSON.array().add("21"));
            issue.setProperty("priority", JSON.of("1"));
            issue.addLabel("test");
            issue.addLabel("temporary");
            issue.setState(RESOLVED);
            issue.setProperty("resolution", JSON.object().put("name", JSON.of("Delivered")));
            issue.setAssignees(List.of(issueProject.issueTracker().currentUser()));

            var authorEmailAddress = issueProject.issueTracker().currentUser().username() + "@openjdk.org";
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue", "Duke", authorEmailAddress);
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The fixVersion of the main issue should still be 21
            var updatedIssue = issueProject.issue(issue.id()).orElseThrow();
            assertEquals(Set.of("21"), fixVersions(updatedIssue));
            assertEquals(RESOLVED, updatedIssue.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), updatedIssue.assignees());
            // The resolution of the issue should still be "Delivered"
            assertEquals("Delivered", issue.properties().get("resolution").get("name").asString());

            // There should be a link
            var links = updatedIssue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            var backport = link.issue().orElseThrow();

            // The backport issue should target to release 22
            assertEquals(Set.of("22"), fixVersions(backport));
            assertEquals(RESOLVED, backport.state());
            assertEquals(List.of(issueProject.issueTracker().currentUser()), backport.assignees());

            // Custom properties should also propagate
            assertEquals("1", backport.properties().get("priority").asString());
            assertEquals("java.io", backport.properties().get(SUBCOMPONENT).asString());
            assertFalse(backport.properties().containsKey(RESOLVED_IN_BUILD));

            // Labels should not
            assertEquals(0, backport.labelNames().size());
        }
    }

    @Test
    void testTargetBranchUpdate(TestInfo testInfo) throws IOException {
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
            var reviewIcon = URI.create("http://www.example.com/review.png");
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
                    .reviewLink(true)
                    .reviewIcon(reviewIcon)
                    .build();
            // Register a RepositoryListener to make history initialize on the first run
            notifyBot.registerRepositoryListener(new NullRepositoryListener());
            updater.attachTo(notifyBot);

            // Initialize history
            TestBotRunner.runPeriodicItems(notifyBot);

            // Save the state
            var historyState = localRepo.fetch(repo.authenticatedUrl(), "history").orElseThrow();

            // Create an issue and commit a fix
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.authenticatedUrl(), "master");
            var pr = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");
            pr.addLabel("rfr");
            pr.setBody("\n\n### Issue\n * [" + issue.id() + "](http://www.test.test/): The issue");
            TestBotRunner.runPeriodicItems(notifyBot);

            // There should be a review link
            var links = issue.links();
            assertEquals(1, links.size());
            var link = links.get(0);
            assertEquals(reviewIcon, link.iconUrl().orElseThrow());
            assertEquals("Review(master)", link.title().orElseThrow());
            assertEquals(pr.webUrl(), link.uri().orElseThrow());
            assertTrue(issue.comments().getLast().body().contains("Branch: master"));

            // Retarget the pr
            pr.setTargetRef("jdk23");
            TestBotRunner.runPeriodicItems(notifyBot);

            // The review link should be updated
            links = issue.links();
            assertEquals(1, links.size());
            link = links.get(0);
            assertEquals(reviewIcon, link.iconUrl().orElseThrow());
            assertEquals("Review(jdk23)", link.title().orElseThrow());
            assertEquals(pr.webUrl(), link.uri().orElseThrow());
            assertTrue(issue.comments().getLast().body().contains("Branch: jdk23"));
        }
    }
}
