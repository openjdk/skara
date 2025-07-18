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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.Diff;
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.net.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TestPullRequest is the object returned from a TestHost when queried for pull
 * requests. It's backed by a TestPullRequestStore, which tracks the "server
 * side" state of the pull request. A TestPullRequest instance contains a
 * snapshot of the server side state for all data directly related to the pull
 * request. What data is snapshotted and which is fetched on request should be
 * the same as for GitHubPullRequest and GitLabMergeRequest.
 */
public class TestPullRequest extends TestIssue implements PullRequest {

    protected final TestHostedRepository targetRepository;
    protected final Hash headHash;
    protected final String sourceRef;
    protected final String targetRef;
    protected final boolean draft;
    private List<Label> labels;

    public TestPullRequest(TestPullRequestStore store, TestHostedRepository targetRepository) {
        super(store, targetRepository.forge().currentUser());
        this.targetRepository = targetRepository;
        this.headHash = store().headHash();
        this.sourceRef = store().sourceRef();
        this.targetRef = store().targetRef();
        this.draft = store().draft();
        // store().headHash() may have updated lastUpdate
        setLastUpdate(store().lastUpdate());
    }

    /**
     * Gives test code direct access to the backing store object to be able to
     * inspect and manipulate state directly.
     */
    public TestPullRequestStore store() {
        return (TestPullRequestStore) super.store();
    }

    @Override
    public HostedRepository repository() {
        return targetRepository;
    }

    @Override
    public List<Review> reviews() {
        return List.copyOf(PullRequest.calculateReviewTargetRefs(store().reviews(), targetRefChanges()));
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
        try {
            var review = new Review(ZonedDateTime.now(), user,
                                    verdict, targetRepository.localRepository().resolve(store().sourceRef()).orElseThrow(),
                                    String.valueOf(store().reviews().size()),
                                    body, targetRef);

            store().reviews().add(review);
            store().setLastUpdate(ZonedDateTime.now());

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void updateReview(String id, String body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public ReviewComment addReviewComment(Hash base, Hash hash, String path, int line, String body) {
        var id = String.valueOf(store().reviewComments().size());
        var comment = new ReviewComment(null, id,
                hash, path, line, id, body, user, ZonedDateTime.now(), ZonedDateTime.now());
        store().reviewComments().add(comment);
        store().setLastUpdate(ZonedDateTime.now());
        return comment;
    }

    @Override
    public ReviewComment addReviewCommentReply(ReviewComment parent, String body) {
        if (parent.parent().isPresent()) {
            throw new RuntimeException("Can only reply to top-level review comments");
        }
        var comment = new ReviewComment(parent, parent.threadId(), parent.hash().orElseThrow(), parent.path(),
                parent.line(), String.valueOf(store().reviewComments().size()), body, user,
                ZonedDateTime.now(), ZonedDateTime.now());
        store().reviewComments().add(comment);
        store().setLastUpdate(ZonedDateTime.now());
        return comment;
    }

    @Override
    public List<ReviewComment> reviewComments() {
        return new ArrayList<>(store().reviewComments());
    }

    @Override
    public Hash headHash() {
        return headHash;
    }

    @Override
    public String fetchRef() {
        return sourceRef;
    }

    @Override
    public String sourceRef() {
        return sourceRef;
    }

    @Override
    public Optional<HostedRepository> sourceRepository() {
        return Optional.of(store().sourceRepository());
    }

    @Override
    public String targetRef() {
        return targetRef;
    }

    @Override
    public List<ReferenceChange> targetRefChanges() {
        return store().targetRefChanges();
    }

    @Override
    public Map<String, Check> checks(Hash hash) {
        return store().checks().stream()
                .filter(check -> check.hash().equals(hash))
                .collect(Collectors.toMap(Check::name, Function.identity()));
    }

    @Override
    public void createCheck(Check check) {
        var checks = store().checks();
        var existing = checks.stream()
                                  .filter(c -> c.name().equals(check.name()))
                                  .findAny();
        existing.ifPresent(checks::remove);
        checks.add(check);
        store().setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public void updateCheck(Check updated) {
        var checks = store().checks();
        var existing = checks.stream()
                .filter(check -> check.name().equals(updated.name()))
                .findAny()
                .orElseThrow();

        checks.remove(existing);
        checks.add(updated);
        store().setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public URI changeUrl() {
        return URIBuilder.base(webUrl()).appendPath("/files").build();
    }

    @Override
    public URI changeUrl(Hash base) {
        return URIBuilder.base(webUrl()).appendPath("/files/" + base.abbreviate()).build();
    }

    @Override
    public URI commentUrl(Comment comment) {
        return URIBuilder.base(webUrl()).appendPath("/comment/" + comment.id()).build();
    }

    @Override
    public URI reviewCommentUrl(ReviewComment reviewComment) {
        return URIBuilder.base(webUrl()).appendPath("/reviewComment/" + reviewComment.id()).build();
    }

    @Override
    public URI reviewUrl(Review review) {
        return URIBuilder.base(webUrl()).appendPath("/review/" + review.id()).build();
    }

    @Override
    public boolean isDraft() {
        return draft;
    }

    @Override
    public URI webUrl() {
        try {
            return new URI(targetRepository.webUrl().toString() + "/pr/" + id());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void makeNotDraft() {
        store().setDraft(false);
    }

    public void makeDraft() {
        store().setDraft(true);
    }

    @Override
    public Optional<ZonedDateTime> lastMarkedAsDraftTime() {
        return Optional.ofNullable(store().lastMarkedAsDraftTime());
    }

    @Override
    public URI diffUrl() {
        return URI.create(webUrl().toString() + ".diff");
    }

    @Override
    public Optional<ZonedDateTime> labelAddedAt(String label) {
        return Optional.ofNullable(store().labels().get(label));
    }

    @Override
    public void setTargetRef(String targetRef) {
        store().setTargetRef(targetRef);
        store().setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public URI headUrl() {
        return URI.create(webUrl().toString() + "/commits/" + headHash().hex());
    }

    @Override
    public Diff diff() {
        if (store().returnCompleteDiff()) {
            try {
                var targetLocalRepository = targetRepository.localRepository();
                var sourceLocalRepository = store().sourceRepository().localRepository();
                var sourceHash = headHash();
                if (!targetLocalRepository.root().equals(sourceLocalRepository.root())) {
                    // The target and source repo are not same, fetch the source branch
                    var sourceUri = URI.create("file://" + sourceLocalRepository.root().toString());
                    sourceHash = targetLocalRepository.fetch(sourceUri, sourceRef).orElseThrow();
                }
                // Find the base hash of the source and target branches.
                var baseHash = targetLocalRepository.mergeBase(sourceHash, targetRepository.branchHash(targetRef()).orElseThrow());
                return targetLocalRepository.diff(baseHash, sourceHash);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            return new Diff(Hash.zero(), Hash.zero(), List.of(), false);
        }
    }

    @Override
    public URI filesUrl(Hash hash) {
        return URI.create(webUrl().toString() + "/files/" + hash.hex());
    }

    @Override
    public Optional<ZonedDateTime> lastForcePushTime() {
        if (store().lastForcePushTime() != null && store().lastForcePushTime().isAfter(store().lastMarkedAsReadyTime())) {
            return Optional.ofNullable(store().lastForcePushTime());
        }
        return Optional.empty();
    }

    public void setLastForcePushTime(ZonedDateTime lastForcePushTime) {
        store().setLastForcePushTime(lastForcePushTime);
    }

    @Override
    public Optional<Hash> findIntegratedCommitHash() {
        return findIntegratedCommitHash(List.of(repository().forge().currentUser().id()));
    }

    @Override
    public Object snapshot() {
        return List.of(this, comments(), reviews());
    }

    /**
     * Mimic GitHub/GitLab where the labels are fetched lazily and cached.
     * In GitLabMergeRequest, the labels are actually part of the main json, but
     * are still re-fetched once on the first call to labels().
     */
    @Override
    public List<Label> labels() {
        if (labels == null) {
            labels = store().labels().keySet().stream().map(Label::new).collect(Collectors.toList());
        }
        return labels;
    }

    /**
     * Equals for a TestPullRequest means that all the snapshotted data is the same.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        TestPullRequest that = (TestPullRequest) o;
        return draft == that.draft &&
                Objects.equals(headHash, that.headHash) &&
                Objects.equals(sourceRef, that.sourceRef) &&
                Objects.equals(targetRef, that.targetRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), headHash, sourceRef, targetRef, draft);
    }

    public void setReturnCompleteDiff(boolean complete){
        this.store().setReturnCompleteDiff(complete);
    }

    // For TestPullRequest, we control the lastUpdate timestamp, so it won't be spurious
    @Override
    public ZonedDateTime lastTouchedTime() {
        return store().lastTouchedTime();
    }
}
