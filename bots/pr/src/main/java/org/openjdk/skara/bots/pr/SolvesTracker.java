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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class SolvesTracker {
    private final static String solvesMarker = "<!-- solves: '%s' '%s' -->";
    private final static Pattern markerPattern = Pattern.compile("<!-- solves: '(.*?)' '(.*?)' -->");

    static String setSolvesMarker(Issue issue) {
        var encodedDescription = Base64.getEncoder().encodeToString(issue.description().getBytes(StandardCharsets.UTF_8));
        return String.format(solvesMarker, issue.shortId(), encodedDescription);
    }

    static String removeSolvesMarker(Issue issue) {
        return String.format(solvesMarker, issue.shortId(), "");
    }

    static List<Issue> currentSolved(HostUser botUser, List<Comment> comments) {
        var solvesActions = comments.stream()
                                    .filter(comment -> comment.author().equals(botUser))
                                    .flatMap(comment -> comment.body().lines())
                                    .map(markerPattern::matcher)
                                    .filter(Matcher::find)
                                    .collect(Collectors.toList());
        var current = new LinkedHashMap<String, Issue>();
        for (var action : solvesActions) {
            var key = action.group(1);
            if (action.group(2).equals("")) {
                current.remove(key);
            } else {
                var decodedDescription = new String(Base64.getDecoder().decode(action.group(2)), StandardCharsets.UTF_8);
                var issue = new Issue(key, decodedDescription);
                current.put(key, issue);
            }
        }

        return new ArrayList<>(current.values());
    }
}
