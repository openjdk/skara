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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.util.*;
import java.time.*;
import java.net.*;

class InMemoryPullRequest implements PullRequest {
    List<Comment> comments = new ArrayList<Comment>();
    List<Review> reviews = new ArrayList<Review>();
    HostUser author;
    HostedRepository repository;
    Hash headHash;
    String id;
    Map<String, Map<String, Check>> checks = new HashMap<>();

    @Override
    public HostedRepository repository() {
        return repository;
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
    public List<Review> reviews() {
        return reviews;
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
    }

    @Override
    public ReviewComment addReviewComment(Hash base, Hash hash, String path, int line, String body) {
        return null;
    }

    @Override
    public ReviewComment addReviewCommentReply(ReviewComment parent, String body) {
        return null;
    }

    @Override
    public List<ReviewComment> reviewComments() {
        return null;
    }

    @Override
    public Hash headHash() {
        return headHash;
    }

    @Override
    public String fetchRef() {
        return null;
    }

    @Override
    public String sourceRef() {
        return null;
    }

    @Override
    public HostedRepository sourceRepository() {
        return null;
    }

    @Override
    public String targetRef() {
        return null;
    }

    @Override
    public Hash targetHash() {
        return null;
    }

    @Override
    public String title() {
        return null;
    }

    @Override
    public String body() {
        return null;
    }

    @Override
    public void setBody(String body) {
    }

    @Override
    public List<Comment> comments() {
        return comments;
    }
    void setComments(List<Comment> comments) {
        this.comments = comments;
    }

    @Override
    public Comment addComment(String body) {
        var user = repository().forge().currentUser();
        var now = ZonedDateTime.now();
        var id = comments.size();
        var comment = new Comment(Integer.toString(id), body, user, now, now);
        comments.add(comment);
        return comment;
    }

    @Override
    public Comment updateComment(String id, String body) {
        var index = Integer.parseInt(id);
        var old = comments.get(index);

        var now = ZonedDateTime.now();
        var newComment = new Comment(id, body, old.author(), old.createdAt(), now);
        comments.set(index, newComment);
        return newComment;
    }

    @Override
    public ZonedDateTime createdAt() {
        return null;
    }

    @Override
    public ZonedDateTime updatedAt() {
        return null;
    }

    @Override
    public State state() {
        return null;
    }

    @Override
    public Map<String, Check> checks(Hash hash) {
        return checks.get(hash.hex());
    }

    @Override
    public void createCheck(Check check) {
        if (!checks.containsKey(check.hash().hex())) {
            checks.put(check.hash().hex(), new HashMap<>());
        }
        checks.get(check.hash().hex()).put(check.name(), check);
    }

    @Override
    public void updateCheck(Check check) {
        if (checks.containsKey(check.hash().hex())) {
            checks.get(check.hash().hex()).put(check.name(), check);
        }
    }

    @Override
    public URI changeUrl() {
        return null;
    }

    @Override
    public URI changeUrl(Hash base) {
        return null;
    }

    @Override
    public boolean isDraft() {
        return false;
    }

    @Override
    public void setState(State state) {
    }

    @Override
    public void addLabel(String label) {
    }

    @Override
    public void removeLabel(String label) {
    }

    @Override
    public List<String> labels() {
        return null;
    }

    @Override
    public URI webUrl() {
        return null;
    }

    @Override
    public List<HostUser> assignees() {
        return null;
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
    }

    @Override
    public List<Link> links() {
        return null;
    }

    @Override
    public void addLink(Link link) {

    }

    @Override
    public void removeLink(Link link) {

    }

    @Override
    public Map<String, JSONValue> properties() {
        return null;
    }

    @Override
    public void setProperty(String name, JSONValue value) {

    }

    @Override
    public void removeProperty(String name) {

    }

    @Override
    public void setTitle(String title) {
    }

    @Override
    public IssueProject project() {
        return null;
    }

    @Override
    public void makeNotDraft() {

    }

    @Override
    public URI diffUrl() {
        return null;
    }
}
