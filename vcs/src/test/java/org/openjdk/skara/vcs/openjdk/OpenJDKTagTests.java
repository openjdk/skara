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

import org.openjdk.skara.vcs.Tag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenJDKTagTests {
    @Test
    void parseTags() {
        var tag = new Tag("jdk-10+20");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(20, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals(19, previousTag.buildNum());
    }

    @Test
    void parseSingleDigitTags() {
        var tag = new Tag("jdk-10+10");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(10, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals("jdk-10+9", previousTag.tag().name());
        assertEquals(9, previousTag.buildNum());
    }

    @Test
    void parseLegacyTags() {
        var tag = new Tag("jdk7-b147");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(147, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals(146, previousTag.buildNum());
    }

    @Test
    void parseSingleDigitLegacyTags() {
        var tag = new Tag("jdk7-b10");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(10, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals("jdk7-b09", previousTag.tag().name());
        assertEquals(9, previousTag.buildNum());
    }

    @Test
    void parseHotspotTags() {
        var tag = new Tag("hs23.6-b19");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(19, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals(18, previousTag.buildNum());
    }

    @Test
    void parseSingleDigitHotspotTags() {
        var tag = new Tag("hs23.6-b10");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(10, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals("hs23.6-b09", previousTag.tag().name());
        assertEquals(9, previousTag.buildNum());
    }

    @Test
    void noPrevious() {
        var tag = new Tag("jdk-10+0");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals(0, jdkTag.buildNum());
        assertFalse(jdkTag.previous().isPresent());
    }

    @Test
    void parseJfxTags() {
        var tag = new Tag("12.1.3+14");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals("12.1.3", jdkTag.version());
        assertEquals(14, jdkTag.buildNum());
        var previousTag = jdkTag.previous().orElseThrow();
        assertEquals(13, previousTag.buildNum());
    }

    @Test
    void parseJfxTagsGa() {
        var tag = new Tag("12.1-ga");
        var jdkTag = OpenJDKTag.create(tag).orElseThrow();
        assertEquals("12.1", jdkTag.version());
        assertEquals(0, jdkTag.buildNum());
    }
}
