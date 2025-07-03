/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.network.URIBuilder;

/**
 * Base class with common functionality for TestIssueTrackerIssue and TestPullRequest
 */
public class TestIssue implements Issue {
    private final TestIssueStore store;
    protected final HostUser user;

    protected final HostUser author;
    protected final String body;
    protected final String title;
    protected final State state;
    // Labels are cached but still kept up to date
    private List<Label> labels;
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
        return body;
    }

    @Override
    public void setBody(String body) {
        store.setBody(body);
        store.setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public List<Comment> comments() {
        return List.copyOf(store.comments());
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
    public URI commentUrl(Comment comment) {
        return URIBuilder.base(webUrl()).appendPath("?focusedCommentId=" + comment.id()).build();
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
        store.setLastTouchedTime(ZonedDateTime.now());
        store.setClosedBy(user);
    }

   @Override
    public boolean isFixed() {
        return isResolved() || isClosed();
    }

    @Override
    public void addLabel(String label) {
        labels = null;
        var now = ZonedDateTime.now();
        store.labels().put(label, now);
        store.setLastUpdate(now);
    }

    @Override
    public void removeLabel(String label) {
        labels = null;
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
        this.labels = labels.stream().map(Label::new).collect(Collectors.toList());
    }

    @Override
    public List<Label> labels() {
        if (labels == null) {
            labels = store.labels().keySet().stream().map(Label::new).collect(Collectors.toList());
        }
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
        return Objects.equals(store.id(), testIssue.store.id()) &&
                Objects.equals(author, testIssue.author) &&
                Objects.equals(body, testIssue.body) &&
                Objects.equals(title, testIssue.title) &&
                Objects.equals(lastUpdate, testIssue.lastUpdate) &&
                Objects.equals(labels, testIssue.labels) &&
                state == testIssue.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(store.id(), author, body, title, lastUpdate, labels, state);
    }

    /**
     * Gives test code direct access to the backing store object to be able to
     * inspect and manipulate state directly.
     */
    public TestIssueStore store() {
        return store;
    }
}
