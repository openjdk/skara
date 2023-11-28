/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.vcs.Reference;
import org.openjdk.skara.vcs.VCS;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class BranchCommitCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .integrators(Set.of(author.forge().currentUser().username()))
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a branch command
            author.addCommitComment(masterHash, "/branch next");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("branch"));
            assertTrue(botReply.body().contains("was successfully created"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var next = new Branch("next");
            localAuthorRepo.checkout(next);
            assertTrue(localAuthorRepo.branches().contains(next));
            var nextHead = localAuthorRepo.lookup(next);
            assertTrue(nextHead.isPresent());
            assertEquals(masterHash, nextHead.get().hash());
        }
    }

    @Test
    void missingBranchName(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .integrators(Set.of(author.forge().currentUser().username()))
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add an empty branch command
            author.addCommitComment(masterHash, "/branch");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Usage: `/branch <name>`"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
        }
    }

    @Test
    void multipleBranchNames(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .integrators(Set.of(author.forge().currentUser().username()))
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a branch command
            author.addCommitComment(masterHash, "/branch a b c");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Usage: `/branch <name>`"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var remoteBranches = localAuthorRepo.remoteBranches("origin")
                                                .stream()
                                                .map(Reference::name)
                                                .collect(Collectors.toSet());
            assertFalse(remoteBranches.contains("a"));
            assertFalse(remoteBranches.contains("b"));
            assertFalse(remoteBranches.contains("c"));
        }
    }

    @Test
    void existingBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .integrators(Set.of(author.forge().currentUser().username()))
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a branch command
            author.addCommitComment(masterHash, "/branch next");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("branch"));
            assertTrue(botReply.body().contains("was successfully created"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var next = new Branch("next");
            localAuthorRepo.checkout(next);
            assertTrue(localAuthorRepo.branches().contains(next));
            var nextHead = localAuthorRepo.lookup(next);
            assertTrue(nextHead.isPresent());
            assertEquals(masterHash, nextHead.get().hash());

            // Make another commit
            var anotherHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(anotherHash, author.authenticatedUrl(), "master", true);

            // Try to re-create an existing branch
            author.addCommitComment(anotherHash, "/branch next");
            TestBotRunner.runPeriodicItems(bot);

            recentCommitComments = author.recentCommitComments();
            assertEquals(4, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("A branch with name `next` already exists"));
            Pattern compilePattern = Pattern.compile(".*\\[.*\\]\\(.*\\).*", Pattern.MULTILINE | Pattern.DOTALL);
            assertTrue(compilePattern.matcher(botReply.body()).matches());
        }
    }

    @Test
    void nonIntegrator(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a branch command
            author.addCommitComment(masterHash, "/branch next");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Only integrators for this repository are allowed to use the `/branch` command"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var remoteBranches = localAuthorRepo.remoteBranches("origin")
                                                .stream()
                                                .map(Reference::name)
                                                .collect(Collectors.toSet());
            assertFalse(remoteBranches.contains("next"));
        }
    }

    @Test
    void nonConformingBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id());
            var seedFolder = tempFolder.path().resolve("seed");
            var bot = PullRequestBot.newBuilder()
                                    .repo(author)
                                    .integrators(Set.of(author.forge().currentUser().username()))
                                    .censusRepo(censusBuilder.build())
                                    .censusLink("https://census.com/{{contributor}}-profile")
                                    .seedStorage(seedFolder)
                                    .forks(Map.of(author.name(), author))
                                    .build();

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var jcheckConf = localRepo.root().resolve(".jcheck").resolve("conf");
            Files.write(jcheckConf, List.of("[repository]", "branches=foo"), StandardOpenOption.APPEND);
            localRepo.add(List.of(Path.of(".jcheck", "conf")));
            localRepo.commit("Added branches spec", "testauthor", "ta@none.none");
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a branch command
            author.addCommitComment(masterHash, "/branch bar");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("The given branch name `bar` is not of the form `foo`"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var remoteBranches = localAuthorRepo.remoteBranches("origin")
                                                .stream()
                                                .map(Reference::name)
                                                .collect(Collectors.toSet());
            assertFalse(remoteBranches.contains("next"));
        }
    }
}
