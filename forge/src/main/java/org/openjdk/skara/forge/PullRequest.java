/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.vcs.Diff;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

public interface PullRequest extends Issue {
    HostedRepository repository();

    /**
     * List of reviews.
     * @return
     */
    List<Review> reviews();

    /**
     * Adds a review with the given verdict.
     */
    void addReview(Review.Verdict verdict, String body);

    /**
     * Updates the comment body of a review.
     */
    void updateReview(String id, String body);

    /**
     * Add a file specific comment.
     * @param hash
     * @param path
     * @param line
     * @param body
     * @return
     */
    ReviewComment addReviewComment(Hash base, Hash hash, String path, int line, String body);

    /**
     * Reply to a file specific comment.
     * @param parent
     * @param body
     * @return
     */
    ReviewComment addReviewCommentReply(ReviewComment parent, String body);

    /**
     * Get all file specific comments.
     * @return
     */
    List<ReviewComment> reviewComments();

    /**
     * Get all file specific comments but potentially without file location data.
     * This may save computation and I/O time if constructing that data is expensive.
     * @return
     */
    default List<? extends Comment> reviewCommentsAsComments() {
        return reviewComments();
    }

    /**
     * Hash of the current head of the request.
     * @return
     */
    Hash headHash();

    /**
     * URI to the current head of the request.
     * @return
     */
    URI headUrl();

    /**
     * Returns the name of the ref used for fetching the pull request.
     * @return
     */
    String fetchRef();

    /**
     * Returns the name of the ref the request is created from.
     * @return
     */
    String sourceRef();

    /**
     * Returns the repository the request is created from.
     * @return
     */
    Optional<HostedRepository> sourceRepository();

    /**
     * Returns the name of the ref the request is intended to be merged into.
     * @return
     */
    String targetRef();

    /**
     * Returns a list of all targetRef change events.
     * @return
     */
    List<ReferenceChange> targetRefChanges();

    /**
     * List of completed checks on the given hash.
     * @return
     */
    Map<String, Check> checks(Hash hash);

    /** Returns a link to the patch/diff file
     * @return
     */
    URI diffUrl();

    /** Returns a diff of the changes between PR HEAD and target branch.
     * @return
     */
    Diff diff();

    /**
     * Creates a new check.
     * @param check
     */
    void createCheck(Check check);

    /**
     * Updates an existing check.
     * @param check
     */
    void updateCheck(Check check);

    /**
     * Returns a link that will lead to the list of changes done in the request.
     */
    URI changeUrl();

    /**
     * Returns a link that will lead to the list of changes with the specified base.
     */
    URI changeUrl(Hash base);

    URI reviewCommentUrl(ReviewComment reviewComment);

    URI reviewUrl(Review review);

    /**
     * Returns true if the request is in draft mode.
     * @return
     */
    boolean isDraft();
    void makeNotDraft();

    /**
     * Return the last time the pull request was converted to draft.
     * If the pull request was created as draft, return the created time of the pull request.
     * If the pull request was always ready for review and never converted to draft, return empty.
     * If the restful api doesn't support draft pull request, return empty.
     * Note: if the pull request was created as draft, but later converted to ready
     *  and didn't convert to draft again, this method will return empty.
     */
    Optional<ZonedDateTime> lastMarkedAsDraftTime();

    Optional<ZonedDateTime> labelAddedAt(String label);

    /**
     * Update the ref the request is intended to be merged into.
     * @return
     */
    void setTargetRef(String targetRef);

    URI filesUrl(Hash hash);

    /**
     * Returns true if this PullRequest represents the same pull request as the other.
     */
    default boolean isSame(PullRequest other) {
        return id().equals(other.id()) && repository().isSame(other.repository());
    }

    /**
     * Return the last time something was force pushed while not in draft state.
     * If there is no force-push in pull request or the restful api doesn't
     * support force-push, return empty.
     */
    Optional<ZonedDateTime> lastForcePushTime();

    /**
     * Return the commit hash if the pull request was integrated.
     */
    Optional<Hash> findIntegratedCommitHash();

    default Optional<Hash> findIntegratedCommitHash(List<String> userIds) {
        Pattern pushedPattern = Pattern.compile("Pushed as commit ([a-f0-9]{40})\\.");
        if (labelNames().contains("integrated")) {
            return comments().stream()
                    .filter(comment -> userIds.contains(comment.author().id()))
                    .map(Comment::body)
                    .map(pushedPattern::matcher)
                    .filter(Matcher::find)
                    .map(m -> m.group(1))
                    .map(Hash::new)
                    .findAny();
        }
        return Optional.empty();
    }

    /**
     * Return the comment message about the commit hash.
     */
    static String commitHashMessage(Hash hash) {
        return hash != null ? "Pushed as commit " + hash.hex() + "." : "";
    }

    /**
     * Returns an object that represents a complete snapshot of this pull request.
     * Used for detecting if anything has changed between two snapshots.
     */
    Object snapshot();

    /**
     * Returns the last time of the pull request touched by user
     * Valid Touch includes "mark as ready", "convert to draft", "reopen", "commit"
     */
    ZonedDateTime lastTouchedTime();

    /**
     * Helper method for implementations of this interface. Creates a new list
     * of Review objects with the targetRef field updated to match the target
     * ref change events. Ideally this method should have been part of a common
     * super class, but there isn't one.
     */
    static List<Review> calculateReviewTargetRefs(List<Review> reviews, List<ReferenceChange> events) {
        if (events.isEmpty()) {
            return reviews;
        }
        var sortedEvents = events.stream()
                .sorted(Comparator.comparing(ReferenceChange::at))
                .toList();
        var lastTargetRef = sortedEvents.getLast().to();
        return reviews.stream().map(orig -> {
                    for (var event : sortedEvents) {
                        if (event.at().isAfter(orig.createdAt())
                                && !PreIntegrations.isPreintegrationBranch(event.from())) {
                            return orig.withTargetRef(event.from());
                        }
                    }
                    if (orig.targetRef().equals(lastTargetRef)) {
                        return orig;
                    } else {
                        return orig.withTargetRef(lastTargetRef);
                    }
                })
                .toList();
    }
}
