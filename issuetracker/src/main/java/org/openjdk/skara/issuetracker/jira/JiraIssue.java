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
package org.openjdk.skara.issuetracker.jira;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class JiraIssue implements Issue {
    private final JiraProject jiraProject;
    private final RestRequest request;
    private final JSONValue json;

    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

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
        var query = JSON.object()
                        .put("fields", JSON.object()
                                           .put("summary", title));
        request.put("").body(query).execute();
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
        var query = JSON.object()
                        .put("fields", JSON.object()
                                           .put("description", body));
        request.put("").body(query).execute();
    }

    private Comment parseComment(JSONValue json) {
        return new Comment(json.get("id").asString(),
                           json.get("body").asString(),
                           new HostUser(json.get("author").get("name").asString(),
                                        json.get("author").get("name").asString(),
                                        json.get("author").get("displayName").asString()),
                           ZonedDateTime.parse(json.get("created").asString(), dateFormat),
                           ZonedDateTime.parse(json.get("updated").asString(), dateFormat));
    }

    @Override
    public List<Comment> comments() {
        var comments = request.get("/comment")
                              .param("maxResults", "1000")
                              .execute();
        return comments.get("comments").stream()
                       .map(this::parseComment)
                       .collect(Collectors.toList());
    }

    @Override
    public Comment addComment(String body) {
        var json = request.post("/comment")
                          .body("body", body)
                          .execute();
        return parseComment(json);
    }

    @Override
    public Comment updateComment(String id, String body) {
        var json = request.put("/comment/" + id)
                          .body("body", body)
                          .execute();
        return parseComment(json);
    }

    @Override
    public ZonedDateTime createdAt() {
        return ZonedDateTime.parse(json.get("fields").get("created").asString(), dateFormat);
    }

    @Override
    public ZonedDateTime updatedAt() {
        return ZonedDateTime.parse(json.get("fields").get("updated").asString(), dateFormat);
    }

    private String stateName(State state) {
        switch (state) {
            case OPEN:
                return "Open";
            case RESOLVED:
                return "Resolved";
            case CLOSED:
                return "Closed";
            default:
                throw new IllegalStateException("Unknown state " + state);
        }
    }

    private Map<String, String> availableTransitions() {
        var transitions = request.get("/transitions").execute();
        return transitions.get("transitions").stream()
                          .collect(Collectors.toMap(v -> v.get("to").get("name").asString(),
                                                    v -> v.get("id").asString()));
    }

    private void performTransition(String id) {
        var query = JSON.object()
                        .put("transition", JSON.object()
                                               .put("id", id));
        request.post("/transitions")
               .body(query)
               .execute();
    }

    @Override
    public void setState(State state) {
        var availableTransitions = availableTransitions();

        // Handle special cases
        if (state == State.RESOLVED) {
            if (!availableTransitions.containsKey("Resolved")) {
                if (availableTransitions.containsKey("Open")) {
                    performTransition(availableTransitions.get("Open"));
                    availableTransitions = availableTransitions();
                    if (!availableTransitions.containsKey("Resolved")) {
                        throw new RuntimeException("Cannot transition to Resolved after Open");
                    }
                } else {
                    // The issue is most likely closed - skip transitioning
                    return;
                }
            }
            performTransition(availableTransitions.get("Resolved"));
        } else if (state == State.CLOSED) {
            if (!availableTransitions.containsKey("Closed")) {
                if (availableTransitions.containsKey("Resolved")) {
                    performTransition(availableTransitions.get("Resolved"));
                    availableTransitions = availableTransitions();
                    if (!availableTransitions.containsKey("Closed")) {
                        throw new RuntimeException("Cannot transition to Closed after Resolved");
                    }
                } else {
                    throw new RuntimeException("Cannot transition to Closed");
                }
                performTransition(availableTransitions.get("Closed"));
            }
        } else if (state == State.OPEN) {
            if (!availableTransitions.containsKey("Open")) {
                throw new RuntimeException("Cannot transition to Open");
            }
            performTransition(availableTransitions.get("Open"));
        }
    }

    @Override
    public void addLabel(String label) {
        var query = JSON.object()
                        .put("update", JSON.object()
                                           .put("labels", JSON.array().add(JSON.object()
                                                                               .put("add", label))));
        request.put("").body(query).execute();
    }

    @Override
    public void removeLabel(String label) {
        var query = JSON.object()
                        .put("update", JSON.object()
                                           .put("labels", JSON.array().add(JSON.object()
                                                                               .put("remove", label))));
        request.put("").body(query).execute();
    }

    @Override
    public List<String> labels() {
        return json.get("fields").get("labels").stream()
                   .map(JSONValue::asString)
                   .collect(Collectors.toList());
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(jiraProject.webUrl())
                         .setPath("/browse/" + id())
                         .build();
    }

    @Override
    public List<HostUser> assignees() {
        var assignee = json.get("fields").get("assignee");
        if (assignee.isNull()) {
            return List.of();
        }

        var user = new HostUser(assignee.get("name").asString(),
                                assignee.get("name").asString(),
                                assignee.get("displayName").asString());
        return List.of(user);
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        String assignee;
        switch (assignees.size()) {
            case 0:
                assignee = null;
                break;
            case 1:
                assignee = assignees.get(0).id();
                break;
            default:
                throw new RuntimeException("multiple assignees not supported");
        }
        request.put("/assignee")
               .body("name", assignee)
               .execute();
    }
}
