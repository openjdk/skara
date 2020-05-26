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
package org.openjdk.skara.bots.pr;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class ReviewerTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var extra = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addAuthor(extra.forge().currentUser().id())
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/reviewer hello");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"Syntax");

            // Add a reviewer
            pr.addComment("/reviewer add @" + integrator.forge().currentUser().userName());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should now consider the PR ready
            assertLastCommentContains(pr,"This change now passes all automated pre-integration checks");

            // Remove it again
            pr.addComment("/reviewer remove @" + integrator.forge().currentUser().userName());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"successfully removed");

            // Remove something that isn't there
            pr.addComment("/reviewer remove @" + integrator.forge().currentUser().userName());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"There are no additional reviewers associated with this pull request");

            // Add the reviewer again
            pr.addComment("/reviewer add integrationreviewer1");
            TestBotRunner.runPeriodicItems(prBot);

            // But also add the review the old-fashioned way
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The commit message preview should contain the reviewer once
            var creditLine = pr.comments().stream()
                               .flatMap(comment -> comment.body().lines())
                               .filter(line -> line.contains("Reviewed-by"))
                               .findAny()
                               .orElseThrow();
            assertEquals("Reviewed-by: integrationreviewer1", creditLine);

            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("change now passes all automated"))
                           .count();
            assertEquals(1, pushed);

            // Add a second reviewer
            pr.addComment("/reviewer add integrationauthor2");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            creditLine = pr.comments().stream()
                           .flatMap(comment -> comment.body().lines())
                           .filter(line -> line.contains("Reviewed-by"))
                           .findAny()
                           .orElseThrow();
            assertEquals("Reviewed-by: integrationreviewer1, integrationauthor2", creditLine);

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

            // The contributor should be credited
            creditLine = headCommit.message().stream()
                    .filter(line -> line.contains("Reviewed-by"))
                    .findAny()
                    .orElseThrow();
            assertEquals("Reviewed-by: integrationreviewer1, integrationauthor2", creditLine);
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
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue a contributor command not as the PR author
            var externalPr = external.pullRequest(pr.id());
            externalPr.addComment("/reviewer add integrationauthor1");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void invalidReviewer(TestInfo testInfo) throws IOException {
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Use a full name
            pr.addComment("/reviewer add Moo <Foo.Bar (at) host.com>");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Could not parse `Moo <Foo.Bar (at) host.com>` as a valid reviewer");

            // Empty platform id
            pr.addComment("/reviewer add @");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Could not parse `@` as a valid reviewer");

            // Unknown platform id
            pr.addComment("/reviewer add @someone");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Could not parse `@someone` as a valid reviewer");

            // Unknown openjdk user
            pr.addComment("/reviewer add someone");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Could not parse `someone` as a valid reviewer");
        }
    }

    @Test
    void platformUser(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(integrator.forge().currentUser().id())
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Use a platform name
            pr.addComment("/reviewer add @" + author.forge().currentUser().userName());
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply
            assertLastCommentContains(pr, "Reviewer `integrationcommitter2` successfully added.");
        }
    }

    @Test
    void openJdkUser(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(integrator.forge().currentUser().id())
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Use a platform name
            pr.addComment("/reviewer add integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply
            assertLastCommentContains(pr, "Reviewer `integrationauthor1` successfully added.");
        }
    }

    @Test
    void removeReviewer(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(integrator.forge().currentUser().id())
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Remove a reviewer that hasn't been added
            pr.addComment("/reviewer remove integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "There are no additional reviewers associated with this pull request.");

            // Add a reviewer
            pr.addComment("/reviewer add integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully added.");

            // Remove another (not added) reviewer
            pr.addComment("/reviewer remove integrationcommitter2");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Reviewer `integrationcommitter2` was not found.");
            assertLastCommentContains(pr, "Current additional reviewers are:");
            assertLastCommentContains(pr, "- `integrationauthor1`");

            // Remove an existing reviewer
            pr.addComment("/reviewer remove integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully removed.");
        }
    }

    @Test
    void prBodyUpdates(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(integrator.forge().currentUser().id())
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Add a reviewer
            pr.addComment("/reviewer add integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully added.");

            // Verify that body is updated
            var body = pr.body().split("\n");
            var contributorsHeaderIndex = -1;
            for (var i = 0; i < body.length; i++) {
                var line = body[i];
                if (line.equals("### Reviewers")) {
                    contributorsHeaderIndex = i;
                    break;
                }
            }
            assertNotEquals(contributorsHeaderIndex, -1);
            var contributors = new ArrayList<String>();
            for (var i = contributorsHeaderIndex + 1; i < body.length && body[i].startsWith(" * "); i++) {
                contributors.add(body[i].substring(3));
            }
            assertEquals(1, contributors.size());
            assertEquals("integrationauthor1 - Author ⚠️ Added manually", contributors.get(0));

            // Remove contributor
            pr.addComment("/reviewer remove integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully removed.");

            // Verify that body does not contain a "Reviewers" section
            for (var line : pr.body().split("\n")) {
                assertNotEquals("### Reviewers", line);
            }
            assertFalse(pr.body().contains("Added manually"));

            // Add it once more
            pr.addComment("/reviewer add integrationauthor1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully added.");
            assertTrue(pr.body().contains("Added manually"));

            // Now add an authenticated review from the same reviewer
            var integratorPr = integrator.pullRequest(pr.id());
            integratorPr.addReview(Review.Verdict.APPROVED, "Looks good");
            TestBotRunner.runPeriodicItems(prBot);

            // The reviewer should no longer be listed as added manually
            assertFalse(pr.body().contains("Added manually"));
        }
    }

    @Test
    void addAuthenticated(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var extra = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addAuthor(extra.forge().currentUser().id())
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Add the review the old-fashioned way
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // Try to add it manually as well
            pr.addComment("/reviewer add integrationreviewer1");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"Reviewer `integrationreviewer1` has already made an authenticated review of this PR");
        }
    }
}
