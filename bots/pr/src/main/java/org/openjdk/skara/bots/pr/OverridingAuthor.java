/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

class OverridingAuthor {
    private static final String SET_MARKER = "<!-- set author: '%s' -->";
    private static final String REMOVE_MARKER = "<!-- remove author: '%s' -->";
    private static final Pattern MARKER_PATTERN = Pattern.compile("<!-- (set|remove) author: '(.*?)' -->");

    static String setAuthorMarker(EmailAddress author) {
        return String.format(SET_MARKER, author.toString());
    }

    static String removeAuthorMarker(EmailAddress author) {
        return String.format(REMOVE_MARKER, author.toString());
    }

    static Optional<EmailAddress> author(HostUser botUser, List<Comment> comments) {
        var authorActions = comments.stream()
                .filter(comment -> comment.author().equals(botUser))
                .map(comment -> MARKER_PATTERN.matcher(comment.body()))
                .filter(Matcher::find)
                .toList();
        Optional<EmailAddress> author = Optional.empty();
        for (var action : authorActions) {
            switch (action.group(1)) {
                case "set":
                    author = Optional.of(EmailAddress.parse(action.group(2)));
                    break;
                case "remove":
                    author = Optional.empty();
                    break;
            }
        }

        return author;
    }
}

