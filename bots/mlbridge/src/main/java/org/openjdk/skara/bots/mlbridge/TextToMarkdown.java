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

import java.util.ArrayList;
import java.util.regex.*;

public class TextToMarkdown {
    private static final Pattern punctuationPattern = Pattern.compile("([!\"#$%&'()*+,\\-./:;<=?@\\[\\]^_`{|}~])", Pattern.MULTILINE);
    private static final Pattern indentedPattern = Pattern.compile("^ {4}", Pattern.MULTILINE);
    private static final Pattern quoteBlockPattern = Pattern.compile("^(>(>|\\s)*\\s.*$)", Pattern.MULTILINE);

    private static String escapeBackslashes(String text) {
        return text.replace("\\", "\\\\");
    }

    private static String escapePunctuation(String text) {
        var punctuationMatcher = punctuationPattern.matcher(text);
        return punctuationMatcher.replaceAll(mr -> "\\\\" + Matcher.quoteReplacement(mr.group(1)));
    }

    private static String escapeIndention(String text) {
        var indentedMatcher = indentedPattern.matcher(text);
        return indentedMatcher.replaceAll("&#32;   ");
    }

    private static String separateQuoteBlocks(String text) {
        var ret = new ArrayList<String>();
        var lastLineQuoted = false;
        for (var line : text.split("\\R")) {
            if ((line.length() > 0) && (line.charAt(0) == '>')) {
                lastLineQuoted = true;
            } else {
                if (lastLineQuoted && !line.isBlank()) {
                    ret.add("");
                }
                lastLineQuoted = false;
            }
            ret.add(line);
        }
        return String.join("\n", ret);
    }

    static String escapeFormatting(String text) {
        return escapeIndention(escapePunctuation(escapeBackslashes(separateQuoteBlocks(text))));
    }
}
