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
package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextToMarkdownTests {
    @Test
    void punctuation() {
        assertEquals("1\\. Not a list", TextToMarkdown.escapeFormatting("1. Not a list"));
        assertEquals("\\*not emphasized\\*", TextToMarkdown.escapeFormatting("*not emphasized*"));
        assertEquals("\\\\n", TextToMarkdown.escapeFormatting("\\n"));
    }

    @Test
    void indented() {
        assertEquals("&#32;      hello", TextToMarkdown.escapeFormatting("       hello"));
    }

    @Test
    void preserveQuoting() {
        assertEquals("> quoted", TextToMarkdown.escapeFormatting("> quoted"));
    }

    @Test
    void escapedPattern() {
        assertEquals("1\\$2", TextToMarkdown.escapeFormatting("1$2"));
    }

    @Test
    void separateQuoteBlocks() {
        assertEquals("> 1\n\n2", TextToMarkdown.escapeFormatting("> 1\n2"));
        assertEquals("> 1\n\n2", TextToMarkdown.escapeFormatting("> 1\n\n2"));
        assertEquals("> 1\n> 2\n\n3", TextToMarkdown.escapeFormatting("> 1\n> 2\n3"));
        assertEquals("> 1\n> 2\n\n3", TextToMarkdown.escapeFormatting("> 1\n> 2\n\n3"));
    }
}
