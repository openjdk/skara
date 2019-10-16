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

import org.openjdk.skara.host.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;

public class JiraIssue implements Issue {
    private final JiraProject jiraProject;
    private final RestRequest request;
    private final JSONValue json;

    JiraIssue(JiraProject jiraProject, RestRequest request, JSONValue json) {
        this.jiraProject = jiraProject;
        this.request = request;
        this.json = json;
    }

    @Override
    public IssueProject project() {
        return jiraProject;
    }

    @Override
    public String id() {
        return json.get("key").asString();
    }

    @Override
    public HostUser author() {
        return new HostUser(json.get("fields").get("creator").get("key").asString(),
                            json.get("fields").get("creator").get("name").asString(),
                            json.get("fields").get("creator").get("displayName").asString());
    }

    @Override
    public String title() {
        return json.get("fields").get("summary").asString();
    }

    @Override
    public void setTitle(String title) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public String body() {
        if (json.get("fields").get("description").isNull()) {
            return "";
        } else {
            return json.get("fields").get("description").asString();
        }
    }

    @Override
    public void setBody(String body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public List<Comment> comments() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Comment addComment(String body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Comment updateComment(String id, String body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public ZonedDateTime createdAt() {
        return ZonedDateTime.parse(json.get("fields").get("created").asString());
    }

    @Override
    public ZonedDateTime updatedAt() {
        return ZonedDateTime.parse(json.get("fields").get("updated").asString());
    }

    @Override
    public void setState(State state) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void addLabel(String label) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void removeLabel(String label) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public List<String> labels() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(jiraProject.webUrl())
                         .setPath("/browse/" + id())
                         .build();
    }

    @Override
    public List<HostUser> assignees() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        throw new RuntimeException("not implemented yet");
    }
}
