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
import org.openjdk.skara.network.URIBuilder;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

public class TestIssue implements Issue {
    protected final String id;
    protected final IssueProject issueProject;
    protected final HostUser author;
    protected final HostUser user;
    protected final IssueData data;

    protected TestIssue(TestIssueProject issueProject, String id, HostUser author, HostUser user, IssueData data) {
        this.id = id;
        this.issueProject = issueProject;
        this.author = author;;
        this.user = user;
        this.data = data;
    }

    static TestIssue createNew(TestIssueProject issueProject, String id, String title, List<String> body, Map<String, String> properties) {
        var data = new IssueData();
        data.title = title;
        data.body = String.join("\n", body);
        data.properties.putAll(properties);
        var issue = new TestIssue(issueProject, id, issueProject.issueTracker().currentUser(), issueProject.issueTracker().currentUser(), data);
        return issue;
    }

    static TestIssue createFrom(TestIssueProject issueProject, TestIssue other) {
        var issue = new TestIssue(issueProject, other.id, other.author, issueProject.issueTracker().currentUser(), other.data);
        return issue;
    }

    @Override
    public IssueProject project() {
        return issueProject;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public HostUser author() {
        return author;
    }

    @Override
    public String title() {
        return data.title;
    }

    @Override
    public void setTitle(String title) {
        data.title = title;
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public String body() {
        return data.body;
    }

    @Override
    public void setBody(String body) {
        data.body = body;
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public List<Comment> comments() {
        return new ArrayList<>(data.comments);
    }

    @Override
    public Comment addComment(String body) {
        var comment = new Comment(String.valueOf(data.comments.size()),
                                  body,
                                  user,
                                  ZonedDateTime.now(),
                                  ZonedDateTime.now());
        data.comments.add(comment);
        data.lastUpdate = ZonedDateTime.now();
        return comment;
    }

    @Override
    public Comment updateComment(String id, String body) {
        var originalComment = data.comments.get(Integer.parseInt(id));
        var comment = new Comment(originalComment.id(),
                                  body,
                                  originalComment.author(),
                                  originalComment.createdAt(),
                                  ZonedDateTime.now());
        data.comments.remove(Integer.parseInt(id));
        data.comments.add(Integer.parseInt(id), comment);
        data.lastUpdate = ZonedDateTime.now();
        return comment;
    }

    @Override
    public ZonedDateTime createdAt() {
        return data.created;
    }

    @Override
    public ZonedDateTime updatedAt() {
        return data.lastUpdate;
    }

    @Override
    public void setState(State state) {
        data.state = state;
        data.lastUpdate = ZonedDateTime.now();
    }

    boolean isOpen() {
        return data.state.equals(Issue.State.OPEN);
    }

    @Override
    public void addLabel(String label) {
        data.labels.add(label);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public void removeLabel(String label) {
        data.labels.remove(label);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public List<String> labels() {
        return new ArrayList<>(data.labels);
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(issueProject.webUrl()).appendPath(id).build();
    }

    @Override
    public List<HostUser> assignees() {
        return new ArrayList<>(data.assignees);
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        data.assignees.clear();
        data.assignees.addAll(assignees);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public List<Link> links() {
        return data.links;
    }

    @Override
    public void addLink(Link link) {
        if (link.uri().isPresent()) {
            removeLink(link);
            data.links.add(link);
        } else if (link.issue().isPresent()) {
            var existing = data.links.stream()
                                     .filter(l -> l.issue().equals(link.issue()))
                                     .findAny();
            existing.ifPresent(data.links::remove);
            data.links.add(link);
            if (existing.isEmpty()) {
                var reverse = Link.create(this, link.relationship().get()).build();
                link.issue().get().addLink(reverse);
            }
        } else {
            throw new IllegalArgumentException("Can't add unknown link type: " + link);
        }
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public void removeLink(Link link) {
        if (link.uri().isPresent()) {
            data.links.removeIf(l -> l.uri().equals(link.uri()));
        } else if (link.issue().isPresent()) {
            var existing = data.links.stream()
                                     .filter(l -> l.issue().equals(link.issue()))
                                     .findAny();
            if (existing.isPresent()) {
                data.links.remove(existing.get());
                var reverse = Link.create(this, "").build();
                link.issue().get().removeLink(reverse);
            }
        } else {
            throw new IllegalArgumentException("Can't remove unknown link type: " + link);
        }
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public List<String> fixVersions() {
        return new ArrayList<>(data.fixVersions);
    }

    @Override
    public void addFixVersion(String fixVersion) {
        data.fixVersions.add(fixVersion);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public void removeFixVersion(String fixVersion) {
        data.fixVersions.remove(fixVersion);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public Map<String, String> properties() {
        return data.properties;
    }

    @Override
    public void setProperty(String name, String value) {
        data.properties.put(name, value);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public void removePropery(String name) {
        data.properties.remove(name);
        data.lastUpdate = ZonedDateTime.now();
    }
}
