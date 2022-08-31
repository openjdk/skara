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
package org.openjdk.skara.test;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;

import java.time.ZonedDateTime;
import java.util.*;

class IssueData {
    Issue.State state = Issue.State.OPEN;
    String body = "";
    String title = "";
    final List<Comment> comments = new ArrayList<>();
    final Map<String, ZonedDateTime> labels = new HashMap<>();
    final List<HostUser> assignees = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    final Map<String, JSONValue> properties = new HashMap<>();
    ZonedDateTime created = ZonedDateTime.now();
    ZonedDateTime lastUpdate = created;
    HostUser closedBy = null;

    IssueData() {
    }

    IssueData copy() {
        var copy = new IssueData();
        copyTo(copy);
        return copy;
    }

    protected void copyTo(IssueData copy) {
        copy.state = state;
        copy.body = body;
        copy.title = title;
        copy.comments.addAll(comments);
        copy.labels.putAll(labels);
        copy.assignees.addAll(assignees);
        copy.links.addAll(links);
        copy.properties.putAll(properties);
        copy.created = created;
        copy.lastUpdate = lastUpdate;
        copy.closedBy = closedBy;
    }

    /**
     * This equals method is tailored for PullRequestPollerTests, where it
     * simulates the parts of a PullRequest which are included in the main
     * object and not accessed by sub queries. That means comments are
     * excluded.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        IssueData issueData = (IssueData) o;
        return state == issueData.state &&
                Objects.equals(body, issueData.body) &&
                Objects.equals(title, issueData.title) &&
                Objects.equals(labels, issueData.labels) &&
                Objects.equals(assignees, issueData.assignees) &&
                Objects.equals(links, issueData.links) &&
                Objects.equals(properties, issueData.properties) &&
                Objects.equals(created, issueData.created) &&
                Objects.equals(lastUpdate, issueData.lastUpdate) &&
                Objects.equals(closedBy, issueData.closedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, body, title, labels, assignees, links, properties, created, lastUpdate, closedBy);
    }
}
