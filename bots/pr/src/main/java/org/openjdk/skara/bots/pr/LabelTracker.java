/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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

public class LabelTracker {
    private static final String ADD_MARKER = "<!-- added label: '%s' -->";
    private static final String REMOVE_MARKER = "<!-- removed label: '%s' -->";
    private static final Pattern LABEL_MARKER_PATTERN = Pattern.compile("<!-- (added|removed) label: '(.*?)' -->");

    static String addLabelMarker(String label) {
        return String.format(ADD_MARKER, label);
    }

    static String removeLabelMarker(String label) {
        return String.format(REMOVE_MARKER, label);
    }

    // Return all manually added labels, but filter any explicitly removed ones
    static Set<String> currentAdded(HostUser botUser, List<Comment> comments) {
        var labelActions = comments.stream()
                .filter(comment -> comment.author().equals(botUser))
                .flatMap(comment -> comment.body().lines())
                .map(LABEL_MARKER_PATTERN::matcher)
                .filter(Matcher::find)
                .collect(Collectors.toList());

        var ret = new HashSet<String>();
        for (var actionMatch : labelActions) {
            var action = actionMatch.group(1);
            if (action.equals("added")) {
                ret.add(actionMatch.group(2));
            } else {
                ret.remove(actionMatch.group(2));
            }
        }

        return Collections.unmodifiableSet(ret);
    }

    // Return all manually removed labels, but filter any explicitly added ones
    static Set<String> currentRemoved(HostUser botUser, List<Comment> comments) {
        var labelActions = comments.stream()
                                   .filter(comment -> comment.author().equals(botUser))
                                   .flatMap(comment -> comment.body().lines())
                                   .map(LABEL_MARKER_PATTERN::matcher)
                                   .filter(Matcher::find)
                                   .collect(Collectors.toList());

        var ret = new HashSet<String>();
        for (var actionMatch : labelActions) {
            var action = actionMatch.group(1);
            if (action.equals("removed")) {
                ret.add(actionMatch.group(2));
            } else {
                ret.remove(actionMatch.group(2));
            }
        }

        return Collections.unmodifiableSet(ret);
    }

    // Return true if the latest operation on the given label by the botUser was "removed"
    static boolean isLabelManuallyRemoved(HostUser botUser, List<Comment> comments, String label) {
        return comments.stream()
                .filter(comment -> comment.author().equals(botUser))
                .sorted(Comparator.comparing(Comment::createdAt).reversed())
                .flatMap(comment -> comment.body().lines())
                .map(LABEL_MARKER_PATTERN::matcher)
                .filter(Matcher::find)
                .filter(matcher -> matcher.group(2).equals(label))
                .map(matcher -> matcher.group(1))
                .findFirst()
                .map(action -> action.equals("removed"))
                .orElse(false);
    }
}
