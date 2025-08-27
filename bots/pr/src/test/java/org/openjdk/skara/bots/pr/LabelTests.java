/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.*;
import org.junit.jupiter.api.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertFirstCommentContains;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class LabelTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                           .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                           .addGroup("group", List.of("1", "2"))
                                                           .addExtra("extra")
                                                           .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .labelConfiguration(labelConfiguration)
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
            TestBotRunner.runPeriodicItems(prBot);

            // No arguments
            pr.addComment("/label");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"Usage: `/label");

            // Check that the alias works as well
            pr.addComment("/cc");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"Usage: `/cc");

            // Invalid label
            pr.addComment("/label add unknown");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a failure message
            assertLastCommentContains(pr,"The label `unknown` is not a valid label");
            assertLastCommentContains(pr,"* `1`");
            assertLastCommentContains(pr,"* `group`");
            assertLastCommentContains(pr,"* `extra`");

            // Add a label
            pr.addComment("/skara label add 1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully added.");

            // One more
            pr.addComment("/cc group");
            TestBotRunner.runPeriodicItems(prBot);
            // Since group label is added, 1 should be removed
            assertFalse(pr.store().labelNames().contains("1"));

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `group` label was successfully added.");

            // Drop group
            pr.addComment("        /skara label remove   group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `group` label was successfully removed.");

            // And once more
            pr.addComment("   /skara    label add 2, extra");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `2` label was successfully added.");
            assertLastCommentContains(pr,"The `extra` label was successfully added.");
        }
    }

    @Test
    void adjustAutoApplied(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                           .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                           .addGroup("group", List.of("1", "2"))
                                                           .addExtra("extra")
                                                           .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .labelConfiguration(labelConfiguration)
                                      .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType(), Path.of("test.hpp"));
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // The bot should have applied one label automatically
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("2", "rfr"), new HashSet<>(pr.store().labelNames()));
            assertLastCommentContains(pr, "The following label will be automatically applied");
            assertLastCommentContains(pr, "`2`");

            // The bot will remove the label
            pr.addComment("/label remove 2");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `2` label was successfully removed.");

            // Add another file that would have trigger a group match
            Files.writeString(localRepoFolder.resolve("test.cpp"), "Hello there");
            localRepo.add(Path.of("test.cpp"));
            editHash = localRepo.commit("Another one", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // The bot should add label "1" since test.cpp is touched
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("1", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Adding the label manually is fine
            pr.addComment("/label add group");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `group` label was successfully added.");
            assertEquals(Set.of("group", "rfr"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void overrideAutoApplied(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                           .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                           .addGroup("group", List.of("1", "2"))
                                                           .addExtra("extra")
                                                           .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .labelConfiguration(labelConfiguration)
                                      .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType(), Path.of("test.hpp"));
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");
            pr.setBody("/cc 1");

            // The bot will not add any label automatically
            TestBotRunner.runPeriodicItems(prBot);
            // Since there is already a component associated, rfr should be added
            assertLastCommentContains(pr, "A manual label command was issued before auto-labeling, so auto-labeling was skipped.");
            assertEquals(Set.of("1", "rfr"), new HashSet<>(pr.store().labelNames()));
            assertEquals(3, pr.comments().size());
            assertTrue(pr.store().comments().get(1).body().contains("The `1` label was successfully added."));

            // Add another file to trigger a group match
            Files.writeString(localRepoFolder.resolve("test.cpp"), "Hello there");
            localRepo.add(Path.of("test.cpp"));
            editHash = localRepo.commit("Another one", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.authenticatedUrl(), "edit");

            // The bot will still not do any automatic labelling
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("1", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Adding manually is still fine
            pr.addComment("/label add group 2");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `group` label was successfully added.");
            assertLastCommentContains(pr, "The `2` label was successfully added.");
            assertEquals(Set.of("group", "rfr"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void commandAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var other = credentials.getHostedRepository();
            var committer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(committer.forge().currentUser().id())
                                           .addAuthor(author.forge().currentUser().id())
                                           .addAuthor(other.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                           .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                           .addGroup("group", List.of("1", "2"))
                                                           .addExtra("extra")
                                                           .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .labelConfiguration(labelConfiguration)
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
            TestBotRunner.runPeriodicItems(prBot);

            // Non committers cannot modify labels
            var otherPr = other.pullRequest(pr.id());
            otherPr.addComment("/label extra");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "Only the PR author and project [Committers]");

            // But PR authors can
            pr.addComment("/label extra");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `extra` label was successfully added");

            // As well as other committers
            var committerPr = committer.pullRequest(pr.id());
            committerPr.addComment("/label 2");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `2` label was successfully added");
        }
    }

    @Test
    void stripSuffix(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                       .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                       .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                       .addGroup("group", List.of("1", "2"))
                                                       .addExtra("extra")
                                                       .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .labelConfiguration(labelConfiguration)
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

            TestBotRunner.runPeriodicItems(prBot);

            // Add a label with -dev suffix
            pr.addComment("/label add 1-dev");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully added.");

            // One more
            pr.addComment("/cc group-dev@openjdk.org");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `group` label was successfully added.");
        }
    }

    @Test
    void twoReviewersLabels(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                       .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                       .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                       .addGroup("group", List.of("1", "2"))
                                                       .addExtra("extra")
                                                       .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .twoReviewersLabels(Set.of("1"))
                                      .labelConfiguration(labelConfiguration)
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
            TestBotRunner.runPeriodicItems(prBot);

            // Add a label with -dev suffix
            pr.addComment("/label add 1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully added.");

            // Review the PR
            var prAsReviewer = integrator.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good!");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a integration message
            assertFirstCommentContains(pr, "This change now passes all *automated* pre-integration checks");
            assertFirstCommentContains(pr, ":mag: One or more changes in this pull request modifies files");
            assertFirstCommentContains(pr, "in areas of the source code that often require two reviewers.");
        }
    }

    @Test
    void twentyFourHoursLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                                                       .addMatchers("1", List.of(Pattern.compile("cpp$")))
                                                       .addMatchers("2", List.of(Pattern.compile("hpp$")))
                                                       .addGroup("group", List.of("1", "2"))
                                                       .addExtra("extra")
                                                       .build();
            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
                                      .twentyFourHoursLabels(Set.of("1"))
                                      .labelConfiguration(labelConfiguration)
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
            TestBotRunner.runPeriodicItems(prBot);

            // Add a label with 24h hint
            pr.addComment("/label add 1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully added.");

            // Review the PR
            var prAsReviewer = integrator.pullRequest(pr.id());
            prAsReviewer.addReview(Review.Verdict.APPROVED, "Looks good!");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a integration message
            assertFirstCommentContains(pr,"This change now passes all *automated* pre-integration checks");
            assertFirstCommentContains(pr,":earth_americas: Applicable reviewers for one or more changes ");
            assertFirstCommentContains(pr,"in this pull request are spread across multiple different time zones.");
            assertFirstCommentContains(pr,"been out for review for at least 24 hours");
        }
    }

    @Test
    void shortArgument(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                    .addMatchers("1", List.of(Pattern.compile("cpp$")))
                    .addMatchers("2", List.of(Pattern.compile("hpp$")))
                    .addGroup("group", List.of("1", "2"))
                    .addExtra("extra")
                    .build();
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .labelConfiguration(labelConfiguration)
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
            TestBotRunner.runPeriodicItems(prBot);

            // Add a label without `+`
            pr.addComment("/label 1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully added.");
            assertEquals(Set.of("1", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Add a label without `+` and check that the alias works as well
            pr.addComment("/cc 2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `2` label was successfully added.");
            // label "1" and "2" should be upgraded to "group"
            assertEquals(Set.of("group", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Remove a label with `-`
            pr.addComment("/label -group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr, "The `group` label was successfully removed.");
            // The rfr label should be removed because the pr is not associated with any component
            assertEquals(Set.of(), new HashSet<>(pr.store().labelNames()));

            // Add a label with `+`
            pr.addComment("/label +group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr, "The `group` label was successfully added.");
            assertEquals(Set.of("rfr", "group"), new HashSet<>(pr.store().labelNames()));

            // Mixed `+/-` labels
            pr.addComment("/label +2,-group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with the success messages
            assertLastCommentContains(pr,"The `2` label was successfully added.");
            assertLastCommentContains(pr,"The `group` label was successfully removed.");
            assertEquals(Set.of("2", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Mixed `+/-` labels again and check that the alias works as well
            pr.addComment("/label group, +1, -2");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with the success messages
            assertLastCommentContains(pr,"The `group` label was successfully added.");
            assertLastCommentContains(pr,"The `1` label was successfully added.");
            assertLastCommentContains(pr,"The `2` label was successfully removed.");
            assertEquals(Set.of("rfr", "group"), new HashSet<>(pr.store().labelNames()));

            // Mixed `+/-` labels and intentional whitespace.
            pr.addComment("/label - 1, + 2, - group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"Usage: `/label");
            assertEquals(Set.of("rfr", "group"), new HashSet<>(pr.store().labelNames()));

            // Mixed normal and short labels
            pr.addComment("/label add +2, -group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a help message
            assertLastCommentContains(pr,"Usage: `/label");
            assertEquals(Set.of("rfr", "group"), new HashSet<>(pr.store().labelNames()));

            // Check unknown labels
            pr.addComment("/label +unknown1, -unknown2, unknown3");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a failure message
            assertLastCommentContains(pr,"The label `unknown1` is not a valid label");
            assertLastCommentContains(pr,"The label `unknown2` is not a valid label");
            assertLastCommentContains(pr,"The label `unknown3` is not a valid label");
            assertLastCommentContains(pr,"* `1`");
            assertLastCommentContains(pr,"* `group`");
            assertLastCommentContains(pr,"* `extra`");
            assertEquals(Set.of("rfr", "group"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void autoAdjustLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(integrator.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfigurationJson.builder()
                    .addMatchers("1", List.of(Pattern.compile("cpp$")))
                    .addMatchers("2", List.of(Pattern.compile("hpp$")))
                    .addMatchers("3", List.of(Pattern.compile("txt$")))
                    .addGroup("group1", List.of("1", "2"))
                    .addExtra("extra")
                    .build();
            var prBot = PullRequestBot.newBuilder()
                    .repo(integrator)
                    .censusRepo(censusBuilder.build())
                    .labelConfiguration(labelConfiguration)
                    .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType(), Path.of("test.hpp"));
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // The bot should have applied one label automatically
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("2", "rfr"), new HashSet<>(pr.store().labelNames()));
            assertLastCommentContains(pr, "The following label will be automatically applied");
            assertLastCommentContains(pr, "`2`");

            var test1Cpp = localRepo.root().resolve("test1.cpp");
            try (var output = Files.newBufferedWriter(test1Cpp)) {
                output.append("test");
            }
            localRepo.add(test1Cpp);
            var addHash = localRepo.commit("add cpp file", "duke", "duke@openjdk.org");
            localRepo.push(addHash, author.authenticatedUrl(), "edit", true);
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("group1", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Simulate force-push.
            localRepo.checkout(editHash);
            var test1txt = localRepo.root().resolve("test1.txt");
            try (var output = Files.newBufferedWriter(test1txt)) {
                output.append("test");
            }
            localRepo.add(test1txt);
            var forcePushHash = localRepo.commit("add txt file", "duke", "duke@openjdk.org");
            localRepo.push(forcePushHash, author.authenticatedUrl(), "edit", true);
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("group1", "rfr", "3"), new HashSet<>(pr.store().labelNames()));
        }
    }
}
