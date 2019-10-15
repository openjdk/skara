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

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class Veto {
    private final static String vetoReplyMarker = "<!-- Veto marker (%s) -->";
    private final static Pattern vetoReplyPattern = Pattern.compile("<!-- Veto marker \\((\\S+)\\) -->");
    private final static String approvalReplyMarker = "<!-- Approval marker (%s) -->";
    private final static Pattern approvalReplyPattern = Pattern.compile("<!-- Approval marker \\((\\S+)\\) -->");

    static String addVeto(HostUser vetoer) {
        return String.format(vetoReplyMarker, vetoer.id());
    }

    static String removeVeto(HostUser vetoer) {
        return String.format(approvalReplyMarker, vetoer.id());
    }

    static Set<String> vetoers(HostUser botUser, List<Comment> allComments) {
        var vetoers = new HashSet<String>();
        var botComments = allComments.stream()
                .filter(comment -> comment.author().equals((botUser)))
                .collect(Collectors.toList());

        for (var comment : botComments) {
            var vetoReplyMatcher = vetoReplyPattern.matcher(comment.body());
            if (vetoReplyMatcher.find()) {
                vetoers.add(vetoReplyMatcher.group(1));
                continue;
            }
            var approvalReplyMatcher = approvalReplyPattern.matcher(comment.body());
            if (approvalReplyMatcher.find()) {
                vetoers.remove(approvalReplyMatcher.group(1));
            }
        }

        return vetoers;
    }
}
