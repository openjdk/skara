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
package org.openjdk.skara.forge;

import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.util.*;

public interface PullRequest extends Issue {
    HostedRepository repository();

    /**
     * List of reviews, in descending chronological order.
     * @return
     */
    List<Review> reviews();

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
    List<ReviewComment> reviewComments();

    /**
     * Hash of the current head of the request.
     * @return
     */
    Hash headHash();

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
    HostedRepository sourceRepository();

    /**
     * Returns the name of the ref the request is intended to be merged into.
     * @return
     */
    String targetRef();

    /**
     * Returns the current head of the ref the request is intended to be merged into.
     * @return
     */
    Hash targetHash();

    /**
     * List of completed checks on the given hash.
     * @return
     */
    Map<String, Check> checks(Hash hash);

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

    /**
     * Returns true if the request is in draft mode.
     * @return
     */
    boolean isDraft();
    void makeNotDraft();
}
