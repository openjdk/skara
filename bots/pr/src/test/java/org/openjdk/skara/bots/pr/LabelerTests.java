/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
            assertEquals(Set.of(), new HashSet<>(pr.labelNames()));
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
            assertEquals(Set.of("rfr", "test1"), new HashSet<>(pr.labelNames()));
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
            assertEquals(Set.of("rfr", "test1"), new HashSet<>(pr.labelNames()));
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

            // Issue a manual label command
            var reviewerPr = reviewer.pullRequest(pr.id());
            reviewerPr.addComment("/label add test2");

            // Check the status - there should still only be a test2 label
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test2"), new HashSet<>(pr.labelNames()));
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

            // Manually set a label
            pr.addLabel("test2");

            // Check the status - there should still only be a test2 label
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test2"), new HashSet<>(pr.labelNames()));
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
            assertEquals(Set.of("rfr", "test1", "test42"), new HashSet<>(pr.labelNames()));
        }
    }
}
