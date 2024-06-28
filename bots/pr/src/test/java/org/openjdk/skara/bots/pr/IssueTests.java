/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.issuetracker.jira.JiraProject.SUBCOMPONENT;

class IssueTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());

            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // No arguments
            pr.addComment("/issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"Command syntax:");
            assertLastCommentContains(pr,  "`/issue");

            // Check that the alias works as well
            pr.addComment("/solves");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"Command syntax:");
            assertLastCommentContains(pr,  "`/solves");

            // Invalid syntax
            pr.addComment("/issue something I guess");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a failure message
            assertLastCommentContains(pr,"Command syntax");

            // Add an issue
            pr.addComment("/issue 1234: An issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Adding additional");

            // Try to remove a not-previously-added issue
            pr.addComment("/issue remove 1235");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a failure message
            assertLastCommentContains(pr,"was not found");

            // Now remove the added one
            pr.addComment("/issue remove 1234");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Removing additional");

            // Add two more issues
            pr.addComment("/issue 12345: Another issue");
            pr.addComment("/issue 123456: Yet another issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Adding additional");

            // Update the description of the first one
            pr.addComment("/issue 12345: This is indeed another issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Updating description");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The commit message preview should contain the additional issues
            var preview = pr.comments().stream()
                            .filter(comment -> comment.body().contains("the commit message for the final commit will be"))
                            .map(Comment::body)
                            .findFirst()
                            .orElseThrow();
            assertTrue(preview.contains("123: This is a pull request"));
            assertTrue(preview.contains("12345: This is indeed another issue"));
            assertTrue(preview.contains("123456: Yet another issue"));

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr,"Pushed as commit");

            // The change should now be present on the master branch
            var pushedFolder = tempFolder.path().resolve("pushed");
            var pushedRepo = Repository.materialize(pushedFolder, author.authenticatedUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // The additional issues should be present in the commit message
            assertEquals(List.of("123: This is a pull request",
                                 "12345: This is indeed another issue",
                                 "123456: Yet another issue",
                                 "",
                                 "Reviewed-by: integrationreviewer1"), headCommit.message());
        }
    }

    @Test
    void multiple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var issue1 = credentials.createIssue(issues, "Main");
            var issue1Number = Integer.parseInt(issue1.id().split("-")[1]);
            var pr = credentials.createPullRequest(author, "master", "edit", issue1Number + ": Main");

            var issue2 = credentials.createIssue(issues, "Second");
            var issue2Number = Integer.parseInt(issue2.id().split("-")[1]);
            var issue3 = credentials.createIssue(issues, "Third");
            var issue3Number = Integer.parseInt(issue3.id().split("-")[1]);

            // Add a single issue with the shorthand syntax
            pr.addComment("/solves " + issue3Number);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Adding additional issue to solves list");
            assertLastCommentContains(pr, ": Third");

            // And remove it
            pr.addComment("/solves delete " + issue3Number);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Removing additional issue from solves list: `" + issue3Number + "`");

            // Add two issues with the shorthand syntax
            pr.addComment("/issue " + issue2.id() + "," + issue3Number);
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should add both
            assertLastCommentContains(pr, "Adding additional issue to issue list");
            assertLastCommentContains(pr, ": Second");
            assertLastCommentContains(pr, ": Third");

            // Update the title of issue2 and issue3
            issue2.setTitle("Second2");
            issue3.setTitle("Third3");
            pr.setBody("update this pr");
            TestBotRunner.runPeriodicItems(prBot);
            // PR body shouldn't contain title mismatch warning
            assertFalse(pr.store().body().contains("Title mismatch between PR and JBS for issue"));

            // Remove one
            pr.addComment("/issue remove " + issue2.id());
            TestBotRunner.runPeriodicItems(prBot);

            assertLastCommentContains(pr, "Removing additional issue from issue list: `" + issue2Number + "`");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The commit message preview should contain the additional issues
            var preview = pr.comments().stream()
                            .filter(comment -> comment.body().contains("the commit message for the final commit will be"))
                            .map(Comment::body)
                            .findFirst()
                            .orElseThrow();
            assertTrue(preview.contains(issue1Number + ": Main"));
            assertTrue(preview.contains(issue3Number + ": Third3"));
            assertFalse(preview.contains("Second"));

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr,"Pushed as commit");

            // The change should now be present on the master branch
            var pushedFolder = tempFolder.path().resolve("pushed");
            var pushedRepo = Repository.materialize(pushedFolder, author.authenticatedUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // The additional issues should be present in the commit message
            assertEquals(List.of(issue1Number + ": Main",
                                 issue3Number + ": Third3",
                                 "",
                                 "Reviewed-by: integrationreviewer1"), headCommit.message());
        }
    }

    @Test
    void invalidCommandAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var external = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var mergeBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue a solves command not as the PR author
            var externalPr = external.pullRequest(pr.id());
            externalPr.addComment("/issue 1234: an issue");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void issueInTitle(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Add an issue
            pr.addComment("/issue 1234: An issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"current title");

            assertEquals("1234: An issue", pr.store().title());

            // Update the issue description
            pr.addComment("/issue 1234: Yes this is an issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"will now be updated");

            assertEquals("1234: Yes this is an issue", pr.store().title());
        }
    }

    @Test
    void issueInBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var issue1 = issues.createIssue("First", List.of("Hello"), Map.of());
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   issue1.id() + ": This is a pull request");

            // First check
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains(issue1.id()));
            assertTrue(pr.store().body().contains("First"));
            assertTrue(pr.store().body().contains("## Issue\n"));

            // Add an extra issue
            var issue2 = issues.createIssue("Second", List.of("There"), Map.of());
            pr.addComment("/issue " + issue2.id() + ": Description");

            // Check that the body was updated
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains(issue1.id()));
            assertTrue(pr.store().body().contains("First"));
            assertTrue(pr.store().body().contains(issue2.id()));
            assertTrue(pr.store().body().contains("Second"));
            assertFalse(pr.store().body().contains("## Issue\n"));
            assertTrue(pr.store().body().contains("## Issues\n"));
        }
    }

    @Test
    void closedIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var issue1 = (TestIssueTrackerIssue) issues.createIssue("First", List.of("Hello"), Map.of());
            issue1.setState(Issue.State.CLOSED);
            issue1.store().properties().put("resolution", JSON.object().put("name", JSON.of("Not an Issue")));
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   issue1.id() + ": This is a pull request");

            // First check
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains(issue1.id()));
            assertTrue(pr.store().body().contains("First"));
            assertTrue(pr.store().body().contains("## Issue\n"));
            assertTrue(pr.store().body().contains("Issue is not open"));
        }
    }

    @Test
    void resolvedIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var issue1 = (TestIssueTrackerIssue) issues.createIssue("First", List.of("Hello"), Map.of());
            issue1.setState(Issue.State.RESOLVED);
            var pr = credentials.createPullRequest(author, "master", "edit",
                    issue1.id() + ": This is a pull request");

            // First check
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains(issue1.id()));
            assertTrue(pr.store().body().contains("First"));
            assertTrue(pr.store().body().contains("## Issue\n"));
            assertTrue(pr.store().body().contains("Consider making this a \"backport pull request\" by setting"));
        }
    }

    @Test
    void closedIssueBackport(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var issue1 = issues.createIssue("First", List.of("Hello"), Map.of());
            issue1.setState(Issue.State.RESOLVED);
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   issue1.id() + ": This is a pull request");
            pr.addLabel("backport");

            // First check
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.store().body().contains(issue1.id()));
            assertTrue(pr.store().body().contains("First"));
            assertTrue(pr.store().body().contains("## Issue\n"));
            assertFalse(pr.store().body().contains("Issue is not open"));
        }
    }

    private static final Pattern addedIssuePattern = Pattern.compile("`(.*)` was successfully created", Pattern.MULTILINE);

    private static IssueTrackerIssue issueFromLastComment(PullRequest pr, IssueProject issueProject) {
        var comments = pr.comments();
        var lastComment = comments.getLast();
        var addedIssueMatcher = addedIssuePattern.matcher(lastComment.body());
        assertTrue(addedIssueMatcher.find(), lastComment.body());
        return issueProject.issue(addedIssueMatcher.group(1)).orElseThrow();
    }

    @Test
    void projectPrefix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issueProject)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Create issues
            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            var issue2 = credentials.createIssue(issueProject, "Issue 2");

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", issue1.id() + ": This is a pull request");
            TestBotRunner.runPeriodicItems(prBot);

            // Add variations of this issue
            pr.addComment("/issue add " + issue2.id().toLowerCase());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Adding additional issue to issue list");

            pr.addComment("/issue remove " + issue2.id().toLowerCase());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Removing additional issue from issue list");

            // Add variations of this issue
            pr.addComment("/issue add " + issue2.id().toUpperCase());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Adding additional issue to issue list");

            pr.addComment("/issue remove " + issue2.id().toUpperCase());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Removing additional issue from issue list");

            // Add variations of this issue
            pr.addComment("/issue add " + issue2.id().split("-")[1]);
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"Adding additional issue to issue list");
        }
    }

    @Test
    void multipleIssuesInBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var issueProject = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issueProject)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            var issue3 = credentials.createIssue(issueProject, "Issue 3");

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Pull request title",
                     List.of("/issue add " + issue1.id(),
                             "/issue add " + issue2.id(),
                             "/issue add " + issue3.id()));

            TestBotRunner.runPeriodicItems(prBot);

            // The first issue should be the title
            assertTrue(pr.title().startsWith(issue1.id().split("-")[1] + ": "));

            var comments = pr.comments();
            assertEquals(4, comments.size());

            assertTrue(comments.get(1).body().contains("current title does not contain an issue reference"));
            assertTrue(comments.get(2).body().contains("Adding additional issue to"));
            assertTrue(comments.get(3).body().contains("Adding additional issue to"));
        }
    }

    @Test
    void issueMissing(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with a non-existing issue ID
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a PR");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // There should be no commit preview message
            var previewComment = pr.comments().stream()
                    .map(Comment::body)
                    .filter(body -> body.contains("the commit message for the final commit will be"))
                    .findFirst();
            assertEquals(Optional.empty(), previewComment, "Preview comment should not have been posted");
            // Body should contain integration blocker
            assertTrue(pr.store().body().contains("Integration blocker"), "Body does not report integration blocker");
            assertTrue(pr.store().body().contains("Failed to retrieve information on issue `123`"),
                    "Body does not contain specific message");
        }
    }
}
