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
package org.openjdk.skara.issuetracker;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * An interface for common aspects of different kinds of issues, either in a bug tracking
 * system or pull requests. In practice it's rare to operate on something that could be
 * either a bug or a pull request, so avoid using this interface directly.
 */
public interface Issue {
    /**
     * Project containing the issue.
     * @return
     */
    IssueProject project();

    /**
     * The repository-specific identifier.
     * @return
     */
    String id();

    /**
     * The host-specific author name.
     * @return
     */
    HostUser author();

    /**
     * Title of the request. The implementation should make sure it is stripped.
     * @return
     */
    String title();

    /**
     * Update the title of the request.
     * @param title
     */
    void setTitle(String title);

    /**
     * The main body of the request.
     * @return
     */
    String body();

    /**
     * Update the main body of the request.
     * @param body
     */
    void setBody(String body);

    /**
     * All comments on the issue, in ascending creation time order.
     * @return
     */
    List<Comment> comments();

    /**
     * Posts a new comment.
     * @param body
     */
    Comment addComment(String body);

    /**
     * Remove the specific comment.
     * @param comment
     */
    void removeComment(Comment comment);

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
    ZonedDateTime createdAt();

    /**
     * When the request was last updated.
     * @return
     */
    ZonedDateTime updatedAt();

    enum State {
        OPEN,
        RESOLVED,
        CLOSED
    }

    /**
     * Returns the current state.
     * @return
     */
    State state();

    default boolean isOpen() {
        return state() == State.OPEN;
    }

    default boolean isClosed() {
        return state() == State.CLOSED;
    }

    default boolean isResolved() {
        return state() == State.RESOLVED;
    }

    /**
     * By default this issue is considered fixed if it has been resolved.
     * For specific implementations, this may require additional criteria,
     * like not having been rejected.
     */
    default boolean isFixed() {
        return isResolved();
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
    default void removeLabel(Label label) {
        removeLabel(label.name());
    }

    /**
     * Set the given labels and remove any others.
     */
    void setLabels(List<String> labels);

    /**
     * Retrieves all the currently set labels.
     * @return
     */
    List<Label> labels();
    default List<String> labelNames() {
        return labels().stream().map(Label::name).collect(Collectors.toList());
    }

    /**
     * Returns a link that will lead to the issue.
     */
    URI webUrl();

    /**
     * Returns a non-transformed link to the issue
     */
    default URI nonTransformedWebUrl() {
        return webUrl();
    }

    /**
     * Returns all usernames assigned to the issue.
     */
    List<HostUser> assignees();

    /**
     * Update the list of assignees.
     * @param assignees
     */
    void setAssignees(List<HostUser> assignees);

    Optional<HostUser> closedBy();

    URI commentUrl(Comment comment);
}
