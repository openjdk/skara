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

import org.openjdk.skara.host.*;
import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VetoTests {
    private static HostUser createUser(int id) {
        return new HostUser(id,
                            String.format("noname_%d", id),
                            String.format("No Name %d", id));
    }

    private static class Comments {
        private final List<Comment> comments = new ArrayList<>();

        void add(String body) {
            var comment = new Comment("0",
                                      body,
                                      createUser(0),
                                      ZonedDateTime.now(),
                                      ZonedDateTime.now());
            comments.add(comment);
        }

        List<Comment> get() {
            return new ArrayList<>(comments);
        }
    }


    @Test
    void simpleVeto() {
        var comments = new Comments();
        comments.add(Veto.addVeto(createUser(123)));
        assertEquals(Set.of("123"), Veto.vetoers(createUser(0), comments.get()));
    }

    @Test
    void multipleVetoes() {
        var comments = new Comments();
        comments.add(Veto.addVeto(createUser(123)));
        comments.add(Veto.addVeto(createUser(456)));
        assertEquals(Set.of("123", "456"), Veto.vetoers(createUser(0), comments.get()));
    }

    @Test
    void removedVeto() {
        var comments = new Comments();
        comments.add(Veto.addVeto(createUser(123)));
        comments.add(Veto.addVeto(createUser(456)));
        comments.add(Veto.removeVeto(createUser(123)));
        assertEquals(Set.of("456"), Veto.vetoers(createUser(0), comments.get()));
    }

    @Test
    @Disabled
    void selfVeto(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.host().currentUser().id());
            var prBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/reject");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("reject your own changes"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    @Disabled
    void mayNotVeto(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var vetoer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.host().currentUser().id())
                                           .addCommitter(vetoer.host().currentUser().id());
            var prBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Try to veto as a non committer
            var vetoPr = vetoer.pullRequest(pr.id());
            vetoPr.addComment("/reject");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("are allowed to reject"))
                          .count();
            assertEquals(1, error);
        }
    }

    @Test
    @Disabled
    void vetoAndMerge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var vetoer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.host().currentUser().id())
                                           .addReviewer(vetoer.host().currentUser().id());

            var prBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Place a veto
            var vetoPr = vetoer.pullRequest(pr.id());
            vetoPr.addReview(Review.Verdict.APPROVED, "Approved");
            vetoPr.addComment("/reject");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should acknowledge
            var ack = pr.comments().stream()
                        .filter(comment -> comment.body().contains("cannot be integrated"))
                        .count();
            assertEquals(1, ack);

            // Now try to integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // There should be another error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("change is currently blocked"))
                          .count();
            assertEquals(1, error);

            // Now drop the veto
            vetoPr.addComment("/allow");
            TestBotRunner.runPeriodicItems(prBot);

            // There should be an acknowledgement
            var approve = pr.comments().stream()
                            .filter(comment -> comment.body().contains("now allowed to be integrated"))
                            .count();
            assertEquals(1, approve);

            // Now try to integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            var pushed = pr.comments().stream()
                           .filter(comment -> comment.body().contains("Pushed as commit"))
                           .count();
            assertEquals(1, pushed);
        }
    }

    @Test
    @Disabled
    void vetoAndSponsor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var vetoer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().currentUser().id())
                                           .addReviewer(vetoer.host().currentUser().id());

            var prBot = new PullRequestBot(integrator, censusBuilder.build(), "master");

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Place a veto
            var vetoPr = vetoer.pullRequest(pr.id());
            vetoPr.addReview(Review.Verdict.APPROVED, "Approved");
            vetoPr.addComment("/reject");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should acknowledge
            var ack = pr.comments().stream()
                        .filter(comment -> comment.body().contains("cannot be integrated"))
                        .count();
            assertEquals(1, ack);

            // Author makes the PR ready for sponsoring
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should acknowledge
            var ready = pr.comments().stream()
                          .filter(comment -> comment.body().contains("sponsor"))
                          .filter(comment -> comment.body().contains("your change"))
                          .count();
            assertEquals(1, ready);

            vetoPr.addComment("/sponsor");
            TestBotRunner.runPeriodicItems(prBot);

            // There should be another error message
            var error = pr.comments().stream()
                          .filter(comment -> comment.body().contains("change is currently blocked"))
                          .count();
            assertEquals(1, error);
        }
    }
}
