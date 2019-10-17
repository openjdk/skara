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

import org.openjdk.skara.forge.Review;
import org.openjdk.skara.issuetracker.Comment;

import java.util.*;
import java.util.regex.Pattern;

class ReviewTracker {
    private final String reviewMarker = "<!-- Review id marker (%d) -->";
    private final Pattern reviewMarkerPattern = Pattern.compile(
            "<!-- Review id marker \\((\\d+)\\) -->");

    private final Map<Review, String> newComments = new HashMap<>();

    ReviewTracker(List<Comment> comments, List<Review> reviews) {
        var notified = new HashSet<Integer>();

        // Calculate current state
        for (var comment : comments) {
            var reviewMarkerMatcher = reviewMarkerPattern.matcher(comment.body());

            if (reviewMarkerMatcher.find()) {
                var reviewId = Integer.parseInt(reviewMarkerMatcher.group(1));
                notified.add(reviewId);
            }
        }

        // Find all reviews without a comment
        for (var review : reviews) {
            // Not notified yet
            if (!notified.contains(review.id())) {
                newComments.put(review, String.format(reviewMarker, review.id()));
            }
        }
    }

    Map<Review, String> newReviews() {
        return newComments;
    }
}
