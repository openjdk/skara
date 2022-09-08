/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.URIBuilder;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TestIssue is the object returned from a TestHost when queried for issues.
 * It's backed by a TestIssueStore, which tracks the "server side" state of the
 * issue. A TestIssue object contains a snapshot of the server side state for
 * all data directly related to the issue. What data is snapshotted and what
 * is fetched on request should be the same as for JiraIssue.
 */
public class TestIssue implements Issue {
    private final TestIssueStore store;
    protected final HostUser user;

    protected final HostUser author;
    protected final String body;
    protected final String title;
    protected final State state;
    // Mimic JiraIssue where labels are part of the main JSON object
    private final List<Label> labels;
    protected ZonedDateTime lastUpdate;

    protected TestIssue(TestIssueStore store, HostUser user) {
        this.store = store;
        this.user = user;
        this.lastUpdate = store.lastUpdate();
        this.state = store.state();
        this.author = store.author();
        this.body = store.body();
        this.title = store.title();
        this.labels = store.labels().keySet().stream().map(Label::new).collect(Collectors.toList());
    }

    @Override
    public IssueProject project() {
        return store.issueProject();
    }

    @Override
    public String id() {
        return store.id();
    }

    @Override
    public HostUser author() {
        return store.author();
    }

    @Override
    public String title() {
        return store.title().strip();
    }

    @Override
    public void setTitle(String title) {
        // the strip simulates gitlab behavior
        store.setTitle(title.strip());
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public String body() {
        return store.body();
    }

    @Override
    public void setBody(String body) {
        store.setBody(body);
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public List<Comment> comments() {
        return new ArrayList<>(store.comments());
    }

    @Override
    public Comment addComment(String body) {
        List<Comment> comments = store.comments();
        var size = comments.size();
        var lastId = size > 0 ? comments.get(size - 1).id() : null;
        var comment = new Comment(String.valueOf(lastId != null ? Integer.parseInt(lastId) + 1 : 0),
                                  body,
                                  user,
                                  ZonedDateTime.now(),
                                  ZonedDateTime.now());
        store.comments().add(comment);
        store.setLastUpdate(ZonedDateTime.now());
        return comment;
    }

    @Override
    public void removeComment(Comment comment) {
        store.comments().remove(comment);
    }

    @Override
    public Comment updateComment(String id, String body) {
        var originalComment = store.comments().stream()
                .filter(comment -> comment.id().equals(id)).findAny().orElseThrow();
        var index = comments().indexOf(originalComment);
        var comment = new Comment(originalComment.id(),
                                  body,
                                  originalComment.author(),
                                  originalComment.createdAt(),
                                  ZonedDateTime.now());
        store.comments().set(index, comment);
        store.setLastUpdate(ZonedDateTime.now());
        return comment;
    }

    @Override
    public ZonedDateTime createdAt() {
        return store.created();
    }

    @Override
    public ZonedDateTime updatedAt() {
        return lastUpdate;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void setState(State state) {
        store.setState(state);
        store.setLastUpdate(ZonedDateTime.now());
        store.setClosedBy(user);
        if (state == State.RESOLVED || state == State.CLOSED) {
            store.properties().put("resolution", JSON.object().put("name", JSON.of("Fixed")));
        }
    }

    /**
     * This implementation mimics the JiraIssue definition of isFixed and is
     * needed to test handling of backports.
     */
    @Override
    public boolean isFixed() {
        if (isResolved() || isClosed()) {
            var resolution = store.properties().get("resolution");
            if (!resolution.isNull()) {
                return "Fixed".equals(resolution.get("name").asString());
            }
        }
        return false;
    }

    @Override
    public void addLabel(String label) {
        var now = ZonedDateTime.now();
        store.labels().put(label, now);
        store.setLastUpdate(now);
    }

    @Override
    public void removeLabel(String label) {
        store.labels().remove(label);
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public void setLabels(List<String> labels) {
        store.labels().clear();
        var now = ZonedDateTime.now();
        for (var label : labels) {
            store.labels().put(label, now);
        }
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public List<Label> labels() {
        return labels;
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(store.issueProject().webUrl()).appendPath(id()).build();
    }

    @Override
    public List<HostUser> assignees() {
        return new ArrayList<>(store.assignees());
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        store.assignees().clear();
        store.assignees().addAll(assignees);
        store.setLastUpdate(ZonedDateTime.now());
    }

    /**
     * When links are returned, they need to contain fresh snapshots of any TestIssue.
     */
    @Override
    public List<Link> links() {
        return store.links().stream()
                .map(this::updateLinkIssue)
                .toList();
    }

    private Link updateLinkIssue(Link link) {
        if (link.issue().isPresent()) {
            var issue = (TestIssue) link.issue().get();
            return Link.create(issue.copy(), link.relationship().orElseThrow()).build();
        } else {
            return link;
        }
    }

    protected TestIssue copy() {
        return new TestIssue(store, user);
    }

    @Override
    public void addLink(Link link) {
        if (link.uri().isPresent()) {
            removeLink(link);
            store.links().add(link);
        } else if (link.issue().isPresent()) {
            var existing = store.links().stream()
                    .filter(l -> l.issue().isPresent() && l.issue().get().id().equals(link.issue().orElseThrow().id()))
                    .findAny();
            existing.ifPresent(store.links()::remove);
            store.links().add(link);
            if (existing.isEmpty()) {
                var map = Map.of("backported by", "backport of", "backport of", "backported by",
                        "csr for", "csr of", "csr of", "csr for",
                        "blocks", "is blocked by", "is blocked by", "blocks",
                        "clones", "is cloned by", "is cloned by", "clones");
                var reverseRelationship = map.getOrDefault(link.relationship().orElseThrow(), link.relationship().orElseThrow());
                var reverse = Link.create(this, reverseRelationship).build();
                link.issue().get().addLink(reverse);
            }
        } else {
            throw new IllegalArgumentException("Can't add unknown link type: " + link);
        }
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public void removeLink(Link link) {
        if (link.uri().isPresent()) {
            store.links().removeIf(l -> l.uri().equals(link.uri()));
        } else if (link.issue().isPresent()) {
            var existing = store.links().stream()
                                     .filter(l -> l.issue().orElseThrow().id().equals(link.issue().orElseThrow().id()))
                                     .findAny();
            if (existing.isPresent()) {
                store.links().remove(existing.get());
                var reverse = Link.create(this, "").build();
                link.issue().get().removeLink(reverse);
            }
        } else {
            throw new IllegalArgumentException("Can't remove unknown link type: " + link);
        }
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public Map<String, JSONValue> properties() {
        return store.properties();
    }

    @Override
    public void setProperty(String name, JSONValue value) {
        store.properties().put(name, value);
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public void removeProperty(String name) {
        store.properties().remove(name);
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public Optional<HostUser> closedBy() {
        return isClosed() ? Optional.of(store.closedBy()) : Optional.empty();
    }

    public void setLastUpdate(ZonedDateTime time) {
        lastUpdate = time;
    }

    /**
     * Equals for a TestIssue means that all the snapshotted data is the same.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        var testIssue = (TestIssue) o;
        return Objects.equals(author, testIssue.author) &&
                Objects.equals(body, testIssue.body) &&
                Objects.equals(title, testIssue.title) &&
                Objects.equals(lastUpdate, testIssue.lastUpdate) &&
                Objects.equals(labels, testIssue.labels) &&
                state == testIssue.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(author, body, title, lastUpdate, labels, state);
    }

    /**
     * Gives test code direct access to the backing store object to be able to
     * inspect and manipulate state directly.
     */
    public TestIssueStore store() {
        return store;
    }
}
