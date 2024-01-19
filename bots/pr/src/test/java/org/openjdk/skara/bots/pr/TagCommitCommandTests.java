/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Tag;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TagCommitCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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

            // Add a tag command
            author.addCommitComment(masterHash, "/skara tag v1.0");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("tag"));
            assertTrue(botReply.body().contains("was successfully created"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(new Tag("v1.0")), tags);
        }
    }

    @Test
    void missingTagName(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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

            // Add a tag command
            author.addCommitComment(masterHash, "/tag");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Usage: `/tag <name>`"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(), tags);
        }
    }

    @Test
    void multipleTagNames(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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

            // Add a tag command
            author.addCommitComment(masterHash, "/tag a b c");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Usage: `/tag <name>`"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(), tags);
        }
    }

    @Test
    void existingTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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

            // Add a tag command
            author.addCommitComment(masterHash, "/tag v1.0");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("tag"));
            assertTrue(botReply.body().contains("was successfully created"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(new Tag("v1.0")), tags);

            // Make another commit
            var anotherHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(anotherHash, author.authenticatedUrl(), "master", true);

            // Try to re-create an existing tag
            author.addCommitComment(anotherHash, "/tag v1.0");
            TestBotRunner.runPeriodicItems(bot);

            recentCommitComments = author.recentCommitComments();
            assertEquals(4, recentCommitComments.size());
            botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("A tag with name `v1.0` already exists"));
            Pattern compilePattern = Pattern.compile(".*\\[.*\\]\\(.*\\).*", Pattern.MULTILINE | Pattern.DOTALL);
            assertTrue(compilePattern.matcher(botReply.body()).matches());
        }
    }

    @Test
    void nonIntegrator(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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

            // Add a tag command
            author.addCommitComment(masterHash, "/tag v1.0");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("Only integrators for this repository are allowed to use the `/tag` command"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(), tags);
        }
    }

    @Test
    void nonConformingTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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
            Files.write(jcheckConf, List.of("[repository]", "tags=foo"), StandardOpenOption.APPEND);
            localRepo.add(List.of(Path.of(".jcheck", "conf")));
            localRepo.commit("Added tags spec", "testauthor", "ta@none.none");
            var masterHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add a tag command
            author.addCommitComment(masterHash, "/tag bar");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            System.out.println(botReply);
            assertTrue(botReply.body().contains("The given tag name `bar` is not of the form `foo`"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(), tags);
        }
    }

    @Test
    void metadata(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
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

            // Add a tag command
            author.addCommitComment(masterHash, "/skara tag v1.0");
            TestBotRunner.runPeriodicItems(bot);

            var recentCommitComments = author.recentCommitComments();
            assertEquals(2, recentCommitComments.size());
            var botReply = recentCommitComments.get(0);
            assertTrue(botReply.body().contains("tag"));
            assertTrue(botReply.body().contains("was successfully created"));

            var localAuthorRepoDir = tempFolder.path().resolve("author");
            var localAuthorRepo = Repository.clone(author.authenticatedUrl(), localAuthorRepoDir);
            var tags = localAuthorRepo.tags();
            assertEquals(List.of(new Tag("v1.0")), tags);

            var tag = localAuthorRepo.annotate(tags.get(0));
            assertTrue(tag.isPresent());
            assertEquals(masterHash, tag.get().target());
            assertEquals("v1.0", tag.get().name());
            assertEquals("Added tag v1.0 for changeset " + masterHash.abbreviate(), tag.get().message());
            assertEquals(Author.fromString("Generated Author 1 <integrationauthor1@openjdk.org>"), tag.get().author());
        }
    }
}
