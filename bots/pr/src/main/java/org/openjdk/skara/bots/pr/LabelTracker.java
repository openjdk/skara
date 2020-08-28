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

public class LabelTracker {
    private final static String addMarker = "<!-- added label: '%s' -->";
    private final static String removeMarker = "<!-- removed label: '%s' -->";
    private final static Pattern labelMarkerPattern = Pattern.compile("<!-- (added|removed) label: '(.*?)' -->");

    static String addLabelMarker(String label) {
        return String.format(addMarker, label);
    }

    static String removeLabelMarker(String label) {
        return String.format(removeMarker, label);
    }

    // Return all manually added labels, but filter any explicitly removed ones
    static Set<String> currentAdded(HostUser botUser, List<Comment> comments) {
        var labelActions = comments.stream()
                .filter(comment -> comment.author().equals(botUser))
                .flatMap(comment -> comment.body().lines())
                .map(labelMarkerPattern::matcher)
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
                                   .map(labelMarkerPattern::matcher)
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
}
