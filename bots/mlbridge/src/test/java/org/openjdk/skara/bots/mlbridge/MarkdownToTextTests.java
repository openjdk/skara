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

import static org.junit.jupiter.api.Assertions.*;

class MarkdownToTextTests {
    @Test
    void emoji() {
        assertEquals("😄", MarkdownToText.removeFormatting(":smile:"));
        assertEquals("yay 😄", MarkdownToText.removeFormatting("yay :smile:"));
        assertEquals("😄\n😄", MarkdownToText.removeFormatting(":smile:\n:smile:"));
        assertEquals("😄 🙁", MarkdownToText.removeFormatting(":smile: :slight_frown:"));
        assertEquals("😄 🙁 :meh:", MarkdownToText.removeFormatting(":smile: :slight_frown: :meh:"));
    }

    @Test
    void patterns() {
        for (var emojiMap : EmojiTable.mapping.entrySet()) {
            var pattern = ":" + emojiMap.getKey() + ":";
            assertNotEquals(pattern, MarkdownToText.removeFormatting(pattern));
        }
    }

    @Test
    void code() {
        assertEquals("Just some text", MarkdownToText.removeFormatting("```\nJust some text\n```"));
        assertEquals("Multi\nline", MarkdownToText.removeFormatting("```\nMulti\nline\n```"));
        assertEquals("Script", MarkdownToText.removeFormatting("```bash\nScript\n```"));
    }

    @Test
    void suggestion() {
        assertEquals("Suggestion:\n\nJust some text", MarkdownToText.removeFormatting("```suggestion\nJust some text\n```"));
    }
}
