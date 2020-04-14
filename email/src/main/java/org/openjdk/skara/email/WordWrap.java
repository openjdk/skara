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
package org.openjdk.skara.email;

import java.util.*;

public class WordWrap {
    private static boolean isIndentCharacter(char ch) {
        switch (ch) {
            case ' ':
            case '>':
            case '-':
            case '*':
                return true;
            default:
                return false;
        }
    }

    private static Map.Entry<String, String> split(String line, int lineLength) {
        if (line.length() <= lineLength) {
            return new AbstractMap.SimpleEntry<>(line, "");
        }
        var splitAt = -1;
        for (int i = 0; i < line.length() - 1; ++i) {
            var cur = line.charAt(i);
            var next = line.charAt(i + 1);
            if (cur == ' ') {
                if (!isIndentCharacter(next)) {
                    if (i < lineLength) {
                        splitAt = i;
                    } else {
                        // We'll never find a better match - if we don't have any candidate we have to split here even if lineLength is exceeded
                        if (splitAt == -1) {
                            splitAt = i;
                        }
                        break;
                    }
                }
            }
        }
        if (splitAt == -1) {
            return new AbstractMap.SimpleEntry<>(line, "");
        }
        return new AbstractMap.SimpleEntry<>(line.substring(0, splitAt), line.substring(splitAt + 1));
    }

    private static String indentation(String line) {
        for (int i = 0; i < line.length(); ++i) {
            if (!isIndentCharacter(line.charAt(i))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String filterIndent(String indent) {
        return indent.replace('-', ' ').replace('*', ' ');
    }

    public static String wrapBody(String body, int lineLength) {
        var ret = new StringBuilder();

        var lines = new LinkedList<String>();
        body.lines().forEach(lines::add);

        while (!lines.isEmpty()) {
            var line = lines.pollFirst();
            var indentation = indentation(line);
            var split = split(line.substring(indentation.length()), lineLength);
            if (!split.getValue().isBlank()) {
                var nextLine = lines.peekFirst();
                if (nextLine != null) {
                    var nextIndent = indentation(nextLine);
                    if (nextLine.isBlank() || !indentation.equals(filterIndent(nextIndent)) || !indentation.equals(nextIndent)) {
                        lines.addFirst(filterIndent(indentation) + split.getValue());
                    } else {
                        lines.removeFirst();
                        lines.addFirst(filterIndent(indentation) + split.getValue() + " " + nextLine.substring(indentation.length()));
                    }
                } else {
                    lines.addFirst(filterIndent(indentation) + split.getValue());
                }
            }
            if (ret.length() > 0) {
                ret.append("\n");
            }
            ret.append(indentation).append(split.getKey().stripTrailing());
        }

        return ret.toString();
    }
}
