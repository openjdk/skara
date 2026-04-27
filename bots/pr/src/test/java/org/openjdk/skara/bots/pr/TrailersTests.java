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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;
import org.openjdk.skara.test.TestHostedRepository;
import org.openjdk.skara.test.TestPullRequest;
import org.openjdk.skara.test.TestPullRequestStore;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.Commits;
import org.openjdk.skara.vcs.Repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.test.TestHost.FAKE_REPO;

public class TrailersTests {

    @Test
    public void rightUser() {
        var user1 = new HostUser.Builder().id(17).build();
        var user2 = new HostUser.Builder().id(4711).build();

        var comments = new ArrayList<Comment>();
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "First value"),
                user1, null, null));
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "False value"),
                user2, null, null));

        var trailers = Trailers.trailers(user1, comments);
        assertEquals(1, trailers.size());
        var trailer1 = trailers.getFirst();
        assertEquals("Trailer-1", trailer1.key());
        assertEquals("First value", trailer1.value());
    }

    @Test
    public void override() {
        var user1 = new HostUser.Builder().id(17).build();

        var comments = new ArrayList<Comment>();
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "First value"),
                user1, null, null));
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "Second value") + " More text",
                user1, null, null));

        var trailers = Trailers.trailers(user1, comments);
        assertEquals(1, trailers.size());
        var trailer1 = trailers.getFirst();
        assertEquals("Trailer-1", trailer1.key());
        assertEquals("Second value", trailer1.value());
    }

    @Test
    public void remove() {
        var user1 = new HostUser.Builder().id(17).build();

        var comments = new ArrayList<Comment>();
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "First value"),
                user1, null, null));
        comments.add(new Comment("1", Trailers.removeTrailerMarker("Trailer-1"),
                user1, null, null));

        var trailers = Trailers.trailers(user1, comments);
        assertEquals(0, trailers.size());
    }

    @Test
    public void multiple() {
        var user1 = new HostUser.Builder().id(17).build();

        var comments = new ArrayList<Comment>();
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "First value"),
                user1, null, null));
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-2", "1st value"),
                user1, null, null));
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-1", "Second value"),
                user1, null, null));
        comments.add(new Comment("1", "Text " + Trailers.setTrailerMarker("Trailer-2", "2nd value"),
                user1, null, null));

        var trailers = Trailers.trailers(user1, comments);
        assertEquals(2, trailers.size());
        var trailer1 = trailers.getFirst();
        assertEquals("Trailer-1", trailer1.key());
        assertEquals("Second value", trailer1.value());
        var trailer2 = trailers.get(1);
        assertEquals("Trailer-2", trailer2.key());
        assertEquals("2nd value", trailer2.value());
    }

    @Test
    public void commandSet(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set Trailer-1 foo", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer `Trailer-1` with value `foo` successfully set"), reply.toString());
        }
    }

    @Test
    public void commandSetListTypeValid(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.LIST,
                                    List.of(Pattern.compile("foo-[0-9]"), Pattern.compile("bar-[0-9]")))))
                    .build();
            var author = new HostUser.Builder().id("17").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set Trailer-1 foo-1, bar-2", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer `Trailer-1` with value `foo-1, bar-2` successfully set"), reply.toString());

            command = new CommandInvocation("2", author, new TrailerCommand(), "trailer", "set Trailer-1 bar-3", null);
            reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer `Trailer-1` with value `bar-3` successfully set"), reply.toString());
        }
    }

    @Test
    public void commandSetListTypeInvalid(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.LIST,
                                    List.of(Pattern.compile("foo-[0-9]"), Pattern.compile("bar-[0-9]")))))
                    .build();
            var author = new HostUser.Builder().id("17").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set Trailer-1 foo-1, bar-b", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("does not match any valid value pattern"), reply.toString());
            assertTrue(reply.toString().contains("- `foo-[0-9]`"), reply.toString());
            assertTrue(reply.toString().contains("- `bar-[0-9]`"), reply.toString());
        }
    }

    @Test
    public void commandInvalidValue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile("foo")))))
                    .build();
            var author = new HostUser.Builder().id("17").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set Trailer-1 invalid", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer value `invalid` for trailer `Trailer-1` does not match any valid value pattern:"), reply.toString());
            assertTrue(reply.toString().contains("- `foo`"));
        }
    }

    @Test
    public void commandSetAlias(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set 1 foo", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer `Trailer-1` with value `foo` successfully set"), reply.toString());
        }
    }

    @Test
    public void commandNotAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var otherUser = new HostUser.Builder().id("4711").username("other").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", otherUser, new TrailerCommand(), "trailer", "set 1 foo", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Only the author"), reply.toString());
        }
    }

    @Test
    public void commandHelp(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Syntax"), reply.toString());
            assertTrue(reply.toString().contains("For this repository, the following custom trailers have been configured:"), reply.toString());
            assertTrue(reply.toString().contains("Key: Trailer-1"), reply.toString());
        }
    }

    @Test
    public void commandSetInvalid(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set foo bar", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("is not configured for this repository"), reply.toString());
            assertTrue(reply.toString().contains("For this repository, the following custom trailers have been configured:"), reply.toString());
            assertTrue(reply.toString().contains("- Key: Trailer-1"), reply.toString());
            assertTrue(reply.toString().contains("- Alias: 1"), reply.toString());
            assertTrue(reply.toString().contains("- Description: Trailer description"), reply.toString());
        }
    }

    @Test
    public void commandSetNoneConfigured(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of())
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "set foo bar", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("There are no custom trailers configured for this repository"), reply.toString());
        }
    }

    @Test
    public void commandRemoveInvalid(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "remove foo", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("is not configured for this repository"), reply.toString());
            assertTrue(reply.toString().contains("For this repository, the following custom trailers have been configured:"), reply.toString());
            assertTrue(reply.toString().contains("Key: Trailer-1"), reply.toString());
        }
    }

    @Test
    public void commandRemoveNotSet(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "remove Trailer-1", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, List.of(), new PrintWriter(reply));

            assertTrue(reply.toString().contains("There are no custom trailers set for this pull request"), reply.toString());
        }
    }

    @Test
    public void commandRemoveOtherSet(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*"))),
                            new TrailerCommand.TrailerConfig("Trailer-2", "2", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "remove Trailer-1", null);

            var reply = new StringWriter();
            var comments = List.of(new Comment("1", Trailers.setTrailerMarker("Trailer-2", "bar"), repo.forge().currentUser(), null,null));
            new TrailerCommand().handle(prBot, pr, null, null, command, comments, new PrintWriter(reply));

            assertTrue(reply.toString().contains("was not found"), reply.toString());
            assertTrue(reply.toString().contains("Current custom trailers for this pull request are:"), reply.toString());
            assertTrue(reply.toString().contains("- Trailer-2: bar"), reply.toString());
        }
    }

    @Test
    public void commandRemoveNoneConfigured(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of())
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "remove foo", null);

            var reply = new StringWriter();
            new TrailerCommand().handle(prBot, pr, null, null, command, null, new PrintWriter(reply));

            assertTrue(reply.toString().contains("There are no custom trailers configured for this repository"), reply.toString());
        }
    }

    @Test
    public void commandRemove(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*"))),
                            new TrailerCommand.TrailerConfig("Trailer-2", "2", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "remove Trailer-1", null);

            var reply = new StringWriter();
            var comments = List.of(new Comment("1", Trailers.setTrailerMarker("Trailer-1", "bar"), repo.forge().currentUser(), null,null));
            new TrailerCommand().handle(prBot, pr, null, null, command, comments, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer `Trailer-1` successfully removed"), reply.toString());
        }
    }

    @Test
    public void commandRemoveAlias(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = (TestHostedRepository) credentials.getHostedRepository(FAKE_REPO);
            var prBot = PullRequestBot.newBuilder()
                    .repo(repo)
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*"))),
                            new TrailerCommand.TrailerConfig("Trailer-2", "2", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*")))))
                    .build();
            var author = new HostUser.Builder().id("17").username("author").build();
            var pr = new TestPullRequest(new TestPullRequestStore(null, author, null, List.of(), repo, null, null, false), repo);
            var command = new CommandInvocation("1", author, new TrailerCommand(), "trailer", "remove 1", null);

            var reply = new StringWriter();
            var comments = List.of(new Comment("1", Trailers.setTrailerMarker("Trailer-1", "bar"), repo.forge().currentUser(), null,null));
            new TrailerCommand().handle(prBot, pr, null, null, command, comments, new PrintWriter(reply));

            assertTrue(reply.toString().contains("Trailer `Trailer-1` successfully removed"), reply.toString());
        }
    }

    @Test
    public void runWithBot(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
                var tempFolder = new TemporaryDirectory()) {
            var bot = credentials.getHostedRepository();
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                    .addReviewer(reviewer.forge().currentUser().id())
                    .addCommitter(author.forge().currentUser().id());
            var prBot = PullRequestBot.newBuilder()
                    .repo(bot)
                    .censusRepo(censusBuilder.build())
                    .trailerConfigs(List.of(
                            new TrailerCommand.TrailerConfig("Trailer-1", "1", "Trailer description",
                                    TrailerCommand.TrailerType.SINGLE, List.of(Pattern.compile(".*value")))))
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
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Issue an invalid command
            pr.addComment("/trailer hello");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"Syntax");

            // Issue command as other user
            var approvalPr = reviewer.pullRequest(pr.id());
            approvalPr.addComment("/trailer set Trailer-1 invalid value");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error
            assertLastCommentContains(approvalPr,"Only the author");

            // Try setting a value that does not match the values regexp
            pr.addComment("/trailer set Trailer-1 something not valid");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error
            assertLastCommentContains(approvalPr,"does not match any valid value pattern");

            // Set a valid trailer
            pr.addComment("/trailer set Trailer-1 first value");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"successfully set");

            // Remove something that isn't there when there is another trailer set
            pr.addComment("/trailer remove Unknown-Trailer");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"is not configured for this repository");

            // Remove the set trailer
            pr.addComment("/trailer remove Trailer-1");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with a success message
            assertLastCommentContains(pr,"successfully removed");

            // Remove something that isn't there when no trailer has been set
            pr.addComment("/trailer remove Unknown-Trailer");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an error message
            assertLastCommentContains(pr,"is not configured for this repository");

            // Set the trailer again
            pr.addComment("/trailer set Trailer-1 second value");
            TestBotRunner.runPeriodicItems(prBot);

            // Overwrite it with a new value
            pr.addComment("/trailer set Trailer-1 third value");
            TestBotRunner.runPeriodicItems(prBot);

            // Approve it as another user
            approvalPr.addReview(Review.Verdict.APPROVED, "Approved");
            TestBotRunner.runPeriodicItems(prBot);

            // The commit message preview should contain the trailer
            var creditLine = pr.comments().stream()
                    .flatMap(comment -> comment.body().lines())
                    .filter(line -> line.contains("third value"))
                    .filter(line -> line.contains("Trailer-1"))
                    .findAny()
                    .orElseThrow();
            assertEquals("Trailer-1: third value", creditLine);

            var pushed = pr.comments().stream()
                    .filter(comment -> comment.body().contains("change now passes all *automated*"))
                    .count();
            assertEquals(1, pushed);

            // Change value again
            pr.addComment("/trailer set Trailer-1 4th value");
            TestBotRunner.runPeriodicItems(prBot);

            creditLine = pr.comments().stream()
                    .flatMap(comment -> comment.body().lines())
                    .filter(line -> line.contains("4th value"))
                    .filter(line -> line.contains("Trailer-1:"))
                    .findAny()
                    .orElseThrow();
            assertEquals("Trailer-1: 4th value", creditLine);

            // Integrate
            pr.addComment("/integrate");
            TestBotRunner.runPeriodicItems(prBot);

            // The bot should reply with an ok message
            assertLastCommentContains(pr,"Pushed as commit");

            // The change should now be present on the master branch
            var pushedFolder = tempFolder.path().resolve("pushed");
            var pushedRepo = Repository.materialize(pushedFolder, author.authenticatedUrl(), "master");
            assertTrue(CheckableRepository.hasBeenEdited(pushedRepo));

            var headHash = pushedRepo.resolve("HEAD").orElseThrow();
            Commit headCommit;
            try (Commits commits = pushedRepo.commits(headHash.hex() + "^.." + headHash.hex())) {
                headCommit = commits.asList().getFirst();
            }

            // The contributor should be credited
            creditLine = headCommit.message().stream()
                    .filter(line -> line.contains("4th value"))
                    .filter(line -> line.contains("Trailer-1:"))
                    .findAny()
                    .orElseThrow();
            assertEquals("Trailer-1: 4th value", creditLine);
        }
    }
}
