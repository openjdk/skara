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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.Comment;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

class Contributors {
    private static final String ADD_MARKER = "<!-- add contributor: '%s' -->";
    private static final String REMOVE_MARKER = "<!-- remove contributor: '%s' -->";
    private static final Pattern MARKER_PATTERN = Pattern.compile("<!-- (add|remove) contributor: '(.*?)' -->");

    static String addContributorMarker(EmailAddress contributor) {
        return String.format(ADD_MARKER, contributor.toString());
    }

    static String removeContributorMarker(EmailAddress contributor) {
        return String.format(REMOVE_MARKER, contributor.toString());
    }

    static List<EmailAddress> contributors(HostUser botUser, List<Comment> comments) {
        var contributorActions = comments.stream()
                                         .filter(comment -> comment.author().equals(botUser))
                                         .map(comment -> MARKER_PATTERN.matcher(comment.body()))
                                         .filter(Matcher::find)
                                         .collect(Collectors.toList());
        var contributors = new LinkedHashSet<EmailAddress>();
        for (var action : contributorActions) {
            switch (action.group(1)) {
                case "add":
                    contributors.add(EmailAddress.parse(action.group(2)));
                    break;
                case "remove":
                    contributors.remove(EmailAddress.parse(action.group(2)));
                    break;
            }
        }

        return new ArrayList<>(contributors);
    }
}
