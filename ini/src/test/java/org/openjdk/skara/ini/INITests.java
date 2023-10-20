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
package org.openjdk.skara.ini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class INITests {
    @Test
    void testOnlyGlobalEntries() {
        var lines = List.of(
            "project=jdk",
            "bugs=dup",
            "whitespace=lax"
        );
        var ini = INI.parse(lines);
        assertEquals("jdk", ini.get("project").asString());
        assertEquals("dup", ini.get("bugs").asString());
        assertEquals("lax", ini.get("whitespace").asString());
    }

    @Test
    void testWhitespaceInEntries() {
        var lines = List.of(
            "project = jdk",
            "\t\tbugs =    dup",
            "",
            "whitespace\t=\tlax"
        );
        var ini = INI.parse(lines);
        assertEquals("jdk", ini.get("project").asString());
        assertEquals("dup", ini.get("bugs").asString());
        assertEquals("lax", ini.get("whitespace").asString());
    }

    @Test
    void testComments() {
        var lines = List.of(
            "; this is a comment",
            "project = jdk",
            "\t\tbugs =    dup",
            "",
            "; this is another comment",
            "whitespace\t=\tlax"
        );
        var ini = INI.parse(lines);
        assertEquals("jdk", ini.get("project").asString());
        assertEquals("dup", ini.get("bugs").asString());
        assertEquals("lax", ini.get("whitespace").asString());
    }

    @Test
    void testOneSection() {
        var lines = List.of(
            "[general]",
            "    project = jdk",
            "    bugs = dup",
            "    whitespace = lax"
        );
        var ini = INI.parse(lines);
        assertEquals("jdk", ini.section("general").get("project").asString());
        assertEquals("dup", ini.section("general").get("bugs").asString());
        assertEquals("lax", ini.section("general").get("whitespace").asString());
    }

    @Test
    void testMultipleSections() {
        var lines = List.of(
            "[general]",
            "    project = jdk",
            "",
            "[checks]",
            "    commits = author, whitespace, reviews"
        );
        var ini = INI.parse(lines);
        assertEquals("jdk", ini.section("general").get("project").asString());
        assertEquals("author, whitespace, reviews", ini.section("checks").get("commits").asString());
    }

    @Test
    void testMultipleSectionsAndSubsection() {
        var lines = List.of(
            "[general]",
            "    project = jdk",
            "",
            "[checks]",
            "    commits = author, whitespace, reviews",
            "",
            "[checks \"whitespace\"]",
            "    suffixes = .cpp, .c, .h, .java"
        );
        var ini = INI.parse(lines);
        assertEquals("jdk", ini.section("general").get("project").asString());
        assertEquals("author, whitespace, reviews", ini.section("checks").get("commits").asString());
        assertEquals(".cpp, .c, .h, .java", ini.section("checks").subsection("whitespace").get("suffixes").asString());
    }

    @Test
    void testAsList() {
        var lines = List.of(
            "[a]",
            "    chars = a, b, c, d, e",
            "",
            "[b]",
            "    chars = a,b,c,d,e",
            "",
            "[c]",
            "    numbers = 1, 2, 3, 4, 5"
        );

        var ini = INI.parse(lines);
        var chars = List.of("a", "b", "c", "d", "e");
        var numbers = List.of(1, 2, 3, 4, 5);

        assertEquals(chars, ini.section("a").get("chars").asList());
        assertEquals(chars, ini.section("b").get("chars").asList());
        assertEquals(numbers, ini.section("c").get("numbers").asList(Integer::parseInt));
    }

    @Test
    void testAsBoolean() {
        var lines = List.of(
            "[a]",
            "    a = true",
            "    b = false"
        );

        var ini = INI.parse(lines);

        assertTrue(ini.section("a").get("a").asBoolean());
        assertFalse(ini.section("a").get("b").asBoolean());
    }

    @Test
    void testAsInt() {
        var lines = List.of(
            "[a]",
            "    a = 17"
        );

        var ini = INI.parse(lines);

        assertEquals(17, ini.section("a").get("a").asInt());
    }

    @Test
    void testAsDouble() {
        var lines = List.of(
            "[a]",
            "    a = 17.7"
        );

        var ini = INI.parse(lines);

        assertEquals(17.7, ini.section("a").get("a").asDouble());
    }

    @Test
    void testParseString() {
        var ini = INI.parse("project=jdk\nbugs=dup");

        assertEquals("jdk", ini.get("project").asString());
        assertEquals("dup", ini.get("bugs").asString());
    }

    @Test
    void testContains() {
        var ini = INI.parse("project=jdk");

        assertTrue(ini.contains("project"));
        assertFalse(ini.contains("bugs"));
    }

    @Test
    void testUpdatingValueInGlobalSection() {
        var lines = List.of(
            "foo=bar",
            "foo=baz"
        );
        var ini = INI.parse(lines);
        assertEquals("baz", ini.get("foo").asString());
    }

    @Test
    void testUpdatingValueInSection() {
        var lines = List.of(
            "[checks]",
            "    commits = reviews",
            "",
            "[checks]",
            "    commits = none"
        );
        var ini = INI.parse(lines);
        assertEquals("none", ini.section("checks").get("commits").asString());
    }

    @Test
    void testUpdatingValueInSubsection() {
        var lines = List.of(
            "[checks]",
            "    commits = reviews",
            "",
            "[checks \"reviews\"]",
            "    merge = ignore",
            "",
            "[checks \"reviews\"]",
            "    merge = check"
        );
        var ini = INI.parse(lines);
        assertEquals("check", ini.section("checks").subsection("reviews").get("merge").asString());
    }
}
