/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Reviewers {
    private static final String ADD_MARKER = "<!-- add reviewer: '%s' -->";
    private static final String REMOVE_MARKER = "<!-- remove reviewer: '%s' -->";
    private static final Pattern MARKER_PATTERN = Pattern.compile("<!-- (add|remove) reviewer: '(.*?)' -->");

    static String addReviewerMarker(Contributor contributor) {
        return String.format(ADD_MARKER, contributor.username());
    }

    static String addReviewerMarker(String username) {
        return String.format(ADD_MARKER, username);
    }

    static String removeReviewerMarker(Contributor contributor) {
        return String.format(REMOVE_MARKER, contributor.username());
    }

    static List<String> reviewers(HostUser botUser, List<Comment> comments) {
        var reviewerActions = comments.stream()
                                         .filter(comment -> comment.author().equals(botUser))
                                         .flatMap(comment -> Stream.of(comment.body().split("\n")))
                                         .map(line -> MARKER_PATTERN.matcher(line))
                                         .filter(Matcher::find)
                                         .collect(Collectors.toList());
        var contributors = new LinkedHashSet<String>();
        for (var action : reviewerActions) {
            switch (action.group(1)) {
                case "add":
                    contributors.add(action.group(2));
                    break;
                case "remove":
                    contributors.remove(action.group(2));
                    break;
            }
        }

        return new ArrayList<>(contributors);
    }
}
