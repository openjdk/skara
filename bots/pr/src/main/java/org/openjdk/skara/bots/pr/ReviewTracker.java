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

class ReviewTracker {
    private final String reviewMarker = "<!-- Review marker (%d) by (%s) (%s) of (%s) -->";
    private final String unreviewMarker = "<!-- Unreview marker by (%s) -->";
    private final Pattern reviewMarkerPattern = Pattern.compile(
            "<!-- Review marker \\((\\d+)\\) by \\((\\d+)\\) \\(([-.\\w]+)\\) of \\((\\w+)\\) -->");
    private final Pattern unreviewMarkerPattern = Pattern.compile(
            "<!-- Unreview marker by \\((\\d+)\\) -->");

    private static class ReviewState {
        Comment comment;
        String userId;
        String userName;
        String hash;
        int verdict;
    }

    private final Map<Review, String> newComments = new HashMap<>();
    private final Map<String, String> removedReviews = new HashMap<>();
    private final Map<Review, String> updatedReviews = new HashMap<>();

    ReviewTracker(List<Comment> comments, List<Review> reviews) {
        var reviewStates = new HashMap<String, ReviewState>();

        // Calculate current state
        for (var comment : comments) {
            var reviewMarkerMatcher = reviewMarkerPattern.matcher(comment.body());
            var unreviewMarkerMatcher = unreviewMarkerPattern.matcher(comment.body());

            if (reviewMarkerMatcher.find()) {
                var reviewState = new ReviewState();
                reviewState.verdict = Integer.parseInt(reviewMarkerMatcher.group(1));
                reviewState.userId = reviewMarkerMatcher.group(2);
                reviewState.userName = reviewMarkerMatcher.group(3);
                reviewState.hash = reviewMarkerMatcher.group(4);
                reviewState.comment = comment;
                reviewStates.put(reviewState.userId, reviewState);
            } else if (unreviewMarkerMatcher.find()) {
                var userId = unreviewMarkerMatcher.group(1);
                reviewStates.remove(userId);
            }
        }

        // Find all reviews without a comment
        for (var review : reviews) {
            // Not notified yet
            if (!reviewStates.containsKey(review.reviewer().id())) {
                newComments.put(review, String.format(reviewMarker, review.verdict().ordinal(), review.reviewer().id(), review.reviewer().userName(), review.hash().hex()));
            } else {
                var oldReview = reviewStates.get(review.reviewer().id());
                if (review.verdict().ordinal() != oldReview.verdict) {
                    updatedReviews.put(review, String.format(reviewMarker, review.verdict().ordinal(), review.reviewer().id(), review.reviewer().userName(), review.hash().hex()));
                }
                reviewStates.remove(review.reviewer().id());
            }
        }

        // Check if there are any states not covered by reviews - these must have been removed
        for (var reviewState : reviewStates.values()) {
            removedReviews.put(reviewState.userName, String.format(unreviewMarker, reviewState.userId));
        }
    }

    Map<Review, String> newReviews() {
        return newComments;
    }

    Map<String, String> removedReviews() {
        return removedReviews;
    }

    Map<Review, String> updatedReviews() {
        return updatedReviews;
    }
}
