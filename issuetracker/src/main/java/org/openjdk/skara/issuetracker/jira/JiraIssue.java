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
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;

public class JiraIssue implements Issue {
    private final JiraProject jiraProject;
    private final RestRequest request;
    private final JSONValue json;
    private final boolean needSecurity;

    private final Logger log = Logger.getLogger("org.openjdk.skara.issuetracker.jira");

    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    JiraIssue(JiraProject jiraProject, RestRequest request, JSONValue json) {
        this.jiraProject = jiraProject;
        this.request = request;
        this.json = json;

        needSecurity = jiraProject.jiraHost().securityLevel().isPresent();
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
        if (needSecurity) {
            log.warning("Issue title does not support setting a security level - ignoring");
            return;
        }
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
        if (needSecurity) {
            log.warning("Issue body does not support setting a security level - ignoring");
            return;
        }
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
        var query = JSON.object().put("body", body);
        jiraProject.jiraHost().visibilityRole().ifPresent(visibility -> query.put("visibility", JSON.object()
                                                                                                    .put("type", "role")
                                                                                                    .put("value", visibility)));
        var json = request.post("/comment")
                          .body(query)
                          .execute();
        return parseComment(json);
    }

    @Override
    public Comment updateComment(String id, String body) {
        var query = JSON.object().put("body", body);
        jiraProject.jiraHost().visibilityRole().ifPresent(visibility -> query.put("visibility", JSON.object()
                                                                                                    .put("type", "role")
                                                                                                    .put("value", visibility)));
        var json = request.put("/comment/" + id)
                          .body(query)
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

    @Override
    public State state() {
        switch (json.get("fields").get("status").get("name").asString()) {
            case "Closed":
                return State.CLOSED;
            case "Resolved":
                return State.RESOLVED;
            default:
                return State.OPEN;
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
        } else {
            throw new IllegalStateException("Unknown state " + state);
        }
    }

    @Override
    public void addLabel(String label) {
        if (needSecurity) {
            log.warning("Issue label does not support setting a security level - ignoring");
            return;
        }
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

    private Link parseLink(JSONObject json) {
        var link = Link.create(URI.create(json.get("object").get("url").asString()), json.get("object").get("title").asString());
        if (json.contains("relationship")) {
            link.relationship(json.get("relationship").asString());
        }
        if (json.get("object").contains("summary")) {
            link.summary(json.get("object").get("summary").asString());
        }
        if (json.get("object").contains("icon")) {
            if (json.get("object").get("icon").contains("url16x16")) {
                link.iconUrl(URI.create(json.get("object").get("icon").get("url16x16").asString()));
            }
            if (json.get("object").get("icon").contains("title")) {
                link.iconTitle(json.get("object").get("icon").get("title").asString());
            }
        }
        if (json.get("object").get("status").contains("icon")) {
            if (json.get("object").get("status").get("icon").contains("url16x16")) {
                link.statusIconUrl(URI.create(json.get("object").get("status").get("icon").get("url16x16").asString()));
            }
            if (json.get("object").get("status").get("icon").contains("title")) {
                link.statusIconTitle(json.get("object").get("status").get("icon").get("title").asString());
            }
        }
        link.resolved(json.get("object").get("status").get("resolved").asBoolean());
        return link.build();
    }

    @Override
    public List<Link> links() {
        var result = request.get("/remotelink").execute();
        var links = result.stream()
                          .map(JSONValue::asObject)
                          .filter(obj -> obj.contains("globalId"))
                          .filter(obj -> obj.get("globalId").asString().startsWith("skaralink="))
                          .map(this::parseLink);

        var commentLinks = comments().stream()
                                     .map(this::parseWebLinkComment)
                                     .filter(Optional::isPresent)
                                     .map(Optional::get);
        links = Stream.concat(commentLinks, links);

        if (json.get("fields").contains("issuelinks")) {
            var issueLinks = json.get("fields").get("issuelinks").stream()
                                 .map(JSONValue::asObject)
                                 .map(o -> Link.create(o.contains("inwardIssue") ? jiraProject.issue(o.get("inwardIssue").get("key").asString()).orElseThrow() :
                                                               jiraProject.issue(o.get("outwardIssue").get("key").asString()).orElseThrow(),
                                                       o.contains("inwardIssue") ? o.get("type").get("inward").asString() :
                                                               o.get("type").get("outward").asString())
                                               .build());

            links = Stream.concat(issueLinks, links);
        }

        return links.collect(Collectors.toList());
    }

    private static final Pattern titlePattern = Pattern.compile("^Remote link: (.*)");
    private static final Pattern urlPattern = Pattern.compile("^URL: (.*)");
    private static final Pattern summaryPattern = Pattern.compile("^Summary: (.*)");
    private static final Pattern relationshipPattern = Pattern.compile("^Relationship: (.*)");

    private Optional<Link> parseWebLinkComment(Comment comment) {
        var lines = comment.body().lines().collect(Collectors.toList());
        if ((lines.size() < 2 ) || (lines.size() > 4)) {
            return Optional.empty();
        }
        var titleMatcher = titlePattern.matcher(lines.get(0));
        var urlMatcher = urlPattern.matcher(lines.get(1));
        if (!titleMatcher.matches() || !urlMatcher.matches()) {
            return Optional.empty();
        }
        var linkBuilder = Link.create(URI.create(urlMatcher.group(1)), titleMatcher.group(1));
        for (int i = 2; i < lines.size(); ++i) {
            var line = lines.get(i);
            var summaryMatcher = summaryPattern.matcher(line);
            if (summaryMatcher.matches()) {
                linkBuilder.summary(summaryMatcher.group(1));
            }
            var relationshipMatcher = relationshipPattern.matcher(line);
            if (relationshipMatcher.matches()) {
                linkBuilder.relationship(relationshipMatcher.group(1));
            }
        }
        return Optional.of(linkBuilder.build());
    }

    private void addWebLinkAsComment(Link link) {
        var body = new StringBuilder();
        body.append("Remote link: ").append(link.title().orElseThrow()).append("\n");
        body.append("URL: ").append(link.uri().orElseThrow().toString()).append("\n");
        link.summary().ifPresent(summary -> body.append("Summary: ").append(summary).append("\n"));
        link.relationship().ifPresent(relationship -> body.append("Relationship: ").append(relationship).append("\n"));

        addComment(body.toString());
    }

    private void addWebLink(Link link) {
        if (needSecurity) {
            addWebLinkAsComment(link);
            return;
        }

        var query = JSON.object().put("globalId", "skaralink=" + link.uri().orElseThrow().toString());
        var object = JSON.object().put("url", link.uri().orElseThrow().toString()).put("title", link.title().orElseThrow());
        var status = JSON.object().put("resolved", link.resolved());
        var icon = JSON.object();
        var statusIcon = JSON.object();

        query.put("object", object);
        object.put("icon", icon);
        object.put("status", status);
        status.put("icon", statusIcon);

        link.relationship().ifPresent(relationship -> query.put("relationship", relationship));
        link.summary().ifPresent(summary -> object.put("summary", summary));
        link.iconUrl().ifPresent(iconUrl -> icon.put("url16x16", iconUrl.toString()));
        link.iconTitle().ifPresent(iconTitle -> icon.put("title", iconTitle));
        link.statusIconUrl().ifPresent(statusIconUrl -> statusIcon.put("url16x16", statusIconUrl.toString()));
        link.statusIconTitle().ifPresent(statusIconTitle -> statusIcon.put("title", statusIconTitle));

        request.post("/remotelink")
               .body(query)
               .execute();
    }

    private boolean matchLinkType(JiraLinkType jiraLinkType, Link link) {
        var relationship = link.relationship().orElseThrow().toLowerCase();
        return (jiraLinkType.inward().toLowerCase().equals(relationship)) ||
                (jiraLinkType.outward().toLowerCase().equals(relationship));
    }

    private boolean isOutwardLink(JiraLinkType jiraLinkType, Link link) {
        var relationship = link.relationship().orElseThrow().toLowerCase();
        return jiraLinkType.outward().toLowerCase().equals(relationship);
    }

    private void addIssueLink(Link link) {
        var linkType = jiraProject.linkTypes().stream()
                                  .filter(lt -> matchLinkType(lt, link))
                                  .findAny().orElseThrow();

        var query = JSON.object()
                        .put("type", JSON.object().put("name", linkType.name()));
        if (isOutwardLink(linkType, link)) {
            query.put("inwardIssue", JSON.object().put("key", id()));
            query.put("outwardIssue", JSON.object().put("key", link.issue().orElseThrow().id()));
        } else {
            query.put("outwardIssue", JSON.object().put("key", id()));
            query.put("inwardIssue", JSON.object().put("key", link.issue().orElseThrow().id()));
        }

        jiraProject.executeLinkQuery(query);
    }

    @Override
    public void addLink(Link link) {
        if (link.uri().isPresent() && link.title().isPresent()) {
            addWebLink(link);
        } else if (link.issue().isPresent() && link.relationship().isPresent()) {
            addIssueLink(link);
        } else {
            throw new IllegalArgumentException("Unknown type of link: " + link);
        }
    }

    private void removeWebLink(Link link) {
        request.delete("/remotelink")
               .param("globalId", "skaralink=" + link.uri().orElseThrow().toString())
               .onError(e -> e.statusCode() == 404 ? Optional.of(JSON.object().put("already_deleted", true)) : Optional.empty())
               .execute();

        for (var comment : comments()) {
            var commentLink = parseWebLinkComment(comment);
            if (commentLink.isEmpty()) {
                continue;
            }
            if (!commentLink.get().uri().equals(link.uri())) {
                continue;
            }
            request.delete("/comment/" + comment.id()).execute();
        }
    }

    private void removeIssueLink(Link link) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void removeLink(Link link) {
        if (link.uri().isPresent()) {
            removeWebLink(link);
        } else if (link.issue().isPresent() && link.relationship().isPresent()) {
            removeIssueLink(link);
        } else {
            throw new IllegalArgumentException("Unknown type of link: " + link);
        }
    }

    @Override
    public Map<String, JSONValue> properties() {
        var ret = new HashMap<String, JSONValue>();

        for (var field : json.get("fields").asObject().fields()) {
            var value = field.value();
            var decoded = jiraProject.decodeProperty(field.name(), value);
            decoded.ifPresent(jsonValue -> ret.put(field.name(), jsonValue));
        }
        return ret;
    }

    @Override
    public void setProperty(String name, JSONValue value) {
        var encoded = jiraProject.encodeProperty(name, value);
        if (encoded.isEmpty()) {
            log.warning("Ignoring unknown property: " + name);
            return;
        }
        var query = JSON.object().put("fields", JSON.object().put(name, encoded.get()));
        request.put("").body(query).execute();
    }

    @Override
    public void removeProperty(String name) {

    }
}
