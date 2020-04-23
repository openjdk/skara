/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.census.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.jcheck.JCheck;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class CheckablePullRequest {
    private final PullRequest pr;
    private final Repository localRepo;
    private final boolean ignoreStaleReviews;

    CheckablePullRequest(PullRequest pr, Repository localRepo, boolean ignoreStaleReviews) {
        this.pr = pr;
        this.localRepo = localRepo;
        this.ignoreStaleReviews = ignoreStaleReviews;
    }

    private String commitMessage(List<Review> activeReviews, Namespace namespace) throws IOException {
        var reviewers = activeReviews.stream()
                                     .filter(review -> !ignoreStaleReviews || review.hash().equals(pr.headHash()))
                                     .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                     .map(review -> review.reviewer().id())
                                     .map(namespace::get)
                                     .filter(Objects::nonNull)
                                     .map(Contributor::username)
                                     .collect(Collectors.toList());

        var comments = pr.comments();
        var currentUser = pr.repository().forge().currentUser();
        var additionalContributors = Contributors.contributors(currentUser,
                                                               comments).stream()
                                                 .map(email -> Author.fromString(email.toString()))
                                                 .collect(Collectors.toList());

        var additionalIssues = SolvesTracker.currentSolved(currentUser, comments);
        var summary = Summary.summary(currentUser, comments);
        var issue = Issue.fromString(pr.title());
        var commitMessageBuilder = issue.map(CommitMessage::title).orElseGet(() -> CommitMessage.title(pr.title()));
        if (issue.isPresent()) {
            commitMessageBuilder.issues(additionalIssues);
        }
        commitMessageBuilder.contributors(additionalContributors)
                            .reviewers(reviewers);
        summary.ifPresent(commitMessageBuilder::summary);

        return String.join("\n", commitMessageBuilder.format(CommitMessageFormatters.v1));
    }

    /**
     * The Review list is in chronological order, the latest one from a particular reviewer is the
     * one that is "active".
     * @param allReviews
     * @return
     */
    static List<Review> filterActiveReviews(List<Review> allReviews) {
        var reviewPerUser = new LinkedHashMap<HostUser, Review>();
        for (var review : allReviews) {
            reviewPerUser.put(review.reviewer(), review);
        }
        return new ArrayList<>(reviewPerUser.values());
    }

    Hash commit(Hash finalHead, Namespace namespace, String censusDomain, String sponsorId) throws IOException, CommitFailure {
        Author committer;
        Author author;
        var contributor = namespace.get(pr.author().id());

        if (contributor == null) {
            if (PullRequestUtils.isMerge(pr)) {
                throw new CommitFailure("Merges can only be performed by Committers.");
            }

            // Use the information contained in the head commit - jcheck has verified that it contains sane values
            var headCommit = localRepo.commitMetadata(pr.headHash().hex() + "^.." + pr.headHash().hex()).get(0);
            author = headCommit.author();
        } else {
            author = new Author(contributor.fullName().orElseThrow(), contributor.username() + "@" + censusDomain);
        }

        if (sponsorId != null) {
            var sponsorContributor = namespace.get(sponsorId);
            committer = new Author(sponsorContributor.fullName().orElseThrow(), sponsorContributor.username() + "@" + censusDomain);
        } else {
            committer = author;
        }

        var activeReviews = filterActiveReviews(pr.reviews());
        var commitMessage = commitMessage(activeReviews, namespace);
        return PullRequestUtils.createCommit(pr, localRepo, finalHead, author, committer, commitMessage);
    }

    PullRequestCheckIssueVisitor createVisitor(Hash localHash, CensusInstance censusInstance) throws IOException {
        var checks = JCheck.checksFor(localRepo, censusInstance.census(), pr.targetHash());
        return new PullRequestCheckIssueVisitor(checks);
    }

    void executeChecks(Hash localHash, CensusInstance censusInstance, PullRequestCheckIssueVisitor visitor, List<String> additionalConfiguration) throws Exception {
        try (var issues = JCheck.check(localRepo, censusInstance.census(), CommitMessageParsers.v1, localHash,
                                       pr.targetHash(), additionalConfiguration)) {
            for (var issue : issues) {
                issue.accept(visitor);
            }
        }
    }

    List<CommitMetadata> divergingCommits() {
        return divergingCommits(pr.headHash());
    }

    private List<CommitMetadata> divergingCommits(Hash commitHash) {
        try {
            var updatedBase = localRepo.mergeBase(pr.targetHash(), commitHash);
            return localRepo.commitMetadata(updatedBase, pr.targetHash());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Optional<Hash> mergeTarget(PrintWriter reply) {
        var divergingCommits = divergingCommits(pr.headHash());
        if (divergingCommits.size() > 0) {
            reply.print("The following commits have been pushed to ");
            reply.print(pr.targetRef());
            reply.println(" since your change was applied:");
            divergingCommits.forEach(c -> reply.println(" * " + c.hash().hex() + ": " + c.message().get(0)));

            try {
                localRepo.checkout(pr.headHash(), true);
                localRepo.merge(pr.targetHash());
                var hash = localRepo.commit("Automatic merge with latest target", "duke", "duke@openjdk.org");
                reply.println();
                reply.println("Your commit was automatically rebased without conflicts.");
                return Optional.of(hash);
            } catch (IOException e) {
                reply.println();
                reply.print("It was not possible to rebase your changes automatically. Please merge `");
                reply.print(pr.targetRef());
                reply.println("` into your branch and try again.");
                return Optional.empty();
            }
        } else {
            // No merge needed
            return Optional.of(pr.headHash());
        }
    }

}