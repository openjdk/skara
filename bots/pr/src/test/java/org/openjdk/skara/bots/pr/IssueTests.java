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
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

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
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
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
                            .filter(comment -> comment.body().contains("the commit message will be"))
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
            var pushedRepo = Repository.materialize(pushedFolder, author.url(), "master");
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
                                      .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            var issue1 = credentials.createIssue(issues, "First");
            var issue1Number = Integer.parseInt(issue1.id().split("-")[1]);
            var issue2 = credentials.createIssue(issues, "Second");
            var issue2Number = Integer.parseInt(issue2.id().split("-")[1]);

            // Add a single issue with the shorthand syntax
            pr.addComment("/solves " + issue2Number);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Adding additional issue to solves list");
            assertLastCommentContains(pr, ": Second");

            // And remove it
            pr.addComment("/solves delete " + issue2Number);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Removing additional issue from solves list: `" + issue2Number + "`");

            // Add two issues with the shorthand syntax
            pr.addComment("/issue " + issue1.id() + "," + issue2Number);
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should add both
            assertLastCommentContains(pr, "Adding additional issue to issue list");
            assertLastCommentContains(pr, ": First");
            assertLastCommentContains(pr, ": Second");

            // Remove one
            pr.addComment("/issue remove " + issue1.id());
            TestBotRunner.runPeriodicItems(prBot);

            assertLastCommentContains(pr, "Removing additional issue from issue list: `" + issue1Number + "`");

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The commit message preview should contain the additional issues
            var preview = pr.comments().stream()
                            .filter(comment -> comment.body().contains("the commit message will be"))
                            .map(Comment::body)
                            .findFirst()
                            .orElseThrow();
            assertTrue(preview.contains("123: This is a pull request"));
            assertTrue(preview.contains(issue2Number + ": Second"));
            assertFalse(preview.contains("First"));

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr,"Pushed as commit");

            // The change should now be present on the master branch
            var pushedFolder = tempFolder.path().resolve("pushed");
            var pushedRepo = Repository.materialize(pushedFolder, author.url(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            var headCommit = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);

            // The additional issues should be present in the commit message
            assertEquals(List.of("123: This is a pull request",
                                 "2: Second",
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
            var mergeBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
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
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Add an issue
            pr.addComment("/issue 1234: An issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"current title");

            var updatedPr = author.pullRequest(pr.id());
            assertEquals("1234: An issue", updatedPr.title());

            // Update the issue description
            pr.addComment("/issue 1234: Yes this is an issue");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"will now be updated");

            updatedPr = author.pullRequest(pr.id());
            assertEquals("1234: Yes this is an issue", updatedPr.title());
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
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).issueProject(issues).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var issue1 = issues.createIssue("First", List.of("Hello"), Map.of());
            var pr = credentials.createPullRequest(author, "master", "edit",
                                                   issue1.id() + ": This is a pull request");

            // First check
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.body().contains(issue1.id()));
            assertTrue(pr.body().contains("First"));
            assertTrue(pr.body().contains("## Issue\n"));

            // Add an extra issue
            var issue2 = issues.createIssue("Second", List.of("There"), Map.of());
            pr.addComment("/issue " + issue2.id() + ": Description");

            // Check that the body was updated
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.body().contains(issue1.id()));
            assertTrue(pr.body().contains("First"));
            assertTrue(pr.body().contains(issue2.id()));
            assertTrue(pr.body().contains("Second"));
            assertFalse(pr.body().contains("## Issue\n"));
            assertTrue(pr.body().contains("## Issues\n"));
        }
    }

    private static final Pattern addedIssuePattern = Pattern.compile("`(.*)` was successfully created", Pattern.MULTILINE);

    private static Issue issueFromLastComment(PullRequest pr, IssueProject issueProject) {
        var comments = pr.comments();
        var lastComment = comments.get(comments.size() - 1);
        var addedIssueMatcher = addedIssuePattern.matcher(lastComment.body());
        assertTrue(addedIssueMatcher.find(), lastComment.body());
        return issueProject.issue(addedIssueMatcher.group(1)).orElseThrow();
    }

    @Test
    void createIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).issueProject(issues).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");
            pr.setBody("This is the body");

            // Create an issue
            pr.addComment("/issue create hotspot");
            TestBotRunner.runPeriodicItems(prBot);

            // Verify it
            var issue = issueFromLastComment(pr, issues);
            assertEquals("This is a pull request", issue.title());
            assertEquals("hotspot", issue.properties().get("components").asArray().get(0).asString());
            assertEquals("This is the body", issue.body());

            var updatedPr = author.pullRequest(pr.id());
            var issueNr = issue.id().split("-", 2)[1];
            assertEquals(issueNr + ": This is a pull request", updatedPr.title());
        }
    }

    @Test
    void createIssueParameterized(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder().repo(integrator).censusRepo(censusBuilder.build()).issueProject(issues).build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Create an issue
            pr.addComment("/issue create P2 hotspot");
            TestBotRunner.runPeriodicItems(prBot);

            // Verify it
            var issue = issueFromLastComment(pr, issues);
            assertEquals("This is a pull request", issue.title());
            assertEquals("hotspot", issue.properties().get("components").asArray().get(0).asString());
            assertEquals("2", issue.properties().get("priority").asString());

            // Reset and try some more
            pr.setTitle("This is another pull request");

            // Create an issue
            pr.addComment("/issue create P4 enhancement hotspot");
            TestBotRunner.runPeriodicItems(prBot);

            // Verify it
            issue = issueFromLastComment(pr, issues);
            assertEquals("This is another pull request", issue.title());
            assertEquals("hotspot", issue.properties().get("components").asArray().get(0).asString());
            assertEquals("4", issue.properties().get("priority").asString());
            assertEquals("enhancement", issue.properties().get("issuetype").asString().toLowerCase());

            // Reset and try some more
            pr.setTitle("This is yet another pull request");

            // Create an issue
            pr.addComment("/issue create new feature core-libs java.io");
            TestBotRunner.runPeriodicItems(prBot);

            // Verify it
            issue = issueFromLastComment(pr, issues);
            assertEquals("This is yet another pull request", issue.title());
            assertEquals("core-libs", issue.properties().get("components").asArray().get(0).asString());
            assertEquals("new feature", issue.properties().get("issuetype").asString().toLowerCase());
            assertEquals("java.io", issue.properties().get("customfield_10008").asString());
        }
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
                                      .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Create issues
            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            var issue2 = credentials.createIssue(issueProject, "Issue 2");

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
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
}
