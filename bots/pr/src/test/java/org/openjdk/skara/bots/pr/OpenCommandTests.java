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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.junit.jupiter.api.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;

public class OpenCommandTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
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
            TestBotRunner.runPeriodicItems(prBot);

            // Close the PR
            pr.setState(Issue.State.CLOSED);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.isClosed());

            // Issue the "/open" PR command, should make the PR open again
            pr.addComment("/open");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.isOpen());
            assertLastCommentContains(pr, "This pull request is now open");
        }
    }

    @Test
    void openCommandOnlyAllowedByAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var other = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addCommitter(other.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
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
            TestBotRunner.runPeriodicItems(prBot);

            // Close the PR
            pr.setState(Issue.State.CLOSED);
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.isClosed());

            // Try to issue the "/open" PR command, should not work
            var prAsOther = other.pullRequest(pr.id());
            prAsOther.addComment("/open");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(prAsOther.isClosed());
            assertLastCommentContains(prAsOther, "Only the pull request author can set the pull request state to \"open\"");
        }
    }

    @Test
    void openCommandOnlyAllowedOnClosedPullRequest(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var prBot = PullRequestBot.newBuilder()
                                      .repo(integrator)
                                      .censusRepo(censusBuilder.build())
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
            TestBotRunner.runPeriodicItems(prBot);

            // Try to issue the "/open" PR command, should not work
            assertTrue(pr.isOpen());
            pr.addComment("/open");
            TestBotRunner.runPeriodicItems(prBot);
            assertTrue(pr.isOpen());
            assertLastCommentContains(pr, "This pull request is already open");
        }
    }
}
