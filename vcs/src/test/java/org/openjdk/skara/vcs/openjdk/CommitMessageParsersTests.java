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

import org.junit.jupiter.api.Test;
import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Hash;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommitMessageParsersTests {
    @Test
    void parseVersion0Commit() {
        var text = List.of("01234567: A bug",
                           "Reviewed-by: foo",
                           "Contributed-by: Bar O'Baz <bar.obaz@localhost.com>");
        var message = CommitMessageParsers.v0.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(new Author("Bar O'Baz", "bar.obaz@localhost.com")),
                     message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion0CommitWithExtraNewline() {
        var text = List.of("01234567: A bug",
                           "",
                           "Summary: summary",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v0.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of("", "Summary: summary", "Reviewed-by: foo"), message.additional());
    }

    @Test
    void parseVersion0CommitWithSummary() {
        var text = List.of("01234567: A bug",
                           "Summary: This is a summary",
                           "Reviewed-by: foo",
                           "Contributed-by: Bar O'Baz <bar.obaz@localhost.com>");
        var message = CommitMessageParsers.v0.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(new Author("Bar O'Baz", "bar.obaz@localhost.com")),
                     message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of("This is a summary"), message.summaries());
        assertEquals(List.of(), message.additional());
    }


    @Test
    void parseVersion1Commit() {
        var text = List.of("01234567: A bug",
                           "",
                           "Co-authored-by: Bar O'Baz <bar.obaz@localhost.com>",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(new Author("Bar O'Baz", "bar.obaz@localhost.com")),
                     message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1CommitWithSummary() {
        var text = List.of("01234567: A bug",
                           "",
                           "This is a summary",
                           "",
                           "Co-authored-by: Bar O'Baz <bar.obaz@localhost.com>",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(new Author("Bar O'Baz", "bar.obaz@localhost.com")),
                     message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of("This is a summary"), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1CommitWithMultiPargraphSummary() {
        var text = List.of("01234567: A bug",
                           "",
                           "This is a summary",
                           "",
                           "This is another summary paragraph",
                           "",
                           "Co-authored-by: Bar O'Baz <bar.obaz@localhost.com>",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(new Author("Bar O'Baz", "bar.obaz@localhost.com")),
                     message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of("This is a summary","","This is another summary paragraph"),
                     message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1CommitWithoutTrailers() {
        var text = List.of("01234567: A bug",
                           "",
                           "This is a summary",
                           "",
                           "This is another summary paragraph");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals(List.of(new Issue("01234567", "A bug")), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.reviewers());
        assertEquals(List.of("This is a summary","","This is another summary paragraph"),
                     message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1CommitWithoutIssue() {
        var text = List.of("Bugfix!");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("Bugfix!", message.title());
        assertEquals(List.of(), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1CommitWithTitleAndSummaryAndTrailers() {
        var text = List.of("Bugfix!",
                           "",
                           "This is a summary",
                           "",
                           "Co-authored-by: Baz Bar <baz@bar.org>",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("Bugfix!", message.title());
        assertEquals(List.of(), message.issues());
        assertEquals(List.of(new Author("Baz Bar", "baz@bar.org")), message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of("This is a summary"), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1CommitWithIssueAndReview() {
        var text = List.of("01234567: An issue",
                           "",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void parseVersion1WithAdditionalLines() {
        var text = List.of("01234567: An issue",
                           "Reviewed-by: foo");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of("Reviewed-by: foo"), message.additional());
    }

    @Test
    void parseVersion1WithUknownTrailer() {
        var text = List.of("01234567: An issue",
                           "",
                           "Reviewed-by: foo",
                           "Unknown-trailer: bar");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of("foo"), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of("Unknown-trailer: bar"), message.additional());
    }

    @Test
    void internationalCoAuthors() {
        var text = List.of("01234567: An issue",
                           "",
                           "Co-authored-by: Föö Bår <foo@bar.com>",
                           "Co-authored-by: Bår Bäz <bar@baz.com>",
                           "Reviewed-by: ab, cd, ef");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of("ab", "cd", "ef"), message.reviewers());
        assertEquals(List.of(new Author("Föö Bår", "foo@bar.com"), new Author("Bår Bäz", "bar@baz.com")),
                     message.contributors());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void capitalLetterInEmail() {
        var text = List.of("01234567: An issue",
                           "",
                           "Co-authored-by: Just An Example <JustAn@example.com>",
                           "Reviewed-by: ab, cd, ef");

        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of("ab", "cd", "ef"), message.reviewers());
        assertEquals(List.of(new Author("Just An Example", "JustAn@example.com")),
                     message.contributors());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
    }

    @Test
    void backportOfTrailer() {
        var text = List.of("01234567: An issue",
                           "",
                           "Reviewed-by: ab",
                           "Backport-of: 0123456789012345678901234567890123456789");

        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of("ab"), message.reviewers());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
        assertEquals(Optional.of(new Hash("0123456789012345678901234567890123456789")), message.original());
    }

    @Test
    void onlyBackportOfTrailer() {
        var text = List.of("01234567: An issue",
                           "",
                           "Backport-of: 0123456789012345678901234567890123456789");

        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of(), message.reviewers());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(), message.additional());
        assertEquals(Optional.of(new Hash("0123456789012345678901234567890123456789")), message.original());
    }

    @Test
    void parseVersion1TrailingBlankLine() {
        var text = List.of("01234567: An issue",
                           "");
        var message = CommitMessageParsers.v1.parse(text);

        assertEquals("01234567: An issue", message.title());
        assertEquals(List.of(new Issue("01234567", "An issue")), message.issues());
        assertEquals(List.of(), message.contributors());
        assertEquals(List.of(), message.reviewers());
        assertEquals(List.of(), message.summaries());
        assertEquals(List.of(""), message.additional());
    }
}
