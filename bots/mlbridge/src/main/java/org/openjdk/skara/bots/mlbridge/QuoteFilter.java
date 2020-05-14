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
package org.openjdk.skara.bots.mlbridge;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

class QuoteFilter {
    private static final Pattern leadingQuotesPattern = Pattern.compile("^([>\\s]+).*");

    private static Optional<String> leadingQuotes(String line) {
        var leadingQuotesMatcher = leadingQuotesPattern.matcher(line);
        if (leadingQuotesMatcher.matches()) {
            if (leadingQuotesMatcher.group(1).contains(">")) {
                return Optional.of(leadingQuotesMatcher.group(1).trim());
            }
        }
        return Optional.empty();
    }

    // Strip all quote blocks containing a certain link
    public static String stripLinkBlock(String body, URI link) {
        var ret = new ArrayList<String>();
        var buffer = new LinkedList<String>();
        String dropPrefix = null;

        for (var line : body.split("\\R")) {
            if (dropPrefix != null && line.startsWith(dropPrefix)) {
                continue;
            }
            dropPrefix = null;

            if (line.contains(link.toString())) {
                var prefix = leadingQuotes(line);
                if (prefix.isEmpty()) {
                    buffer.add(line);
                    continue;
                }
                dropPrefix = prefix.get();

                // Drop any previous lines with the same prefix
                while (!buffer.isEmpty()) {
                    if (buffer.peekLast().startsWith(dropPrefix)) {
                        buffer.removeLast();
                    } else {
                        break;
                    }
                }
                // Any remaining lines in buffer should be kept in the final result
                ret.addAll(buffer);
                buffer.clear();
            } else {
                buffer.add(line);
            }
        }

        ret.addAll(buffer);
        return String.join("\n", ret);
    }
}
