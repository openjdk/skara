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

import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Hash;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

class ReadyForSponsorTracker {
    private final static String marker = "<!-- integration requested: '%s' -->";
    private final static Pattern markerPattern = Pattern.compile("<!-- integration requested: '(.*?)' -->");

    static String addIntegrationMarker(Hash hash) {
        return String.format(marker, hash.hex());
    }

    static Optional<Hash> latestReadyForSponsor(HostUser botUser, List<Comment> comments) {
        var ready = comments.stream()
                                         .filter(comment -> comment.author().equals(botUser))
                                         .map(comment -> markerPattern.matcher(comment.body()))
                                         .filter(Matcher::find)
                            .map(matcher -> matcher.group(1))
                            .map(Hash::new)
                                         .collect(Collectors.toList());
        if (ready.size() == 0) {
            return Optional.empty();
        } else {
            return Optional.of(ready.get(ready.size() - 1));
        }
    }
}
