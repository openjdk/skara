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

import org.openjdk.skara.host.*;
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

    static TestIssue createNew(TestIssueProject issueProject, String id, String title, List<String> body) {
        var data = new IssueData();
        data.title = title;
        data.body = String.join("\n", body);
        var issue = new TestIssue(issueProject, id, issueProject.host().currentUser(), issueProject.host().currentUser(), data);
        return issue;
    }

    static TestIssue createFrom(TestIssueProject issueProject, TestIssue other) {
        var issue = new TestIssue(issueProject, other.id, other.author, issueProject.host().currentUser(), other.data);
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
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        throw new RuntimeException("not implemented yet");
    }
}
