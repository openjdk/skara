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
import org.openjdk.skara.jcheck.JCheckConfiguration;

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

    static LinkedHashMap<String, Integer> updatedRoleLimits(JCheckConfiguration checkConfiguration, int count, String role) {
        var currentReviewers = checkConfiguration.checks().reviewers();

        var updatedLimits = new LinkedHashMap<String, Integer>();
        updatedLimits.put("lead", currentReviewers.lead());
        updatedLimits.put("reviewers", currentReviewers.reviewers());
        updatedLimits.put("committers", currentReviewers.committers());
        updatedLimits.put("authors", currentReviewers.authors());
        updatedLimits.put("contributors", currentReviewers.contributors());

        // Increase the required role level by the requested amount (while subtracting higher roles)
        var remainingAdditional = count;
        var roles = new ArrayList<>(updatedLimits.keySet());
        for (var r : roles) {
            if (!r.equals(role)) {
                remainingAdditional -= updatedLimits.get(r);
                if (remainingAdditional <= 0) {
                    break;
                }
            } else {
                updatedLimits.replace(r, updatedLimits.get(r) + remainingAdditional);
                break;
            }
        }

        // Decrease lower roles (if any) to avoid increasing the total count above the requested
        Collections.reverse(roles);
        var remainingRemovals = count;
        for (var r : roles) {
            if (!r.equals(role)) {
                var removed = Math.max(0, updatedLimits.get(r) - remainingRemovals);
                updatedLimits.replace(r, removed);
                remainingRemovals -= removed;
            } else {
                break;
            }
        }

        return updatedLimits;
    }

    static class AdditionalRequiredReviewers {
        private int number;
        private String role;

        AdditionalRequiredReviewers(int number, String role) {
            this.number = number;
            this.role = role;
        }

        int number() {
            return number;
        }

        String role() {
            return role;
        }
    }

    static Optional<AdditionalRequiredReviewers> additionalRequiredReviewers(HostUser botUser, List<Comment> comments) {
        var ret = new HashMap<String, Integer>();
        var reviewersActions = comments.stream()
                                       .filter(comment -> comment.author().equals(botUser))
                                       .map(comment -> reviewersMarkerPattern.matcher(comment.body()))
                                       .filter(Matcher::find)
                                       .collect(Collectors.toList());
        if (reviewersActions.isEmpty()) {
            return Optional.empty();
        }
        var last = reviewersActions.get(reviewersActions.size() - 1);
        return Optional.of(new AdditionalRequiredReviewers(Integer.parseInt(last.group(1)), last.group(2)));
    }
}
