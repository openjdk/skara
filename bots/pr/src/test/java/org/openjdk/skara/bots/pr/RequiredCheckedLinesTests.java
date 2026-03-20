/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.common.PullRequestConstants.PROGRESS_MARKER;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class RequiredCheckedLinesTests {
    @Test
    void dashLowerCaseChecked(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR using lowercase x for checkbox
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("- [x] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertTrue(updatedPR.body().startsWith(
                "- [x] foo" +
                "\n\n" +
                PROGRESS_MARKER
            ), updatedPR.body());
            assertEquals(List.of("rfr"), updatedPR.labelNames());
        }
    }

    @Test
    void dashUpperCaseChecked(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR using upper case X in checkbox
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("- [X] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertTrue(updatedPR.body().startsWith(
                "- [X] foo" + 
                "\n\n" +
                PROGRESS_MARKER
            ), updatedPR.body());
            assertEquals(List.of("rfr"), updatedPR.labelNames());
        }
    }

    @Test
    void checkedWithTrailingWhitespace(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with trailing whitespace after checkbox line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("First line",
                        "- [x] foo   \t\t   ",
                        "Last line")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of("rfr"), updatedPR.labelNames());
            assertTrue(updatedPR.body().startsWith(
                "First line\n" +
                "- [x] foo   \t\t   \n" +
                "Last line" +
                "\n\n" +
                PROGRESS_MARKER
            ), updatedPR.body());
        }
    }

    @Test
    void multipleCheckedRequiredLines(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo", "bar"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR, do not add any required line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("This is a pull request")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Should not be ready for review (nor have any other labels)
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());

            // Should have errors for both missing required lines
            var lines = updatedPR.body().lines().toList();
            var foundUncheckedErrorLineForFoo = false;
            var foundUncheckedErrorLineForBar = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("### Error")) {
                    for (int j = i+1; j < lines.size(); j++) {
                        if (!lines.get(j).startsWith("&nbsp;")) {
                            break; // no longer an error list item
                        }

                        if (lines.get(j).contains("Pull request body is missing required line: `- [x] foo`")) {
                            assertFalse(foundUncheckedErrorLineForFoo, "Should only be one error");
                            foundUncheckedErrorLineForFoo = true;
                        } else if (lines.get(j).contains("Pull request body is missing required line: `- [x] bar`")) {
                            assertFalse(foundUncheckedErrorLineForBar, "Should only be one error");
                            foundUncheckedErrorLineForBar = true;
                        }
                    }
                }
            }
            assertTrue(foundUncheckedErrorLineForFoo, updatedPR.body());
            assertTrue(foundUncheckedErrorLineForBar, updatedPR.body());

            // Add one of the required lines
            updatedPR.setBody(
                "This is a pull request\n" +
                "- [x] foo"
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Should still not be ready for review (nor have any other labels)
            updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());

            // Should have error for missing required line "bar"
            lines = updatedPR.body().lines().toList();
            foundUncheckedErrorLineForBar = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("### Error")) {
                    for (int j = i+1; j < lines.size(); j++) {
                        if (!lines.get(j).startsWith("&nbsp;")) {
                            break; // no longer an error list item
                        }

                        if (lines.get(j).contains("Pull request body is missing required line: `- [x] bar`")) {
                            assertFalse(foundUncheckedErrorLineForBar, "Should only be one error");
                            foundUncheckedErrorLineForBar = true;
                        }
                    }
                }
            }
            assertTrue(foundUncheckedErrorLineForBar, updatedPR.body());

            // Add both of the required lines
            updatedPR.setBody(
                "This is a pull request\n" +
                "- [x] foo\n" +
                "- [x] bar"
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Should be ready for review
            updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of("rfr"), updatedPR.labelNames());

            // There should not be any errors
            lines = updatedPR.body().lines().toList();
            for (var line : lines) {
                assertFalse(line.startsWith("### Error"), line);
            }
        }
    }

    @Test
    void uncheckedLineBlocksReadyForReview(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .addReviewer(reviewer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with unchecked line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("This is a pull request",
                        "- [ ] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Pull request should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertTrue(updatedPR.body().startsWith(
                "This is a pull request\n" +
                "- [ ] foo" +
                "\n\n" +
                PROGRESS_MARKER
            ), updatedPR.body());

            // Should not be ready for review (nor have any other labels)
            assertEquals(List.of(), updatedPR.labelNames());

            // Should have an error
            var lines = updatedPR.body().lines().toList();
            var foundUncheckedErrorLine = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("### Error")) {
                    for (int j = i+1; j < lines.size(); j++) {
                        if (!lines.get(j).startsWith("&nbsp;")) {
                            break; // no longer an error list item
                        }

                        if (lines.get(j).contains("Pull request body is missing required line: `- [x] foo`")) {
                            assertFalse(foundUncheckedErrorLine, "Should only be one error");
                            foundUncheckedErrorLine = true;
                        }
                    }
                }
            }
            assertTrue(foundUncheckedErrorLine, updatedPR.body());

            // Add checked line
            updatedPR.setBody(
                "This is a pull request\n" +
                "- [x] foo"
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Should be ready for review
            updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of("rfr"), updatedPR.labelNames());

            // There should not be any errors
            lines = updatedPR.body().lines().toList();
            for (var line : lines) {
                assertFalse(line.startsWith("### Error"), line);
            }
        }
    }

    @Test
    void cleanBackportRequiresCheckedLines(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();
            var issues = credentials.getIssueProject();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .issueProject(issues)
                    .issuePRMap(new HashMap<>())
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            var releaseBranch = localRepo.branch(masterHash, "release");
            localRepo.checkout(releaseBranch);
            var newFile = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile, "hello");
            localRepo.add(newFile);
            var issue1 = credentials.createIssue(issues, "An issue");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var releaseHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(releaseHash, author.authenticatedUrl(), "refs/heads/release", true);

            // "backport" the new file to the master branch
            localRepo.checkout(localRepo.defaultBranch());
            var editBranch = localRepo.branch(masterHash, "edit");
            localRepo.checkout(editBranch);
            var newFile2 = localRepo.root().resolve("a_new_file.txt");
            Files.writeString(newFile2, "hello");
            localRepo.add(newFile2);
            var editHash = localRepo.commit("Backport", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Backport " + releaseHash.hex(),
                    List.of("This is a clean backport pull request"));

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // The bot should reply with a backport message
            var updatedPR = author.pullRequest(pr.id());
            var backportComment = updatedPR.comments().get(1).body();
            assertTrue(backportComment.contains("This backport pull request has now been updated with issue"));
            assertTrue(backportComment.contains("<!-- backport " + releaseHash.hex() + " -->"));
            assertEquals(issue1Number + ": An issue", updatedPR.title());

            // The pull request should not be ready for review
            var labels = updatedPR.labelNames();
            Collections.sort(labels);
            assertEquals(List.of("backport", "clean"), labels);

            // Should have an error
            var lines = updatedPR.body().lines().toList();
            var foundUncheckedErrorLine = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("### Error")) {
                    for (int j = i+1; j < lines.size(); j++) {
                        if (!lines.get(j).startsWith("&nbsp;")) {
                            break; // no longer an error list item
                        }

                        if (lines.get(j).contains("Pull request body is missing required line: `- [x] foo`")) {
                            assertFalse(foundUncheckedErrorLine, "Should only be one error");
                            foundUncheckedErrorLine = true;
                        }
                    }
                }
            }
            assertTrue(foundUncheckedErrorLine, updatedPR.body());

            // Add checked line
            updatedPR.setBody(
                "This is a clean backport pull request\n" +
                "- [x] foo"
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Should be ready for integration
            updatedPR = author.pullRequest(pr.id());
            labels = updatedPR.labelNames();
            Collections.sort(labels);
            assertEquals(List.of("backport", "clean", "ready", "rfr"), labels);

            // There should not be any errors
            lines = updatedPR.body().lines().toList();
            for (var line : lines) {
                assertFalse(line.startsWith("### Error"), line);
            }
        }
    }

    @Test
    void cleanMergePRRequiresCheckedLines(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {

            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .requiredCheckedLines(List.of("foo"))
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make more changes in another branch
            var otherHash1 = CheckableRepository.appendAndCommit(localRepo, "First change in other_/-1.2",
                                                                "First other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash1, author.authenticatedUrl(), "other_/-1.2", true);
            var otherHash2 = CheckableRepository.appendAndCommit(localRepo, "Second change in other_/-1.2",
                                                                "Second other_/-1.2\n\nReviewed-by: integrationreviewer2");
            localRepo.push(otherHash2, author.authenticatedUrl(), "other_/-1.2");

            // Go back to the original master
            localRepo.checkout(masterHash, true);

            // Make a change with a corresponding PR
            var unrelated = Files.writeString(localRepo.root().resolve("unrelated.txt"), "Unrelated");
            localRepo.add(unrelated);
            var updatedMaster = localRepo.commit("Unrelated", "some", "some@one");
            localRepo.merge(otherHash2);
            localRepo.push(updatedMaster, author.authenticatedUrl(), "master");

            var mergeHash = localRepo.commit("Merge commit", "some", "some@one");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "Merge " + author.name() + ":other_/-1.2",
                List.of("This is a merge-style pull request")
            );

            // Let the bot check the status
            TestBotRunner.runPeriodicItems(bot);

            // Merge PR should be clean but not ready for review
            var updatedPR = author.pullRequest(pr.id());
            var labels = updatedPR.labelNames();
            Collections.sort(labels);
            assertEquals(List.of("clean"), labels);

            // Should have an error
            var lines = updatedPR.body().lines().toList();
            var foundUncheckedErrorLine = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("### Error")) {
                    for (int j = i+1; j < lines.size(); j++) {
                        if (!lines.get(j).startsWith("&nbsp;")) {
                            break; // no longer an error list item
                        }

                        if (lines.get(j).contains("Pull request body is missing required line: `- [x] foo`")) {
                            assertFalse(foundUncheckedErrorLine, "Should only be one error");
                            foundUncheckedErrorLine = true;
                        }
                    }
                }
            }
            assertTrue(foundUncheckedErrorLine, updatedPR.body());

            // Add checked line
            updatedPR.setBody(
                "This is a merge-style pull request\n" +
                "- [x] foo"
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // Should be ready for integration
            updatedPR = author.pullRequest(pr.id());
            labels = updatedPR.labelNames();
            Collections.sort(labels);
            assertEquals(List.of("clean", "ready", "rfr"), labels);

            // There should not be any errors
            lines = updatedPR.body().lines().toList();
            for (var line : lines) {
                assertFalse(line.startsWith("### Error"), line);
            }
        }
    }

    @Test
    void checkedLineInBlockComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with checked line in HTML block comment
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("<!--",
                        "- [x] foo",
                        "-->"
                )
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }

    @Test
    void commentInCheckedLine(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with HTML comment in checked line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("- [<!--x-->] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }

    @Test
    void blockCommentStartingOnCheckedLine(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with HTML block comment starting on checked line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("- [x] foo <!--")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }

    @Test
    void blockCommentEndingOnCheckedLine(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with HTML block comment ending on checked line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("<!--",
                        "-->- [x] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }

    @Test
    void commentBeforeCheckedLine(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with HTML comment on same line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("<!-- HIDDEN -->- [x] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }

    @Test
    void commentsBeforeAndAfterCheckedLineShouldWork(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with HTML comments before and after checked line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("<!-- BEFORE -->",
                        "<!--",
                        "-->",
                        "- [x] foo",
                        "<!-- AFTER -->",
                        "<!--",
                        "-->"
                )
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of("rfr"), updatedPR.labelNames());
        }
    }

    @Test
    void blockCommentStartingOnEndingLine(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with HTML block comment starting on
            // the same line where a previous HTML block comment ended
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("<!--",
                        "--><!--",
                        "- [x] foo",
                        "-->"
                )
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }

    @Test
    void prefixWhitespaceIsNotTrimmed(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var committer = credentials.getHostedRepository();

            var census = credentials.getCensusBuilder()
                .addCommitter(committer.forge().currentUser().id())
                .build();
            var seedPath = tmp.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                    .repo(committer)
                    .censusRepo(census)
                    .seedStorage(seedPath)
                    .requiredCheckedLines(List.of("foo"))
                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tmp.path(), committer.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, committer.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR with whitespace before checked line
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, committer.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(committer, "master", "edit", "A PR",
                List.of("  - [x] foo")
            );

            // Check the status
            TestBotRunner.runPeriodicItems(bot);

            // PR should not be ready for review
            var updatedPR = committer.pullRequest(pr.id());
            assertEquals(List.of(), updatedPR.labelNames());
        }
    }
}
