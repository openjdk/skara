/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.Comment;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class Summary {
    private static final String SUMMARY_MARKER = "<!-- summary: '%s' -->";
    private static final Pattern MARKER_PATTERN = Pattern.compile("<!-- summary: '(.*?)' -->");

    static String setSummaryMarker(String summary) {
        var encodedSummary = Base64.getEncoder().encodeToString(summary.getBytes(StandardCharsets.UTF_8));
        return String.format(SUMMARY_MARKER, encodedSummary);
    }

    static Optional<String> summary(HostUser botUser, List<Comment> comments) {
        var summaryActions = comments.stream()
                                         .filter(comment -> comment.author().equals(botUser))
                                         .map(comment -> MARKER_PATTERN.matcher(comment.body()))
                                         .filter(Matcher::find)
                                         .collect(Collectors.toList());
        String summary = null;
        for (var action : summaryActions) {
            var decodedSummary = new String(Base64.getDecoder().decode(action.group(1)), StandardCharsets.UTF_8);
            if (decodedSummary.isBlank()) {
                summary = null;
            } else {
                summary = decodedSummary;
            }
        }

        return Optional.ofNullable(summary);
    }
}
