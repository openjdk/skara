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
package org.openjdk.skara.host;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;

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
    String getId();

    /**
     * The host-specific author name.
     * @return
     */
    HostUserDetails getAuthor();

    /**
     * Title of the request.
     * @return
     */
    String getTitle();

    /**
     * Update the title of the request.
     * @param title
     */
    void setTitle(String title);

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
     * Returns a link that will lead to the issue.
     */
    URI getWebUrl();

    /**
     * Returns all usernames assigned to the issue.
     */
    List<HostUserDetails> getAssignees();

    /**
     * Update the list of assignees.
     * @param assignees
     */
    void setAssignees(List<HostUserDetails> assignees);
}
