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
package org.openjdk.skara.vcs.openjdk;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommitMessageBuilderTests {
    @Test
    void commitMessageBuilderPlain() {
        var lines = CommitMessage.title("Simple commit")
                                 .format(CommitMessageFormatters.v1);
        assertEquals(List.of("Simple commit"), lines);
    }

    @Test
    void commitMessageBuilderReviewers() {
        var lines = CommitMessage.title("Simple commit")
                                 .reviewer("reviewer1")
                                 .reviewer("reviewer2")
                                 .format(CommitMessageFormatters.v1);
        assertEquals(List.of("Simple commit", "", "Reviewed-by: reviewer1, reviewer2"), lines);
    }

    @Test
    void commitMessageBuilderIssues() {
        var issues = List.of(new Issue("123", "First"), new Issue("456", "Second"));
        var lines = CommitMessage.title(issues)
                                 .format(CommitMessageFormatters.v1);
        assertEquals(List.of("123: First", "456: Second"), lines);
    }

    @Test
    void commitBuilderNullTitle() {
        String title = null;
        assertThrows(IllegalArgumentException.class, () -> CommitMessage.title(title));
    }

    @Test
    void commitBuilderEmptyTitle() {
        assertThrows(IllegalArgumentException.class, () -> CommitMessage.title(""));
    }

    @Test
    void commitBuilderBothTitleAndIssue() {
        assertThrows(IllegalArgumentException.class, () -> CommitMessage.title("test")
                                                                        .issue(new Issue("123", "test"))
                                                                        .format(CommitMessageFormatters.v1));
    }
}
