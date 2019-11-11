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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.CheckStatus;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.ci.Job;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestWorkItemTests {
    @Test
    void noTestCommentsShouldDoNothing() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.comments = List.of();

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);
            item.run(scratch);

            var comments = pr.comments();
            assertEquals(0, comments.size());
        }
    }

    @Test
    void topLevelTestApproveShouldDoNothing() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            var now = ZonedDateTime.now();
            pr.author = duke;
            var testApproveComment = new Comment("0", "/test approve", duke, now, now);
            pr.comments = List.of(testApproveComment);

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);
            item.run(scratch);

            var comments = pr.comments();
            assertEquals(1, comments.size());
            assertEquals(testApproveComment, comments.get(0));
        }
    }

    @Test
    void topLevelTestCancelShouldDoNothing() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            var now = ZonedDateTime.now();
            pr.author = duke;
            var testApproveComment = new Comment("0", "/test cancel", duke, now, now);
            pr.comments = List.of(testApproveComment);

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);
            item.run(scratch);

            var comments = pr.comments();
            assertEquals(1, comments.size());
            assertEquals(testApproveComment, comments.get(0));
        }
    }

    @Test
    void testCommentWithMadeUpJobShouldBeError() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of("0", Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test foobar", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);
            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));

            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals(2, lines.length);
            assertEquals("<!-- TEST ERROR -->", lines[0]);
            assertEquals("@duke the test group foobar does not exist", lines[1]);
        }
    }

    @Test
    void testCommentFromUnapprovedUserShouldBePending() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of("0", Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = new Hash("01234567890123456789012345789012345789");

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test foobar", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            // Non-existing test group should result in error
            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));

            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals(2, lines.length);
            assertEquals("<!-- TEST ERROR -->", lines[0]);
            assertEquals("@duke the test group foobar does not exist", lines[1]);

            // Trying to test again should be fine
            var thirdComment = new Comment("2", "/test tier1", duke, now, now);
            pr.comments.add(thirdComment);
            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(thirdComment, comments.get(2));

            var fourthComment = comments.get(3);
            assertEquals(bot, fourthComment.author());

            lines = fourthComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- 01234567890123456789012345789012345789 -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until 01234567",
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(thirdComment, comments.get(2));
            assertEquals(fourthComment, comments.get(3));
        }
    }

    @Test
    void cancelAtestCommentShouldBeCancel() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of("0", Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = new Hash("01234567890123456789012345789012345789");

            var now = ZonedDateTime.now();
            var testComment = new Comment("0", "/test tier1", duke, now, now);
            var cancelComment = new Comment("1", "/test cancel", duke, now, now);
            pr.comments = new ArrayList<>(List.of(testComment, cancelComment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(testComment, comments.get(0));
            assertEquals(cancelComment, comments.get(1));
        }
    }

    @Test
    void cancellingAPendingTestCommentShouldWork() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of(approvers, Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = new Hash("01234567890123456789012345789012345789");

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test tier1", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- 01234567890123456789012345789012345789 -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until 01234567",
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));

            // Cancelling the test now should be fine
            var cancelComment = new Comment("2", "/test cancel", duke, now, now);
            pr.comments.add(cancelComment);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(3, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(cancelComment, comments.get(2));

            // Approving the test should not start a job, it has already been cancelled
            var member = new HostUser(3, "foo", "Foo Bar");
            host.groups = Map.of(approvers, Set.of(member));
            var approveComment = new Comment("3", "/test approve", member, now, now);
            pr.comments.add(approveComment);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(cancelComment, comments.get(2));
            assertEquals(approveComment, comments.get(3));
        }
    }

    @Test
    void cancellingApprovedPendingRequestShouldBeCancelled() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of(approvers, Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = new Hash("01234567890123456789012345789012345789");

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test tier1", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- 01234567890123456789012345789012345789 -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until 01234567",
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));

            // Approve the request
            var member = new HostUser(2, "foo", "Foo Bar");
            host.groups = Map.of(approvers, Set.of(member));
            var approveComment = new Comment("2", "/test approve", member, now, now);
            pr.comments.add(approveComment);

            // Cancelling the request
            var cancelComment = new Comment("2", "/test cancel", duke, now, now);
            pr.comments.add(cancelComment);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(approveComment, comments.get(2));
            assertEquals(cancelComment, comments.get(3));
        }
    }

    @Test
    void approvedPendingRequestShouldBeStarted() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var localRepoDir = tmp.path().resolve("repository.git");
            var localRepo = Repository.init(localRepoDir, VCS.GIT);
            var readme = localRepoDir.resolve("README");
            Files.writeString(readme, "Hello\n");
            localRepo.add(readme);
            var head = localRepo.commit("Add README", "duke", "duke@openjdk.org");

            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of(approvers, Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;
            repo.webUrl = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.url = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.id = 1337L;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;
            pr.id = "17";
            pr.targetRef = "master";

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = head;

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test tier1", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- " + head.hex() + " -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until " + head.abbreviate(),
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));

            // Approve the request
            var member = new HostUser(2, "foo", "Foo Bar");
            host.groups = Map.of(approvers, Set.of(member));
            var approveComment = new Comment("2", "/test approve", member, now, now);
            pr.comments.add(approveComment);

            var expectedJobId = "null-1337-17-0";
            var expectedJob = new InMemoryJob();
            expectedJob.status = new Job.Status(0, 1, 7);
            ci.jobs.put(expectedJobId, expectedJob);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(approveComment, comments.get(2));

            var fourthComment = comments.get(3);
            lines = fourthComment.body().split("\n");
            assertEquals("<!-- TEST STARTED -->", lines[0]);
            assertEquals("<!-- " + expectedJobId + " -->", lines[1]);
            assertEquals("<!-- " + head.hex() + " -->", lines[2]);
            assertEquals("A test job has been started with id: " + expectedJobId, lines[3]);

            assertEquals(1, ci.submissions.size());
            var submission = ci.submissions.get(0);
            assertTrue(submission.source.startsWith(storage));
            assertEquals(List.of("tier1"), submission.jobs);
            assertEquals(expectedJobId, submission.id);

            var checks = pr.checks(pr.headHash());
            assertEquals(1, checks.keySet().size());
            var check = checks.get("test");
            assertEquals("Summary", check.title().get());
            assertTrue(check.summary()
                            .get()
                            .contains("0 jobs completed, 1 job running, 7 jobs not yet started"));
        }
    }

    @Test
    void cancellingApprovedPendingRequestShouldBeCancel() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var localRepoDir = tmp.path().resolve("repository.git");
            var localRepo = Repository.init(localRepoDir, VCS.GIT);
            var readme = localRepoDir.resolve("README");
            Files.writeString(readme, "Hello\n");
            localRepo.add(readme);
            var head = localRepo.commit("Add README", "duke", "duke@openjdk.org");

            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of(approvers, Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;
            repo.webUrl = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.url = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.id = 1337L;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;
            pr.id = "17";
            pr.targetRef = "master";

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = head;

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test tier1", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- " + head.hex() + " -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until " + head.abbreviate(),
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));

            // Approve the request
            var member = new HostUser(2, "foo", "Foo Bar");
            host.groups = Map.of(approvers, Set.of(member));
            var approveComment = new Comment("2", "/test approve", member, now, now);
            pr.comments.add(approveComment);

            var expectedJobId = "null-1337-17-0";
            var expectedJob = new InMemoryJob();
            expectedJob.status = new Job.Status(0, 1, 7);
            ci.jobs.put(expectedJobId, expectedJob);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(approveComment, comments.get(2));

            var fourthComment = comments.get(3);
            lines = fourthComment.body().split("\n");
            assertEquals("<!-- TEST STARTED -->", lines[0]);
            assertEquals("<!-- " + expectedJobId + " -->", lines[1]);
            assertEquals("<!-- " + head.hex() + " -->", lines[2]);
            assertEquals("A test job has been started with id: " + expectedJobId, lines[3]);

            assertEquals(1, ci.submissions.size());
            var submission = ci.submissions.get(0);
            assertTrue(submission.source.startsWith(storage));
            assertEquals(List.of("tier1"), submission.jobs);
            assertEquals(expectedJobId, submission.id);

            var checks = pr.checks(pr.headHash());
            assertEquals(1, checks.keySet().size());
            var check = checks.get("test");
            assertEquals("Summary", check.title().get());
            assertEquals(CheckStatus.IN_PROGRESS, check.status());
            assertTrue(check.summary()
                            .get()
                            .contains("## Status\n0 jobs completed, 1 job running, 7 jobs not yet started\n"));

            var cancelComment = new Comment("4", "/test cancel", duke, now, now);
            pr.comments.add(cancelComment);

            item.run(scratch);

            checks = pr.checks(pr.headHash());
            assertEquals(1, checks.keySet().size());
            check = checks.get("test");
            assertEquals("Summary", check.title().get());
            assertEquals(CheckStatus.CANCELLED, check.status());
            assertTrue(check.summary()
                            .get()
                            .contains("## Status\n0 jobs completed, 1 job running, 7 jobs not yet started\n"));

            assertEquals(expectedJobId, ci.cancelled.get(0));
        }
    }

    @Test
    void errorWhenCreatingTestJobShouldResultInError() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var localRepoDir = tmp.path().resolve("repository.git");
            var localRepo = Repository.init(localRepoDir, VCS.GIT);
            var readme = localRepoDir.resolve("README");
            Files.writeString(readme, "Hello\n");
            localRepo.add(readme);
            var head = localRepo.commit("Add README", "duke", "duke@openjdk.org");

            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of(approvers, Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;
            repo.webUrl = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.url = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.id = 1337L;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;
            pr.id = "17";
            pr.targetRef = "master";

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = head;

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test tier1", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- " + head.hex() + " -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until " + head.abbreviate(),
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));

            // Approve the request
            var member = new HostUser(2, "foo", "Foo Bar");
            host.groups = Map.of(approvers, Set.of(member));
            var approveComment = new Comment("2", "/test approve", member, now, now);
            pr.comments.add(approveComment);

            ci.throwOnSubmit = true;
            assertThrows(UncheckedIOException.class, () -> item.run(scratch));

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(approveComment, comments.get(2));

            var fifthComment = comments.get(3);
            lines = fifthComment.body().split("\n");
            assertEquals("<!-- TEST ERROR -->", lines[0]);
            assertEquals("Could not create test job", lines[1]);
        }
    }

    @Test
    void finishedJobShouldResultInFinishedComment() throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var localRepoDir = tmp.path().resolve("repository.git");
            var localRepo = Repository.init(localRepoDir, VCS.GIT);
            var readme = localRepoDir.resolve("README");
            Files.writeString(readme, "Hello\n");
            localRepo.add(readme);
            var head = localRepo.commit("Add README", "duke", "duke@openjdk.org");

            var ci = new InMemoryContinuousIntegration();
            var approvers = "0";
            var available = List.of("tier1", "tier2", "tier3");
            var defaultJobs = List.of("tier1");
            var name = "test";
            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("storage");

            var bot = new HostUser(1, "bot", "openjdk [bot]");
            var host = new InMemoryHost();
            host.currentUserDetails = bot;
            host.groups = Map.of(approvers, Set.of());

            var repo = new InMemoryHostedRepository();
            repo.host = host;
            repo.webUrl = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.url = URI.create("file://" + localRepoDir.toAbsolutePath());
            repo.id = 1337L;

            var pr = new InMemoryPullRequest();
            pr.repository = repo;
            pr.id = "17";
            pr.targetRef = "master";

            var duke = new HostUser(0, "duke", "Duke");
            pr.author = duke;
            pr.headHash = head;

            var now = ZonedDateTime.now();
            var comment = new Comment("0", "/test tier1", duke, now, now);
            pr.comments = new ArrayList<>(List.of(comment));

            var item = new TestWorkItem(ci, approvers, available, defaultJobs, name, storage, pr);

            item.run(scratch);

            var comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            var secondComment = comments.get(1);
            assertEquals(bot, secondComment.author());

            var lines = secondComment.body().split("\n");
            assertEquals("<!-- TEST PENDING -->", lines[0]);
            assertEquals("<!-- " + head.hex() + " -->", lines[1]);
            assertEquals("<!-- tier1 -->", lines[2]);
            assertEquals("@duke you need to get approval to run the tests in tier1 for commits up until " + head.abbreviate(),
                         lines[3]);

            // Nothing should change if we run it yet again
            item.run(scratch);

            comments = pr.comments();
            assertEquals(2, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));

            // Approve the request
            var member = new HostUser(2, "foo", "Foo Bar");
            host.groups = Map.of(approvers, Set.of(member));
            var approveComment = new Comment("2", "/test approve", member, now, now);
            pr.comments.add(approveComment);

            var expectedJobId = "null-1337-17-0";
            var expectedJob = new InMemoryJob();
            expectedJob.status = new Job.Status(0, 1, 7);
            ci.jobs.put(expectedJobId, expectedJob);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(4, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(approveComment, comments.get(2));

            var fourthComment = comments.get(3);
            lines = fourthComment.body().split("\n");
            assertEquals("<!-- TEST STARTED -->", lines[0]);
            assertEquals("<!-- " + expectedJobId + " -->", lines[1]);
            assertEquals("<!-- " + head.hex() + " -->", lines[2]);
            assertEquals("A test job has been started with id: " + expectedJobId, lines[3]);

            assertEquals(1, ci.submissions.size());
            var submission = ci.submissions.get(0);
            assertTrue(submission.source.startsWith(storage));
            assertEquals(List.of("tier1"), submission.jobs);
            assertEquals(expectedJobId, submission.id);

            var checks = pr.checks(pr.headHash());
            assertEquals(1, checks.keySet().size());
            var check = checks.get("test");
            assertEquals("Summary", check.title().get());
            assertEquals(CheckStatus.IN_PROGRESS, check.status());
            assertTrue(check.summary()
                            .get()
                            .contains("0 jobs completed, 1 job running, 7 jobs not yet started"));

            var job = ci.jobs.get(expectedJobId);
            assertNotNull(job);
            job.id = "id";
            job.state = Job.State.COMPLETED;
            job.status = new Job.Status(8, 0, 0);
            job.result = new Job.Result(8, 0, 0);

            item.run(scratch);

            comments = pr.comments();
            assertEquals(5, comments.size());
            assertEquals(comment, comments.get(0));
            assertEquals(secondComment, comments.get(1));
            assertEquals(approveComment, comments.get(2));
            assertEquals(fourthComment, comments.get(3));

            var finishedComment = comments.get(4);
            lines = finishedComment.body().split("\n");
            assertEquals("<!-- TEST FINISHED -->", lines[0]);
            assertEquals("<!-- " + expectedJobId +" -->", lines[1]);
            assertEquals("<!-- " + head.hex() +" -->", lines[2]);
            assertEquals("@duke your test job with id " + expectedJobId + " for commits up until " +
                         head.abbreviate() + " has finished.", lines[3]);

            checks = pr.checks(pr.headHash());
            assertEquals(1, checks.keySet().size());
            check = checks.get("test");
            assertEquals("Summary", check.title().get());
            assertEquals(CheckStatus.SUCCESS, check.status());

            var summaryLines = check.summary().get().split("\n");
            assertEquals("## Id", summaryLines[0]);
            assertEquals("`id`", summaryLines[1]);
            assertEquals("", summaryLines[2]);
            assertEquals("## Builds", summaryLines[3]);
            assertEquals("", summaryLines[4]);
            assertEquals("## Tests", summaryLines[5]);
            assertEquals("", summaryLines[6]);
            assertEquals("## Status", summaryLines[7]);
            assertEquals("8 jobs completed, 0 jobs running, 0 jobs not yet started", summaryLines[8]);
            assertEquals("", summaryLines[9]);
            assertEquals("## Result", summaryLines[10]);
            assertEquals("8 jobs passed, 0 jobs with failures, 0 jobs not run", summaryLines[11]);
        }
    }
}
