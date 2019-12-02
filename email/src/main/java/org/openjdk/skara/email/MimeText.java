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
package org.openjdk.skara.email;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class MimeText {
    private final static Pattern encodePattern = Pattern.compile("([^\\x00-\\x7f]+)");
    private final static Pattern decodePattern = Pattern.compile("=\\?([A-Za-z0-9_.-]+)\\?([bBqQ])\\?(.*?)\\?=");
    private final static Pattern decodeQuotedPrintablePattern = Pattern.compile("=([0-9A-F]{2})");

    public static String encode(String raw) {
        var words = raw.split(" ");
        var encodedWords = new ArrayList<String>();
        var lastEncoded = false;
        for (var word : words) {
            var needsQuotePattern = encodePattern.matcher(word);
            if (needsQuotePattern.find()) {
                if (lastEncoded) {
                    // Spaces between encoded words are ignored, so add an explicit one
                    encodedWords.add("=?UTF-8?B?IA==?=");
                }
                encodedWords.add("=?UTF-8?B?" + Base64.getEncoder().encodeToString(word.getBytes(StandardCharsets.UTF_8)) + "?=");
                lastEncoded = true;
            } else {
                encodedWords.add(word);
                lastEncoded = false;
            }
        }
        return String.join(" ", encodedWords);
    }

    public static String decode(String encoded) {
        var decoded = new StringBuilder();
        var quotedMatcher = decodePattern.matcher(encoded);
        var lastMatchEnd = 0;
        while (quotedMatcher.find()) {
            if (quotedMatcher.start() > lastMatchEnd) {
                var separator = encoded.substring(lastMatchEnd, quotedMatcher.start());
                if (!separator.isBlank()) {
                    decoded.append(separator);
                }
            }
            if (quotedMatcher.group(2).toUpperCase().equals("B")) {
                try {
                    decoded.append(new String(Base64.getDecoder().decode(quotedMatcher.group(3)), quotedMatcher.group(1)));
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            } else {
                var quotedDecodedSpaces = quotedMatcher.group(3).replace("_", " ");
                var quotedPrintableMatcher = decodeQuotedPrintablePattern.matcher(quotedDecodedSpaces);
                decoded.append(quotedPrintableMatcher.replaceAll(qmo -> {
                    var byteValue = new byte[1];
                    byteValue[0] = (byte)Integer.parseInt(qmo.group(1), 16);
                    try {
                        return new String(byteValue, quotedMatcher.group(1));
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            lastMatchEnd = quotedMatcher.end();
        }
        if (lastMatchEnd < encoded.length()) {
            decoded.append(encoded, lastMatchEnd, encoded.length());
        }
        return decoded.toString();
    }
}
