/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.census;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProjectTests {
    private static final Group group = new Group("testgroup", "Test Group",
                                                 new Contributor("user_5", "User Number 5"),
                                                 List.of());
    @Test
    void testIsLead() {
        var leader = new Member(new Contributor("user_1", "User Number 1"), 1, 2);
        var project = new Project("jdk", "JDK", group, List.of(leader), List.of(), List.of(), List.of());
        assertTrue(project.isLead("user_1", 1));
        assertFalse(project.isLead("user_1", 2));
        assertFalse(project.isLead("user_1", 0));

        assertFalse(project.isLead("foo", 1));
    }

    @Test
    void testOngoingLeader() {
        var leader = new Member(new Contributor("user_1", "User Number 1"), 1);
        var project = new Project("jdk", "JDK", group, List.of(leader), List.of(), List.of(), List.of());
        assertFalse(project.isLead("user_1", 0));
        assertTrue(project.isLead("user_1", 1));
        assertTrue(project.isLead("user_1", 2));
        assertTrue(project.isLead("user_1", 3));
        assertTrue(project.isLead("user_1", 4));
    }

    @Test
    void testMultipleLeaders() {
        var leaders = List.of(new Member(new Contributor("user_1", "User Number 1"), 1, 2),
                              new Member(new Contributor("user_2", "User Number 2"), 2, 3));
        var project = new Project("jdk", "JDK", group, leaders, List.of(), List.of(), List.of());
        assertFalse(project.isLead("user_1", 0));
        assertFalse(project.isLead("user_2", 0));

        assertTrue(project.isLead("user_1", 1));
        assertFalse(project.isLead("user_2", 1));

        assertFalse(project.isLead("user_1", 2));
        assertTrue(project.isLead("user_2", 2));

        assertFalse(project.isLead("user_1", 3));
        assertFalse(project.isLead("user_2", 3));
    }

    @Test
    void testLeader() {
        var leader = new Member(new Contributor("user_1", "User Number 1"), 1, 2);
        var project = new Project("jdk", "JDK", group, List.of(leader), List.of(), List.of(), List.of());
        assertNull(project.lead(0));
        assertEquals(new Contributor("user_1", "User Number 1"), project.lead(1));
        assertNull(project.lead(2));
    }

    private Project sampleSingletonProject() {
        var leader = new Member(new Contributor("user_1", "User Number 1"), 1);
        var reviewer = new Member(new Contributor("user_2", "User Number 2"), 1);
        var committer = new Member(new Contributor("user_3", "User Number 3"), 1);
        var author = new Member(new Contributor("user_4", "User Number 4"), 1);

        return new Project("jdk", "JDK", group,
                           List.of(leader),
                           List.of(reviewer),
                           List.of(committer),
                           List.of(author));
    }

    @Test
    void testIsReviewer() {
        var project = sampleSingletonProject();

        assertFalse(project.isReviewer("user_1", 0));
        assertFalse(project.isReviewer("user_2", 0));
        assertFalse(project.isReviewer("user_3", 0));
        assertFalse(project.isReviewer("user_4", 0));


        assertTrue(project.isReviewer("user_1", 1));
        assertTrue(project.isReviewer("user_2", 1));
        assertFalse(project.isReviewer("user_3", 1));
        assertFalse(project.isReviewer("user_4", 1));

        assertFalse(project.isReviewer("foo", 1));
    }

    @Test
    void testReviewers() {
        var project = sampleSingletonProject();

        var expected = Set.of(new Contributor("user_1", "User Number 1"),
                              new Contributor("user_2", "User Number 2"));
        var actual = Set.copyOf(project.reviewers(1));

        assertEquals(expected, actual);
    }

    @Test
    void testIsCommitter() {
        var project = sampleSingletonProject();

        assertFalse(project.isCommitter("user_1", 0));
        assertFalse(project.isCommitter("user_2", 0));
        assertFalse(project.isCommitter("user_3", 0));
        assertFalse(project.isCommitter("user_4", 0));


        assertTrue(project.isCommitter("user_1", 1));
        assertTrue(project.isCommitter("user_2", 1));
        assertTrue(project.isCommitter("user_3", 1));
        assertFalse(project.isCommitter("user_4", 1));

        assertFalse(project.isCommitter("foo", 1));
    }

    @Test
    void testCommitters() {
        var project = sampleSingletonProject();

        var expected = Set.of(new Contributor("user_1", "User Number 1"),
                              new Contributor("user_2", "User Number 2"),
                              new Contributor("user_3", "User Number 3"));
        var actual = Set.copyOf(project.committers(1));

        assertEquals(expected, actual);
    }

    @Test
    void testIsAuthor() {
        var project = sampleSingletonProject();

        assertFalse(project.isAuthor("user_1", 0));
        assertFalse(project.isAuthor("user_2", 0));
        assertFalse(project.isAuthor("user_3", 0));
        assertFalse(project.isAuthor("user_4", 0));


        assertTrue(project.isAuthor("user_1", 1));
        assertTrue(project.isAuthor("user_2", 1));
        assertTrue(project.isAuthor("user_3", 1));
        assertTrue(project.isAuthor("user_4", 1));

        assertFalse(project.isAuthor("foo", 1));
    }
}
