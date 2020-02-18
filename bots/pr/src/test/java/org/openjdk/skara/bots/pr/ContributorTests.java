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

import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class ContributorTests {
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/contributor hello");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"Syntax");

            // Add a contributor
            pr.addComment("/contributor add Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"successfully added");

            // Remove it again
            pr.addComment("/contributor remove Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"successfully removed");

            // Remove something that isn't there
            pr.addComment("/contributor remove Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"There are no additional contributors associated with this pull request");

            // Now add someone back again
            pr.addComment("/contributor add Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(prBot);

            // Approve it as another user
            var approvalPr = integrator.pullRequest(pr.id());
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            // The commit message preview should contain the contributor
            var creditLine = pr.comments().stream()
                               .flatMap(comment -> comment.body().lines())
                               .filter(line -> line.contains("Test Person <test@test.test>"))
                               .filter(line -> line.contains("Co-authored-by"))
                               .findAny()
                               .orElseThrow();
            assertEquals("Co-authored-by: Test Person <test@test.test>", creditLine);

            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("change now passes all automated"))
                           .count();
            assertEquals(1, pushed);

            // Add a second person
            pr.addComment("/contributor add Another Person <another@test.test>");
            TestBotRunner.runPeriodicItems(prBot);
            TestBotRunner.runPeriodicItems(prBot);

            creditLine = pr.comments().stream()
                           .flatMap(comment -> comment.body().lines())
                           .filter(line -> line.contains("Another Person <another@test.test>"))
                           .filter(line -> line.contains("Co-authored-by"))
                           .findAny()
                           .orElseThrow();
            assertEquals("Co-authored-by: Another Person <another@test.test>", creditLine);

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
                    .filter(line -> line.contains("Test Person <test@test.test>"))
                    .findAny()
                    .orElseThrow();
            assertEquals("Co-authored-by: Test Person <test@test.test>", creditLine);
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
            externalPr.addComment("/contributor add Test Person <test@test.test>");
            TestBotRunner.runPeriodicItems(mergeBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("Only the author"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    void invalidFullName(TestInfo testInfo) throws IOException {
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

            // Use an invalid full name
            pr.addComment("/contributor add Foo! Bar <foo.bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "The full name is *not* of the format");
        }
    }

    @Test
    void invalidEmail(TestInfo testInfo) throws IOException {
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

            // Use an invalid full name
            pr.addComment("/contributor add Foo Bar <Foo.Bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr, "The email is *not* of the format");
        }
    }

    @Test
    void removeContributor(TestInfo testInfo) throws IOException {
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

            // Remove a contributor that hasn't been added
            pr.addComment("/contributor remove Foo Bar <foo.bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "There are no additional contributors associated with this pull request.");

            // Add a contributor
            pr.addComment("/contributor add Foo Bar <foo.bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully added.");

            // Remove another (not added) contributor
            pr.addComment("/contributor remove Baz Bar <baz.bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Contributor `Baz Bar <baz.bar@host.com>` was not found.");
            assertLastCommentContains(pr, "Current additional contributors are:");
            assertLastCommentContains(pr, "- `Foo Bar <foo.bar@host.com>`");

            // Remove an existing contributor
            pr.addComment("/contributor remove Foo Bar <foo.bar@host.com>");
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

            // Add a contributor
            pr.addComment("/contributor add Foo Bar <foo.bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully added.");

            // Verify that body is updated
            var body = pr.body().split("\n");
            var contributorsHeaderIndex = -1;
            for (var i = 0; i < body.length; i++) {
                var line = body[i];
                if (line.equals("### Contributors")) {
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
            assertEquals("Foo Bar `<foo.bar@host.com>`", contributors.get(0));

            // Remove contributor
            pr.addComment("/contributor remove Foo Bar <foo.bar@host.com>");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "successfully removed.");

            // Verify that body does not contain "Contributors" section
            for (var line : pr.body().split("\n")) {
                assertNotEquals("### Contributors", line);
            }
        }
    }
}
