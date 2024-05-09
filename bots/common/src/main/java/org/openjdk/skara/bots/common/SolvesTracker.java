/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.common;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class SolvesTracker {
    private static final String SOLVES_MARKER = "<!-- solves: '%s' '%s' -->";
    private static final Pattern MARKER_PATTERN = Pattern.compile("<!-- solves: '(.*?)' '(.*?)' -->");

    public static String setSolvesMarker(Issue issue) {
        var encodedDescription = Base64.getEncoder().encodeToString(issue.description().getBytes(StandardCharsets.UTF_8));
        return String.format(SOLVES_MARKER, issue.shortId(), encodedDescription);
    }

    public static String removeSolvesMarker(Issue issue) {
        return String.format(SOLVES_MARKER, issue.shortId(), "");
    }

    public static List<Issue> currentSolved(HostUser botUser, List<Comment> comments, String title) {
        var solvesActions = comments.stream()
                .filter(comment -> comment.author().equals(botUser))
                .flatMap(comment -> comment.body().lines())
                .map(MARKER_PATTERN::matcher)
                .filter(Matcher::find)
                .toList();
        var current = new LinkedHashMap<String, Issue>();
        var titleIssue = Issue.fromStringRelaxed(title);
        for (var action : solvesActions) {
            var key = action.group(1);
            if (titleIssue.isPresent() && key.equals(titleIssue.get().shortId())) {
                continue;
            }
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

    public static Optional<Comment> getLatestSolvesActionComment(HostUser botUser, List<Comment> comments, Issue issue) {
        return comments.stream()
                .filter(comment -> comment.author().equals(botUser))
                .filter(comment -> comment.body().contains("<!-- solves: '" + issue.shortId() + "'"))
                .max(Comparator.comparing(Comment::createdAt));
    }
}
