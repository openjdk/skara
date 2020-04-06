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
package org.openjdk.skara.issuetracker;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

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
     * Title of the request.
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
    List<String> labels();

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

    List<Link> links();

    void addLink(Link link);

    void removeLink(Link link);

    Map<String, JSONValue> properties();

    void setProperty(String name, JSONValue value);

    void removeProperty(String name);
}
