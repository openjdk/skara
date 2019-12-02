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

import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.host.HostUser;

import java.util.*;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateTests {
    @Test
    void noCommentsShouldEqualNA() {
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

        var state = State.from(pr, "0");
        assertEquals(Stage.NA, state.stage());
        assertEquals(null, state.requested());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void testCommentFromNotApprovedUserShouldEqualRequested() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var comment = new Comment("0", "/test tier1", duke, now, now);
        pr.comments = List.of(comment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of());

        var state = State.from(pr, approvers);
        assertEquals(Stage.REQUESTED, state.stage());
        assertEquals(comment, state.requested());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void testCommentFromApprovedUserShouldEqualApproved() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var comment = new Comment("0", "/test tier1", duke, now, now);
        pr.comments = List.of(comment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of(duke));

        var state = State.from(pr, approvers);
        assertEquals(Stage.APPROVED, state.stage());
        assertEquals(comment, state.requested());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void testApprovalNeededCommentShouldResultInPending() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var testComment = new Comment("0", "/test tier1", duke, now, now);

        var pendingBody = List.of(
            "<!-- TEST PENDING -->",
            "<!-- tier1 -->",
            "@duke you need to get approval to run these tests"
        );
        var pendingComment = new Comment("0", String.join("\n", pendingBody), bot, now, now);
        pr.comments = List.of(testComment, pendingComment);
        host.groups = Map.of("0", Set.of());

        var state = State.from(pr, "0");
        assertEquals(Stage.PENDING, state.stage());
        assertEquals(testComment, state.requested());
        assertEquals(pendingComment, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void testStartedCommentShouldResultInRunning() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var testComment = new Comment("0", "/test tier1", duke, now, now);

        var pendingBody = List.of(
            "<!-- TEST PENDING -->",
            "<!-- tier1 -->",
            "@duke you need to get approval to run these tests"
        );
        var pendingComment = new Comment("1", String.join("\n", pendingBody), bot, now, now);

        var member = new HostUser(2, "foo", "Foo Bar");
        var approveComment = new Comment("2", "/test approve", member, now, now);

        var startedBody = List.of(
            "<!-- TEST STARTED -->",
            "<!-- 0 -->",
            "A test job has been started with id 0"
        );
        var startedComment = new Comment("3", String.join("\n", startedBody), bot, now, now);

        pr.comments = List.of(testComment, pendingComment, approveComment, startedComment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of(member));

        var state = State.from(pr, approvers);
        assertEquals(Stage.STARTED, state.stage());
        assertEquals(testComment, state.requested());
        assertEquals(pendingComment, state.pending());
        assertEquals(startedComment, state.started());
    }

    @Test
    void cancelCommentFromAuthorShouldEqualCancelled() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var testComment = new Comment("0", "/test tier1", duke, now, now);
        var cancelComment = new Comment("1", "/test cancel", duke, now, now);
        pr.comments = List.of(testComment, cancelComment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of());

        var state = State.from(pr, approvers);
        assertEquals(Stage.CANCELLED, state.stage());
        assertEquals(testComment, state.requested());
        assertEquals(cancelComment, state.cancelled());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void cancelCommentFromAnotherUserShouldHaveNoEffect() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var user = new HostUser(0, "foo", "Foo Bar");

        var now = ZonedDateTime.now();
        var testComment = new Comment("0", "/test tier1", duke, now, now);
        var cancelComment = new Comment("1", "/test cancel", user, now, now);
        pr.comments = List.of(testComment, cancelComment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of());

        var state = State.from(pr, approvers);
        assertEquals(Stage.REQUESTED, state.stage());
        assertEquals(testComment, state.requested());
        assertEquals(null, state.cancelled());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void multipleTestCommentsShouldOnlyCareAboutLast() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var test1Comment = new Comment("0", "/test tier1", duke, now, now);
        var test2Comment = new Comment("1", "/test tier1,tier2", duke, now, now);
        var test3Comment = new Comment("2", "/test tier1,tier2,tier3", duke, now, now);
        pr.comments = List.of(test1Comment, test2Comment, test3Comment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of());

        var state = State.from(pr, approvers);
        assertEquals(Stage.REQUESTED, state.stage());
        assertEquals(test3Comment, state.requested());
        assertEquals(null, state.cancelled());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void errorAfterRequestedShouldBeError() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var testComment = new Comment("0", "/test tier1", duke, now, now);

        var lines = List.of(
            "<!-- TEST ERROR -->",
            "The test tier1 does not exist"
        );
        var errorComment = new Comment("2", String.join("\n", lines), bot, now, now);
        pr.comments = List.of(testComment, errorComment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of());

        var state = State.from(pr, approvers);
        assertEquals(Stage.ERROR, state.stage());
        assertEquals(testComment, state.requested());
        assertEquals(null, state.pending());
        assertEquals(null, state.started());
    }

    @Test
    void testFinishedCommentShouldResultInFinished() {
        var bot = new HostUser(1, "bot", "openjdk [bot]");

        var host = new InMemoryHost();
        host.currentUserDetails = bot;

        var repo = new InMemoryHostedRepository();
        repo.host = host;

        var pr = new InMemoryPullRequest();
        pr.repository = repo;

        var duke = new HostUser(0, "duke", "Duke");
        pr.author = duke;

        var now = ZonedDateTime.now();
        var testComment = new Comment("0", "/test tier1", duke, now, now);

        var pendingBody = List.of(
            "<!-- TEST PENDING -->",
            "<!-- tier1 -->",
            "@duke you need to get approval to run these tests"
        );
        var pendingComment = new Comment("1", String.join("\n", pendingBody), bot, now, now);

        var member = new HostUser(2, "foo", "Foo Bar");
        var approveComment = new Comment("2", "/test approve", member, now, now);

        var startedBody = List.of(
            "<!-- TEST STARTED -->",
            "<!-- 0 -->",
            "A test job has been started with id 0"
        );
        var startedComment = new Comment("3", String.join("\n", startedBody), bot, now, now);

        var finishedBody = List.of(
            "<!-- TEST FINISHED -->",
            "<!-- 0 -->",
            "A test job has been started with id 0"
        );
        var finishedComment = new Comment("4", String.join("\n", finishedBody), bot, now, now);

        pr.comments = List.of(testComment, pendingComment, approveComment, startedComment, finishedComment);

        var approvers = "0";
        host.groups = Map.of(approvers, Set.of(member));

        var state = State.from(pr, approvers);
        assertEquals(Stage.FINISHED, state.stage());
        assertEquals(testComment, state.requested());
        assertEquals(pendingComment, state.pending());
        assertEquals(startedComment, state.started());
        assertEquals(finishedComment, state.finished());
    }
}
