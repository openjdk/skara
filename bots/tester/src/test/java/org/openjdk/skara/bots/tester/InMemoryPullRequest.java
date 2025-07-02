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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.util.*;
import java.util.stream.Collectors;
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
    Set<String> labels = new TreeSet<>();

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
    public void updateReview(String id, String body) {
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
    public Optional<HostedRepository> sourceRepository() {
        return Optional.empty();
    }

    @Override
    public String targetRef() {
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
        var size = comments.size();
        var lastId = size > 0 ? comments.get(size - 1).id() : null;
        var comment = new Comment(String.valueOf(lastId != null ? Integer.parseInt(lastId) + 1 : 0), body, user, now, now);
        comments.add(comment);
        return comment;
    }

    @Override
    public void removeComment(Comment comment) {
        comments.remove(comment);
    }

    @Override
    public Comment updateComment(String id, String body) {
        var old = comments.stream()
                .filter(comment -> comment.id().equals(id)).findAny().get();
        var index = comments().indexOf(old);

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
    public URI commentUrl(Comment comment) {
        return null;
    }

    @Override
    public URI reviewCommentUrl(ReviewComment reviewComment) {
        return null;
    }

    @Override
    public URI reviewUrl(Review review) {
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
        labels.add(label);
    }

    @Override
    public void removeLabel(String label) {
        labels.remove(label);
    }

    @Override
    public void setLabels(List<String> labels) {
        this.labels = new HashSet<>(labels);
    }

    @Override
    public List<Label> labels() {
        return labels.stream().map(s -> new Label(s)).collect(Collectors.toList());
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
    public Optional<ZonedDateTime> lastMarkedAsDraftTime() {
        return Optional.empty();
    }

    @Override
    public URI diffUrl() {
        return null;
    }

    @Override
    public Optional<ZonedDateTime> labelAddedAt(String label) {
        return null;
    }

    @Override
    public void setTargetRef(String targetRef) {

    }

    @Override
    public URI headUrl() {
        return null;
    }

    @Override
    public Diff diff() {
        return null;
    }

    @Override
    public Optional<HostUser> closedBy() {
        return Optional.empty();
    }

    @Override
    public URI filesUrl(Hash hash) {
        return null;
    }

    @Override
    public Optional<ZonedDateTime> lastForcePushTime() {
        return Optional.empty();
    }

    @Override
    public Optional<Hash> findIntegratedCommitHash() {
        return Optional.empty();
    }

    @Override
    public Object snapshot() {
        return this;
    }

    @Override
    public List<ReferenceChange> targetRefChanges() {
        return List.of();
    }

    @Override
    public ZonedDateTime lastTouchedTime() {
        return null;
    }
}
