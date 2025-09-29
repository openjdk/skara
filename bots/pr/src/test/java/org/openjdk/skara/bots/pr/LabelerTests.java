/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

class LabelerTests {
    @Test
    void noMatch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                           .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                           .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status - rfr label should not be set since the pr is not associated with any component
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of(), new HashSet<>(pr.store().labelNames()));
            assertLastCommentContains(pr, "However, no automatic labelling rule matches the changes in this pull request.");
            assertLastCommentContains(pr, "<details>");
            assertLastCommentContains(pr, "<summary>Applicable Labels</summary>");
            assertLastCommentContains(pr, "- `test1`");
            assertLastCommentContains(pr, "- `test2`");
            assertLastCommentContains(pr, "</details>");
        }
    }

    @Test
    void match(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                           .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                           .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);

            var fileA = localRepoFolder.resolve("a.txt");
            Files.writeString(fileA, "Hello");
            localRepo.add(fileA);
            var hashA = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashA, author.authenticatedUrl(), "edit");

            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status - there should now be a test1 label
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test1"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void copy(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                           .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                           .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add an unrelated file to master
            var fileB = localRepoFolder.resolve("b.txt");
            Files.writeString(fileB, "Hello");
            localRepo.add(fileB);
            var hashB = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashB, author.authenticatedUrl(), "master");

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);

            var fileA = localRepoFolder.resolve("a.txt");
            Files.writeString(fileA, "Hello");
            localRepo.add(fileA);
            var hashA = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashA, author.authenticatedUrl(), "edit");

            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status - there should now be a test1 label
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test1"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void initialLabelCommand(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                           .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                           .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);

            var fileA = localRepoFolder.resolve("a.txt");
            Files.writeString(fileA, "Hello");
            localRepo.add(fileA);
            var hashA = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashA, author.authenticatedUrl(), "edit");

            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue a manual label command, this shouldn't affect the auto labeling
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/label add test2");

            // Check the status - there should be test1 and test2
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test2", "test1"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void initialLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                           .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                           .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);

            var fileA = localRepoFolder.resolve("a.txt");
            Files.writeString(fileA, "Hello");
            localRepo.add(fileA);
            var hashA = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashA, author.authenticatedUrl(), "edit");

            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Manually set a label shouldn't affect auto labeling
            pr.addLabel("test2");

            // Check the status - there should be test1 and test2
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test2", "test1"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void initialUnmatchedLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfigurationJson.builder()
                                                           .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                           .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                           .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "refs/heads/edit", true);

            var fileA = localRepoFolder.resolve("a.txt");
            Files.writeString(fileA, "Hello");
            localRepo.add(fileA);
            var hashA = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashA, author.authenticatedUrl(), "edit");

            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Manually set a label that isn't in the set of automatic ones
            pr.addLabel("test42");

            // Check the status - the test1 label should have been added
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test1", "test42"), new HashSet<>(pr.store().labelNames()));
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

            // Add cpp and hpp together should add group label
            var test1Cpp = localRepo.root().resolve("test1.cpp");
            try (var output = Files.newBufferedWriter(test1Cpp)) {
                output.append("test");
            }
            localRepo.add(test1Cpp);
            var test1Hpp = localRepo.root().resolve("test1.hpp");
            try (var output = Files.newBufferedWriter(test1Hpp)) {
                output.append("test");
            }
            localRepo.add(test1Hpp);
            var addHash = localRepo.commit("add cpp,hpp file", "duke", "duke@openjdk.org");
            localRepo.push(addHash, author.authenticatedUrl(), "edit", true);
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("group1", "2", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Add another Cpp file, since "group1" label is already added, the bot shouldn't add "1" label
            var test2Cpp = localRepo.root().resolve("test2.cpp");
            try (var output = Files.newBufferedWriter(test2Cpp)) {
                output.append("test");
            }
            localRepo.add(test2Cpp);
            addHash = localRepo.commit("add cpp2 file", "duke", "duke@openjdk.org");
            localRepo.push(addHash, author.authenticatedUrl(), "edit", true);
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("group1", "2", "rfr"), new HashSet<>(pr.store().labelNames()));

            // But user should still be able to add "1" label manually
            pr.addComment("/label 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("group1", "1", "2", "rfr"), new HashSet<>(pr.store().labelNames()));

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
            assertEquals(Set.of("group1", "1", "2", "rfr", "3"), new HashSet<>(pr.store().labelNames()));
        }
    }

    @Test
    void autoAdjustLabelWithMerge(TestInfo testInfo) throws IOException {
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

            // Update the target branch
            localRepo.checkout(masterHash);
            var txtFile = localRepo.root().resolve("unrelated.txt");
            Files.writeString(txtFile, "Hello");
            localRepo.add(txtFile);
            var updatedMasterHash = localRepo.commit("add txt file", "duke", "duke@openjdk.org");
            localRepo.push(updatedMasterHash, author.authenticatedUrl(), "master", true);

            TestBotRunner.runPeriodicItems(prBot);
            // Change to master branch shouldn't change labels
            assertEquals(Set.of("2", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Merge master into edit
            localRepo.checkout(editHash);
            localRepo.merge(updatedMasterHash);
            var mergeHash = localRepo.commit("merge master", "duke", "duke@openjdk.org");
            localRepo.push(mergeHash, author.authenticatedUrl(), "edit", true);
            // Add cpp file
            localRepo.checkout(mergeHash);
            var cppFile = localRepo.root().resolve("test.cpp");
            Files.writeString(cppFile, "Hello cpp");
            localRepo.add(cppFile);
            var updatedEditHash = localRepo.commit("add cpp file", "duke", "duke@openjdk.org");
            localRepo.push(updatedEditHash, author.authenticatedUrl(), "edit", true);

            TestBotRunner.runPeriodicItems(prBot);
            // The commit brought in by merge shouldn't affect labels, so "3" shouldn't be added
            // After adding cpp file, "1" should be added, but "2" label already there, so "1" will be upgraded to "group1"
            assertEquals(Set.of("group1", "2", "rfr"), new HashSet<>(pr.store().labelNames()));

            // Remove group1 manually
            pr.addComment("/label remove group1");
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("2", "rfr"), new HashSet<>(pr.store().labelNames()));

            //Add another file trigger label "1"
            var cpp2File = localRepo.root().resolve("test2.cpp");
            Files.writeString(cpp2File, "Hello cpp");
            localRepo.add(cpp2File);
            var updated2EditHash = localRepo.commit("add test2.cpp file", "duke", "duke@openjdk.org");
            localRepo.push(updated2EditHash, author.authenticatedUrl(), "edit", true);
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("1", "2", "rfr"), new HashSet<>(pr.store().labelNames()));
        }
    }
}
