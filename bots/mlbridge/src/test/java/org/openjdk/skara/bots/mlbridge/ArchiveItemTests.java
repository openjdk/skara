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
package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.test.HostCredentials;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ArchiveItemTests {
    private int curId = 0;

    private Comment createComment(HostUser user, String body) {
        return new Comment(Integer.toString(curId++), body, user, ZonedDateTime.now(), ZonedDateTime.now());
    }

    private ArchiveItem fromComment(PullRequest pr, Comment comment) {
        return ArchiveItem.from(pr, comment, null, null);
    }

    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var repo = credentials.getHostedRepository();
            var pr = credentials.createPullRequest(repo, "master", "master", "Test");

            var user1 = HostUser.create("1", "user1", "User Uno");
            var user2 = HostUser.create("2", "user2", "User Duo");
            var user3 = HostUser.create("3", "user3", "User Trio");

            var c1 = createComment(user1, "First comment\nwith two lines");
            var c2 = createComment(user2, "Second comment");

            var a1 = fromComment(pr, c1);
            var a2 = fromComment(pr, c2);

            assertEquals(a2, ArchiveItem.findParent(List.of(a1, a2), createComment(user3, "Plain reply")));

            assertEquals(a1, ArchiveItem.findParent(List.of(a1, a2), createComment(user3, "> First comment\n\nI agree")));
            assertEquals(a1, ArchiveItem.findParent(List.of(a1, a2), createComment(user3, "> First comment\n>with two lines\n\nI agree")));
            assertEquals(a1, ArchiveItem.findParent(List.of(a1, a2), createComment(user3, "\n> First comment\n\nI agree")));

            assertEquals(a1, ArchiveItem.findParent(List.of(a1, a2), createComment(user3, "@user1 I agree")));
            assertEquals(a1, ArchiveItem.findParent(List.of(a1, a2), createComment(user3, "@user1\nI agree")));
        }
    }
}
