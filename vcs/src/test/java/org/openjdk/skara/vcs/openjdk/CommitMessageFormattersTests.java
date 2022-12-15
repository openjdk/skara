/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Hash;

public class CommitMessageFormattersTests {
    private CommitMessageFormatter v0() {
        return CommitMessageFormatters.v0;
    }

    private CommitMessageFormatter v1() {
        return CommitMessageFormatters.v1;
    }

    @Test
    void formatVersion0WithBugAndReviewer() {
        var lines = CommitMessage.title(new Issue("01234567", "A bug"))
                                 .reviewer("foo")
                                 .format(v0());
        assertEquals(List.of("01234567: A bug",
                             "Reviewed-by: foo"),
                     lines);
    }

    @Test
    void formatVersion0WithBugAndReviewerAndSummary() {
        var lines = CommitMessage.title(new Issue("01234567", "A bug"))
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .format(v0());
        assertEquals(List.of("01234567: A bug",
                             "Summary: A summary",
                             "Reviewed-by: foo"),
                     lines);
    }

    @Test
    void formatVersion0WithBugAndReviewerAndSummaryAndContributor() {
        var lines = CommitMessage.title(new Issue("01234567", "A bug"))
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .contributor(new Author("Baz Bar", "baz@bar.org"))
                                 .format(v0());
        assertEquals(List.of("01234567: A bug",
                             "Summary: A summary",
                             "Reviewed-by: foo",
                             "Contributed-by: Baz Bar <baz@bar.org>"),
                     lines);
    }

    @Test
    void formatVersion0WithMultipleContributors() {
        var lines = CommitMessage.title(new Issue("01234567", "A bug"))
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .contributors(new Author("Baz Bar", "baz@bar.org"),
                                               new Author("Foo Bar", "foo@bar.org"))
                                 .format(v0());
        assertEquals(List.of("01234567: A bug",
                             "Summary: A summary",
                             "Reviewed-by: foo",
                             "Contributed-by: Baz Bar <baz@bar.org>, Foo Bar <foo@bar.org>"),
                     lines);
    }

    @Test
    void formatVersion1WithBugAndReviewer() {
        var lines = CommitMessage.title("01234567: A bug")
                                 .reviewer("foo")
                                 .format(v1());
        assertEquals(List.of("01234567: A bug",
                             "",
                             "Reviewed-by: foo"),
                     lines);
    }

    @Test
    void formatVersion1WithBugAndReviewerAndSummary() {
        var lines = CommitMessage.title("01234567: A bug")
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .format(v1());
        assertEquals(List.of("01234567: A bug",
                             "",
                             "A summary",
                             "",
                             "Reviewed-by: foo"),
                     lines);
    }

    @Test
    void formatVersion1WithBugAndReviewerAndSummaryAndContributor() {
        var lines = CommitMessage.title("01234567: A bug")
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .contributor(new Author("Baz Bar", "baz@bar.org"))
                                 .format(v1());
        assertEquals(List.of("01234567: A bug",
                             "",
                             "A summary",
                             "",
                             "Co-authored-by: Baz Bar <baz@bar.org>",
                             "Reviewed-by: foo"),
                     lines);
    }

    @Test
    void formatVersion1WithMultipleContributors() {
        var lines = CommitMessage.title("01234567: A bug")
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .contributors(new Author("Baz Bar", "baz@bar.org"),
                                               new Author("Foo Bar", "foo@bar.org"))
                                 .format(v1());
        assertEquals(List.of("01234567: A bug",
                             "",
                             "A summary",
                             "",
                             "Co-authored-by: Baz Bar <baz@bar.org>",
                             "Co-authored-by: Foo Bar <foo@bar.org>",
                             "Reviewed-by: foo"),
                     lines);
    }

    @Test
    void formatVersion1WithOriginal() {
        var lines = CommitMessage.title("01234567: A bug")
                                 .summary("A summary")
                                 .reviewer("foo")
                                 .contributors(new Author("Baz Bar", "baz@bar.org"))
                                 .original(new Hash("0123456789012345678901234567890123456789"))
                                 .format(v1());
        assertEquals(List.of("01234567: A bug",
                             "",
                             "A summary",
                             "",
                             "Co-authored-by: Baz Bar <baz@bar.org>",
                             "Reviewed-by: foo",
                             "Backport-of: 0123456789012345678901234567890123456789"),
                     lines);
    }
}
