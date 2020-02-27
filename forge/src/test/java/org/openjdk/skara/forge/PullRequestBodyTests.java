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
package org.openjdk.skara.forge;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PullRequestBodyTests {
    @Test
    void parseEmpty() {
        var body = PullRequestBody.parse(List.of());
        assertTrue(body.issues().isEmpty());
        assertTrue(body.contributors().isEmpty());
    }

    @Test
    void parseText() {
        var text = List.of(
            "Hi all,",
            "",
            "please review this patch!",
            ""
        );
        var body = PullRequestBody.parse(text);
        assertTrue(body.issues().isEmpty());
        assertTrue(body.contributors().isEmpty());
    }

    @Test
    void parseEmptySections() {
        var text = List.of(
            "### Issues",
            "",
            "### Contributors",
            ""
        );
        var body = PullRequestBody.parse(text);
        assertTrue(body.issues().isEmpty());
        assertTrue(body.contributors().isEmpty());
    }

    @Test
    void parseSingleIssue() {
        var text = List.of(
            "### Issues",
            " * [JDK-1234567](https://bugs/JDK-1234567): A bug"
        );
        var body = PullRequestBody.parse(text);
        assertTrue(body.contributors().isEmpty());
        assertEquals(1, body.issues().size());
        assertEquals(URI.create("https://bugs/JDK-1234567"),
                     body.issues().get(0));
    }

    @Test
    void parseMultipleIssues() {
        var text = List.of(
            "### Issues",
            " * [JDK-1234567](https://bugs/JDK-1234567): A bug",
            " * [JDK-4567890](https://bugs/JDK-4567890): Another bug"
        );
        var body = PullRequestBody.parse(text);
        assertTrue(body.contributors().isEmpty());
        assertEquals(2, body.issues().size());
        assertEquals(URI.create("https://bugs/JDK-1234567"),
                     body.issues().get(0));
        assertEquals(URI.create("https://bugs/JDK-4567890"),
                     body.issues().get(1));
    }

    @Test
    void parseSingleContributor() {
        var text = List.of(
            "### Contributors",
            " * Foo Bar `<foo@bar.com>`"
        );
        var body = PullRequestBody.parse(text);
        assertTrue(body.issues().isEmpty());
        assertEquals(1, body.contributors().size());
        assertEquals("Foo Bar <foo@bar.com>", body.contributors().get(0));
    }

    @Test
    void parseMultipleContributors() {
        var text = List.of(
            "### Contributors",
            " * Foo Bar `<foo@bar.com>`",
            " * J Duke `<j@duke.com>`"
        );
        var body = PullRequestBody.parse(text);
        assertTrue(body.issues().isEmpty());
        assertEquals(2, body.contributors().size());
        assertEquals("Foo Bar <foo@bar.com>", body.contributors().get(0));
        assertEquals("J Duke <j@duke.com>", body.contributors().get(1));
    }

    @Test
    void parseMultipleContributorsAndMultipleIssues() {
        var text = List.of(
            "### Issues",
            " * [JDK-1234567](https://bugs/JDK-1234567): A bug",
            " * [JDK-4567890](https://bugs/JDK-4567890): Another bug",
            "### Contributors",
            " * Foo Bar `<foo@bar.com>`",
            " * J Duke `<j@duke.com>`"
        );
        var body = PullRequestBody.parse(text);
        assertEquals(URI.create("https://bugs/JDK-1234567"),
                     body.issues().get(0));
        assertEquals(URI.create("https://bugs/JDK-4567890"),
                     body.issues().get(1));
        assertEquals(2, body.contributors().size());
        assertEquals("Foo Bar <foo@bar.com>", body.contributors().get(0));
        assertEquals("J Duke <j@duke.com>", body.contributors().get(1));
    }

    @Test
    void parseMultipleContributorsAndMultipleIssuesWithAdditionalText() {
        var text = List.of(
            "Hi all,",
            "",
            "please review this patch!",
            "",
            "<!-- A COMMENT ->",
            "### Progress",
            "",
            "### Issues",
            " * [JDK-1234567](https://bugs/JDK-1234567): A bug",
            " * [JDK-4567890](https://bugs/JDK-4567890): Another bug",
            "",
            "### Contributors",
            " * Foo Bar `<foo@bar.com>`",
            " * J Duke `<j@duke.com>`",
            "",
            "### Download"
        );
        var body = PullRequestBody.parse(text);
        assertEquals(URI.create("https://bugs/JDK-1234567"),
                     body.issues().get(0));
        assertEquals(URI.create("https://bugs/JDK-4567890"),
                     body.issues().get(1));
        assertEquals(2, body.contributors().size());
        assertEquals("Foo Bar <foo@bar.com>", body.contributors().get(0));
        assertEquals("J Duke <j@duke.com>", body.contributors().get(1));
    }
}
