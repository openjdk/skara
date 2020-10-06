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
package org.openjdk.skara.bots.pr;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.*;

public class ExpirationTracker {
    private static final String expirationMarker = "<!-- Data expires at: '%s' -->";
    private static final Pattern expirationPattern = Pattern.compile("<!-- Data expires at: '(.*?)' -->", Pattern.MULTILINE);

    static String expiresAfterMarker(Duration expiresAfter) {
        return String.format(expirationMarker, ZonedDateTime.now().plus(expiresAfter).format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
    }

    static boolean hasExpired(String textWithMarkers) {
        var earliestExpiration = textWithMarkers.lines()
                                                .map(expirationPattern::matcher)
                                                .filter(Matcher::find)
                                                .map(matcher -> matcher.group(1))
                                                .sorted()
                                                .findFirst();
        if (earliestExpiration.isEmpty()) {
            return false;
        }

        var expiresAt = ZonedDateTime.parse(earliestExpiration.get(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        return expiresAt.isBefore(ZonedDateTime.now());
    }
}
