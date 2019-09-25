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
import java.util.Base64;
import java.util.regex.Pattern;

public class MimeText {
    private final static Pattern encodePattern = Pattern.compile("([^\\x00-\\x7f]+)");
    private final static Pattern decodePattern = Pattern.compile("=\\?([A-Za-z0-9_.-]+)\\?([bBqQ])\\?(.*?)\\?=");
    private final static Pattern decodeQuotedPrintablePattern = Pattern.compile("=([0-9A-F]{2})");

    public static String encode(String raw) {
        var quoteMatcher = encodePattern.matcher(raw);
        return quoteMatcher.replaceAll(mo -> "=?utf-8?b?" + Base64.getEncoder().encodeToString(String.valueOf(mo.group(1)).getBytes(StandardCharsets.UTF_8)) + "?=");
    }

    public static String decode(String encoded) {
        var quotedMatcher = decodePattern.matcher(encoded);
        return quotedMatcher.replaceAll(mo -> {
            try {
                if (mo.group(2).toUpperCase().equals("B")) {
                    return new String(Base64.getDecoder().decode(mo.group(3)), mo.group(1));
                } else {
                    var quotedPrintableMatcher = decodeQuotedPrintablePattern.matcher(mo.group(3));
                    return quotedPrintableMatcher.replaceAll(qmo -> {
                        var byteValue = new byte[1];
                        byteValue[0] = (byte)Integer.parseInt(qmo.group(1), 16);
                        try {
                            return new String(byteValue, mo.group(1));
                        } catch (UnsupportedEncodingException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
