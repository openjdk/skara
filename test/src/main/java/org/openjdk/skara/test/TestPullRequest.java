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
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.net.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestPullRequest implements PullRequest {
    private final TestHostedRepository repository;
    private final String id;
    private final HostUserDetails author;
    private final HostUserDetails user;
    private final String targetRef;
    private final String sourceRef;
    private final String title;
    private final List<String> body;
    private final PullRequestData data;

    private static class PullRequestData {
        private Hash headHash;
        PullRequest.State state = PullRequest.State.OPEN;
        String body = "";
        final List<Comment> comments = new ArrayList<>();
        final List<ReviewComment> reviewComments = new ArrayList<>();
        final Set<Check> checks = new HashSet<>();
        final Set<String> labels = new HashSet<>();
        final List<Review> reviews = new ArrayList<>();
        ZonedDateTime created = ZonedDateTime.now();
        ZonedDateTime lastUpdate = created;
    }

    private TestPullRequest(TestHostedRepository repository, String id, HostUserDetails author, HostUserDetails user, String targetRef, String sourceRef, String title, List<String> body, PullRequestData data) {
        this.repository = repository;
        this.id = id;
        this.author = author;
        this.user = user;
        this.targetRef = targetRef;
        this.sourceRef = sourceRef;
        this.title = title;
        this.body = body;
        this.data = data;

        try {
            var headHash = repository.localRepository().resolve(sourceRef).orElseThrow();
            if (!headHash.equals(data.headHash)) {
                data.headHash = headHash;
                data.lastUpdate = ZonedDateTime.now();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TestPullRequest createNew(TestHostedRepository repository, String id, String targetRef, String sourceRef, String title, List<String> body) {
        var data = new PullRequestData();
        data.body = String.join("\n", body);
        var pr = new TestPullRequest(repository, id, repository.host().getCurrentUserDetails(), repository.host().getCurrentUserDetails(), targetRef, sourceRef, title, body, data);
        return pr;
    }

    static TestPullRequest createFrom(TestHostedRepository repository, TestPullRequest other) {
        var pr = new TestPullRequest(repository, other.id, other.author, repository.host().getCurrentUserDetails(), other.targetRef, other.sourceRef, other.title, other.body, other.data);
        return pr;
    }

    @Override
    public HostedRepository repository() {
        return repository;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public HostUserDetails getAuthor() {
        return author;
    }

    @Override
    public List<Review> getReviews() {
        return new ArrayList<>(data.reviews);
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
        try {
            var review = new Review(repository.host().getCurrentUserDetails(),
                                    verdict, repository.localRepository().resolve(sourceRef).orElseThrow(),
                                    data.reviews.size(),
                                    body);

            data.reviews.add(review);
            data.lastUpdate = ZonedDateTime.now();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ReviewComment addReviewComment(Hash base, Hash hash, String path, int line, String body) {
        var comment = new ReviewComment(null, String.valueOf(data.reviewComments.size()), hash, path, line, String.valueOf(data.reviewComments.size()), body, user, ZonedDateTime.now(), ZonedDateTime.now());
        data.reviewComments.add(comment);
        data.lastUpdate = ZonedDateTime.now();
        return comment;
    }

    @Override
    public ReviewComment addReviewCommentReply(ReviewComment parent, String body) {
        if (parent.parent().isPresent()) {
            throw new RuntimeException("Can only reply to top-level review comments");
        }
        var comment = new ReviewComment(parent, parent.threadId(), parent.hash(), parent.path(), parent.line(), String.valueOf(data.reviewComments.size()), body, user, ZonedDateTime.now(), ZonedDateTime.now());
        data.reviewComments.add(comment);
        data.lastUpdate = ZonedDateTime.now();
        return comment;
    }

    @Override
    public List<ReviewComment> getReviewComments() {
        return new ArrayList<>(data.reviewComments);
    }

    @Override
    public Hash getHeadHash() {
        return data.headHash;
    }

    @Override
    public String getSourceRef() {
        return sourceRef;
    }

    @Override
    public String getTargetRef() {
        return targetRef;
    }

    @Override
    public Hash getTargetHash() {
        return repository.getBranchHash(targetRef);
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getBody() {
        return data.body;
    }

    @Override
    public void setBody(String body) {
        data.body = body;
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public List<Comment> getComments() {
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
    public ZonedDateTime getCreated() {
        return data.created;
    }

    @Override
    public ZonedDateTime getUpdated() {
        return data.lastUpdate;
    }

    @Override
    public Map<String, Check> getChecks(Hash hash) {
        return data.checks.stream()
                .filter(check -> check.hash().equals(hash))
                .collect(Collectors.toMap(Check::name, Function.identity()));
    }

    @Override
    public void createCheck(Check check) {
        var existing = data.checks.stream()
                                  .filter(c -> check.name().equals(check.name()))
                                  .findAny();
        existing.ifPresent(data.checks::remove);
        data.checks.add(check);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public void updateCheck(Check updated) {
        var existing = data.checks.stream()
                .filter(check -> check.name().equals(updated.name()))
                .findAny()
                .orElseThrow();

        data.checks.remove(existing);
        data.checks.add(updated);
        data.lastUpdate = ZonedDateTime.now();
    }

    @Override
    public void setState(State state) {
        data.state = state;
        data.lastUpdate = ZonedDateTime.now();
    }

    boolean isOpen() {
        return data.state.equals(PullRequest.State.OPEN);
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
    public List<String> getLabels() {
        return new ArrayList<>(data.labels);
    }

    @Override
    public URI getWebUrl() {
        try {
            return new URI(repository.getUrl().toString() + "/pr/" + getId());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<HostUserDetails> getAssignees() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void setAssignees(List<HostUserDetails> assignees) {
        throw new RuntimeException("not implemented yet");
    }
}
