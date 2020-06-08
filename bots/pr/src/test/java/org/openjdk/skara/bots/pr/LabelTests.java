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
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
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
            var labelConfiguration = LabelConfiguration.newBuilder()
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

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
            pr.addComment("/label add 1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully added.");

            // One more
            pr.addComment("/cc group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `group` label was successfully added.");

            // Drop both
            pr.addComment("/label remove 1   group");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `1` label was successfully removed.");
            assertLastCommentContains(pr,"The `group` label was successfully removed.");

            // And once more
            pr.addComment("/label add 2, extra");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"The `2` label was successfully added.");
            assertLastCommentContains(pr,"The `extra` label was successfully added.");
        }
    }

    @Test
    void removeAutoApplied(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addReviewer(integrator.forge().currentUser().id())
                                           .addCommitter(author.forge().currentUser().id());
            var labelConfiguration = LabelConfiguration.newBuilder()
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // The bot should have applied one label automatically
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("2", "rfr"), new HashSet<>(pr.labels()));

            // It will refuse to remove it
            pr.addComment("/label remove 2");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `2` label was automatically added and cannot be removed.");

            // Add another file to trigger a group match
            Files.writeString(localRepoFolder.resolve("test.cpp"), "Hello there");
            localRepo.add(Path.of("test.cpp"));
            editHash = localRepo.commit("Another one", "duke", "duke@openjdk.org");
            localRepo.push(editHash, author.url(), "edit");

            // The bot should have applied more labels automatically
            TestBotRunner.runPeriodicItems(prBot);
            assertEquals(Set.of("1", "2", "group", "rfr"), new HashSet<>(pr.labels()));

            // It will refuse to remove these as well
            pr.addComment("/label remove group, 1");
            TestBotRunner.runPeriodicItems(prBot);
            assertLastCommentContains(pr, "The `1` label was automatically added and cannot be removed.");
            assertLastCommentContains(pr, "The `group` label was automatically added and cannot be removed.");
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
            var labelConfiguration = LabelConfiguration.newBuilder()
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
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

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
}
