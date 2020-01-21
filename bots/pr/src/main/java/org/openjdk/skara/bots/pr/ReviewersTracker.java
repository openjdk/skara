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

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

class ReviewersTracker {
    private final static String reviewersMarker = "<!-- additional required reviewers id marker (%d) (%s) -->";
    private final static Pattern reviewersMarkerPattern = Pattern.compile(
            "<!-- additional required reviewers id marker \\((\\d+)\\) \\((\\w+)\\) -->");

    static String setReviewersMarker(int numReviewers, String role) {
        return String.format(reviewersMarker, numReviewers, role);
    }

    static Map<String, Integer> additionalRequiredReviewers(HostUser botUser, List<Comment> comments) {
        var ret = new HashMap<String, Integer>();
        var reviewersActions = comments.stream()
                                       .filter(comment -> comment.author().equals(botUser))
                                       .map(comment -> reviewersMarkerPattern.matcher(comment.body()))
                                       .filter(Matcher::find)
                                       .collect(Collectors.toList());
        if (reviewersActions.isEmpty()) {
            return ret;
        }
        for (var reviewersAction : reviewersActions) {
            ret.put(reviewersAction.group(2), Integer.parseInt(reviewersAction.group(1)));
        }
        return ret;
    }
}
