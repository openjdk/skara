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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.net.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestPullRequest extends TestIssue implements PullRequest {
    private final TestHostedRepository targetRepository;
    private final TestHostedRepository sourceRepository;
    private final String targetRef;
    private final String sourceRef;
    private final PullRequestData data;

    private TestPullRequest(TestHostedRepository targetRepository, TestHostedRepository sourceRepository, String id, HostUser author, HostUser user, String targetRef, String sourceRef, PullRequestData data) {
        super(targetRepository, id, author, user, data);
        this.targetRepository = targetRepository;
        this.sourceRepository = sourceRepository;
        this.targetRef = targetRef;
        this.sourceRef = sourceRef;
        this.data = data;

        try {
            var headHash = sourceRepository.localRepository().resolve(sourceRef);
            if (headHash.isPresent() && !headHash.get().equals(data.headHash)) {
                data.headHash = headHash.get();
                data.lastUpdate = ZonedDateTime.now();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TestPullRequest createNew(TestHostedRepository targetRepository, TestHostedRepository sourceRepository, String id, String targetRef, String sourceRef, String title, List<String> body, boolean draft) {
        var data = new PullRequestData();
        data.title = title;
        data.body = String.join("\n", body);
        data.draft = draft;
        var pr = new TestPullRequest(targetRepository, sourceRepository, id, targetRepository.forge().currentUser(), targetRepository.forge().currentUser(), targetRef, sourceRef, data);
        return pr;
    }

    static TestPullRequest createFrom(TestHostedRepository repository, TestPullRequest other) {
        var pr = new TestPullRequest(repository, other.sourceRepository, other.id, other.author, repository.forge().currentUser(), other.targetRef, other.sourceRef, other.data);
        return pr;
    }

    @Override
    public HostedRepository repository() {
        return targetRepository;
    }

    @Override
    public IssueProject project() {
        return null;
    }

    @Override
    public List<Review> reviews() {
        return new ArrayList<>(data.reviews);
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
        try {
            var review = new Review(ZonedDateTime.now(), targetRepository.forge().currentUser(),
                                    verdict, targetRepository.localRepository().resolve(sourceRef).orElseThrow(),
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
    public List<ReviewComment> reviewComments() {
        return new ArrayList<>(data.reviewComments);
    }

    @Override
    public Hash headHash() {
        return data.headHash;
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
    public HostedRepository sourceRepository() {
        return sourceRepository;
    }

    @Override
    public String targetRef() {
        return targetRef;
    }

    @Override
    public Hash targetHash() {
        return targetRepository.branchHash(targetRef);
    }

    @Override
    public Map<String, Check> checks(Hash hash) {
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
    public URI changeUrl() {
        return URIBuilder.base(webUrl()).appendPath("/files").build();
    }

    @Override
    public URI changeUrl(Hash base) {
        return URIBuilder.base(webUrl()).appendPath("/files/" + base.abbreviate()).build();
    }

    @Override
    public boolean isDraft() {
        return data.draft;
    }

    @Override
    public URI webUrl() {
        try {
            return new URI(targetRepository.url().toString() + "/pr/" + id());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void makeNotDraft() {
        data.draft = false;
    }

    @Override
    public URI diffUrl() {
        return URI.create(webUrl().toString() + ".diff");
    }
}
