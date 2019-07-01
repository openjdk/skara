/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.host;

import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

public interface PullRequest {

    HostedRepository repository();

    /**
     * The repository-specific identifier.
     * @return
     */
    String getId();

    /**
     * The host-specific author name.
     * @return
     */
    HostUserDetails getAuthor();

    /**
     * List of reviews, in descending chronological order.
     * @return
     */
    List<Review> getReviews();

    /**
     * Adds a review with the given verdict.
     */
    void addReview(Review.Verdict verdict, String body);

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
    List<ReviewComment> getReviewComments();

    /**
     * Hash of the current head of the request.
     * @return
     */
    Hash getHeadHash();

    /**
     * Returns the name of the ref the request is created from.
     * @return
     */
    String getSourceRef();

    /**
     * Returns the name of the ref the request is intended to be merged into.
     * @return
     */
    String getTargetRef();

    /**
     * Returns the current head of the ref the request is intended to be merged into.
     * @return
     */
    Hash getTargetHash();

    /**
     * Title of the request.
     * @return
     */
    String getTitle();

    /**
     * The main body of the request.
     * @return
     */
    String getBody();

    /**
     * Update the main body of the request.
     * @param body
     */
    void setBody(String body);

    /**
     * All comments on the issue, in ascending creation time order.
     * @return
     */
    List<Comment> getComments();

    /**
     * Posts a new comment.
     * @param body
     */
    Comment addComment(String body);

    /**
     * Updates an existing comment.
     * @param id
     * @param body
     */
    Comment updateComment(String id, String body);

    /**
     * When the request was created.
     * @return
     */
    ZonedDateTime getCreated();

    /**
     * When the request was last updated.
     * @return
     */
    ZonedDateTime getUpdated();

    /**
     * List of completed checks on the given hash.
     * @return
     */
    Map<String, Check> getChecks(Hash hash);

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

    enum State {
        OPEN,
        CLOSED
    }

    /**
     * Set the state.
     * @param state Desired state
     */
    void setState(State state);

    /**
     * Adds the given label.
     * @param label
     */
    void addLabel(String label);

    /**
     * Removes the given label.
     * @param label
     */
    void removeLabel(String label);

    /**
     * Retrieves all the currently set labels.
     * @return
     */
    List<String> getLabels();

    /**
     * Returns a link that will lead to the PR.
     */
    URI getWebUrl();

    /**
     * Returns all usernames assigned to the PR.
     */
    List<HostUserDetails> getAssignees();

    /**
     * Update the list of assignees.
     * @param assignees
     */
    void setAssignees(List<HostUserDetails> assignees);
}
