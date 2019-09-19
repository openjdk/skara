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

import java.util.regex.Pattern;

public class MarkdownToText {
    private static final Pattern emojiPattern = Pattern.compile("(:([0-9a-z_+-]+):)");
    private static final Pattern codePattern = Pattern.compile("^```(\\w*)\\R(.*)\\R```", Pattern.DOTALL | Pattern.MULTILINE);

    private static String removeEmojis(String markdown) {
        var emojiMatcher = emojiPattern.matcher(markdown);
        return emojiMatcher.replaceAll(mr -> EmojiTable.mapping.getOrDefault(mr.group(2), mr.group(1)));
    }

    private static String removeCode(String markdown) {
        var codeMatcher = codePattern.matcher(markdown);
        return codeMatcher.replaceAll(mr -> (mr.group(1).equals("suggestion") ? "Suggestion:\n\n" : "") + mr.group(2));
    }

    static String removeFormatting(String markdown) {
        return removeCode(removeEmojis(markdown));
    }
}
