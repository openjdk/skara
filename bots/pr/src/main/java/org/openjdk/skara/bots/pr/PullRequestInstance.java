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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.census.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.jcheck.JCheck;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

class PullRequestInstance {
    private final PullRequest pr;
    private final Repository localRepo;
    private final Hash targetHash;
    private final Hash headHash;
    private final Hash baseHash;

    PullRequestInstance(Path localRepoPath, PullRequest pr) throws IOException  {
        this.pr = pr;
        var repository = pr.repository();

        // Materialize the PR's target ref
        localRepo = Repository.materialize(localRepoPath, repository.getUrl(), pr.getTargetRef());
        targetHash = localRepo.fetch(repository.getUrl(), pr.getTargetRef());
        headHash = localRepo.fetch(repository.getUrl(), pr.getHeadHash().hex());
        baseHash = localRepo.mergeBase(targetHash, headHash);
    }

    /**
     * The Review list is in chronological order, the latest one from a particular reviewer is the
     * one that is "active".
     * @param allReviews
     * @return
     */
    static List<Review> filterActiveReviews(List<Review> allReviews) {
        var reviewPerUser = new LinkedHashMap<HostUserDetails, Review>();
        for (var review : allReviews) {
            reviewPerUser.put(review.reviewer(), review);
        }
        return new ArrayList<>(reviewPerUser.values());
    }

    private String commitMessage(List<Review> activeReviews, Namespace namespace, boolean isMerge) throws IOException {
        var reviewers = activeReviews.stream()
                          .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                          .map(review -> review.reviewer().id())
                          .map(namespace::get)
                          .filter(Objects::nonNull)
                          .map(Contributor::username)
                          .collect(Collectors.toList());

        var comments = pr.getComments();
        var additionalContributors = Contributors.contributors(pr.repository().host().getCurrentUserDetails(),
                                                               comments).stream()
                                                 .map(email -> Author.fromString(email.toString()))
                                                 .collect(Collectors.toList());

        var summary = Summary.summary(pr.repository().host().getCurrentUserDetails(), comments);
        var issue = Issue.fromString(pr.getTitle());
        var commitMessageBuilder = issue.map(CommitMessage::title).orElseGet(() -> CommitMessage.title(isMerge ? "Merge" : pr.getTitle()));
        commitMessageBuilder.contributors(additionalContributors)
                                         .reviewers(reviewers);
        summary.ifPresent(commitMessageBuilder::summary);

        return String.join("\n", commitMessageBuilder.format(CommitMessageFormatters.v1));
    }

    private Hash commitSquashed(List<Review> activeReviews, Namespace namespace, String censusDomain, String sponsorId) throws IOException {
        localRepo.checkout(baseHash, true);
        localRepo.squash(headHash);

        Author committer;
        Author author;
        var contributor = namespace.get(pr.getAuthor().id());

        if (contributor == null) {
            // Use the information contained in the head commit - jcheck has verified that it contains sane values
            var headCommit = localRepo.commits(headHash.hex() + "^.." + headHash.hex()).asList().get(0);
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

        var commitMessage = commitMessage(activeReviews, namespace, false);
        return localRepo.commit(commitMessage, author.name(), author.email(), committer.name(), committer.email());
    }

    private Hash commitMerge(List<Review> activeReviews, Namespace namespace, String censusDomain) throws IOException {
        localRepo.checkout(headHash, true);

        var contributor = namespace.get(pr.getAuthor().id());
        if (contributor == null) {
            throw new RuntimeException("Merges can only be performed by Committers");
        }

        var author = new Author(contributor.fullName().orElseThrow(), contributor.username() + "@" + censusDomain);

        var commitMessage = commitMessage(activeReviews, namespace, true);
        return localRepo.amend(commitMessage, author.name(), author.email(), author.name(), author.email());
    }

    Hash commit(Namespace namespace, String censusDomain, String sponsorId) throws IOException {
        var activeReviews = filterActiveReviews(pr.getReviews());
        if (!pr.getTitle().startsWith("Merge")) {
            return commitSquashed(activeReviews, namespace, censusDomain, sponsorId);
        } else {
            return commitMerge(activeReviews, namespace, censusDomain);
        }
    }

    List<Commit> divergingCommits() {
        try {
            return localRepo.commits(baseHash + ".." + targetHash).asList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Optional<Hash> rebase(Hash commitHash, PrintWriter reply) {
        var divergingCommits = divergingCommits();
        if (divergingCommits.size() > 0) {
            reply.print("The following commits have been pushed to ");
            reply.print(pr.getTargetRef());
            reply.println(" since your change was applied:");
            divergingCommits.forEach(c -> reply.println(" * " + c.hash().hex() + ": " + c.message().get(0)));

            try {
                var commit = localRepo.lookup(commitHash).orElseThrow();
                localRepo.rebase(targetHash, commit.committer().name(), commit.committer().email());
                reply.println();
                reply.println("Your commit was automatically rebased without conflicts.");
                var hash = localRepo.head();
                return Optional.of(hash);
            } catch (IOException e) {
                reply.println();
                reply.print("It was not possible to rebase your changes automatically. ");
                reply.println("Please rebase your branch manually and try again.");
                try {
                    localRepo.checkout(commitHash, true);
                } catch (IOException e2) {
                    throw new UncheckedIOException(e2);
                }
                return Optional.empty();
            }
        } else {
            // No rebase needed
            return Optional.of(commitHash);
        }
    }

    Repository localRepo() {
        return this.localRepo;
    }

    Hash baseHash() {
        return this.baseHash;
    }

    Set<Path> changedFiles() throws IOException {
        var ret = new HashSet<Path>();
        var changes = localRepo.diff(baseHash, headHash);
        for (var patch : changes.patches()) {
            patch.target().path().ifPresent(ret::add);
            patch.source().path().ifPresent(ret::add);
        }
        return ret;
    }

    PullRequestCheckIssueVisitor executeChecks(Hash localHash, CensusInstance censusInstance) throws Exception {
        var checks = JCheck.checks(localRepo(), censusInstance.census(), localHash);
        var visitor = new PullRequestCheckIssueVisitor(checks);
        try (var issues = JCheck.check(localRepo(), censusInstance.census(), CommitMessageParsers.v1, "HEAD~1..HEAD",
                                       localHash, new HashMap<>(), new HashSet<>())) {
            for (var issue : issues) {
                issue.accept(visitor);
            }
        }

        return visitor;
    }
}
