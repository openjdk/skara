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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

class CommitFailure extends Exception {
    CommitFailure(String reason) {
        super(reason);
    }
}

class PullRequestInstance {
    private final PullRequest pr;
    private final Repository localRepo;
    private final Hash targetHash;
    private final Hash headHash;
    private final Hash baseHash;
    private final boolean ignoreStaleReviews;

    PullRequestInstance(Path localRepoPath, HostedRepositoryPool hostedRepositoryPool, PullRequest pr, boolean ignoreStaleReviews) throws IOException  {
        this.pr = pr;
        this.ignoreStaleReviews = ignoreStaleReviews;

        // Materialize the PR's source and target ref
        var repository = pr.repository();
        localRepo = hostedRepositoryPool.checkout(pr, localRepoPath.resolve(repository.name()));
        localRepo.fetch(repository.url(), "+" + pr.targetRef() + ":pr_prinstance", false);

        targetHash = pr.targetHash();
        headHash = pr.headHash();
        baseHash = localRepo.mergeBase(targetHash, headHash);
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

    private String commitMessage(List<Review> activeReviews, Namespace namespace) throws IOException {
        var reviewers = activeReviews.stream()
                                     .filter(review -> !ignoreStaleReviews || review.hash().equals(headHash))
                                     .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                     .map(review -> review.reviewer().id())
                                     .map(namespace::get)
                                     .filter(Objects::nonNull)
                                     .map(Contributor::username)
                                     .collect(Collectors.toList());

        var comments = pr.comments();
        var additionalContributors = Contributors.contributors(pr.repository().forge().currentUser(),
                                                               comments).stream()
                                                 .map(email -> Author.fromString(email.toString()))
                                                 .collect(Collectors.toList());

        var additionalIssues = SolvesTracker.currentSolved(pr.repository().forge().currentUser(), comments);
        var summary = Summary.summary(pr.repository().forge().currentUser(), comments);
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

    private Hash commitSquashed(Hash finalHead, List<Review> activeReviews, Namespace namespace, String censusDomain, String sponsorId) throws IOException {
        Author committer;
        Author author;
        var contributor = namespace.get(pr.author().id());

        if (contributor == null) {
            // Use the information contained in the head commit - jcheck has verified that it contains sane values
            var headCommit = localRepo.commitMetadata(headHash.hex() + "^.." + headHash.hex()).get(0);
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

        var commitMessage = commitMessage(activeReviews, namespace);
        return localRepo.commit(finalHead, List.of(targetHash), commitMessage, author.name(), author.email(),
                ZonedDateTime.now(), committer.name(), committer.email(), ZonedDateTime.now());
    }

    private Hash commitMerge(Hash finalHead, List<Review> activeReviews, Namespace namespace, String censusDomain, String sponsorId) throws IOException, CommitFailure {
        // Find the first merge commit with an incoming parent outside of the merge target
        // The very last commit is not eligible (as the merge needs a parent)
        var commits = localRepo.commitMetadata(baseHash, finalHead);
        int mergeCommitIndex = commits.size();
        for (int i = 0; i < commits.size() - 1; ++i) {
            if (commits.get(i).isMerge()) {
                boolean isSourceMerge = false;
                for (int j = 0; j < commits.get(i).parents().size(); ++j) {
                    if (!localRepo.isAncestor(baseHash, commits.get(i).parents().get(j))) {
                        isSourceMerge = true;
                    }
                }
                if (isSourceMerge) {
                    mergeCommitIndex = i;
                    break;
                }
            }
        }

        if (mergeCommitIndex == commits.size()) {
            throw new CommitFailure("No merge commit containing incoming commits from another branch than the target was found");
        }

        // Find the parent which is on the target branch - we will replace it with the target hash (if there were no merge conflicts)
        Hash firstParent = null;
        var finalParents = new ArrayList<Hash>();
        for (int i = 0; i < commits.get(mergeCommitIndex).parents().size(); ++i) {
            if (localRepo.isAncestor(baseHash, commits.get(mergeCommitIndex).parents().get(i))) {
                if (firstParent == null) {
                    firstParent = localRepo.mergeBase(targetHash, finalHead);
                    continue;
                }
            }
            finalParents.add(commits.get(mergeCommitIndex).parents().get(i));
        }
        if (firstParent == null) {
            throw new CommitFailure("The merge commit did not have any common ancestor with the target branch");
        }
        finalParents.add(0, firstParent);

        var contributor = namespace.get(pr.author().id());
        if (contributor == null) {
            throw new CommitFailure("Merges can only be performed by Committers");
        }

        var author = new Author(contributor.fullName().orElseThrow(), contributor.username() + "@" + censusDomain);
        Author committer;
        if (sponsorId != null) {
            var sponsorContributor = namespace.get(sponsorId);
            committer = new Author(sponsorContributor.fullName().orElseThrow(), sponsorContributor.username() + "@" + censusDomain);
        } else {
            committer = author;
        }
        var commitMessage = commitMessage(activeReviews, namespace);

        return localRepo.commit(finalHead, finalParents, commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                committer.name(), committer.email(), ZonedDateTime.now());
    }

    private boolean isMergeCommit() {
        return pr.title().startsWith("Merge");
    }

    Hash commit(Hash finalHead, Namespace namespace, String censusDomain, String sponsorId) throws IOException, CommitFailure {
        var activeReviews = filterActiveReviews(pr.reviews());
        Hash commit;
        if (!isMergeCommit()) {
            commit = commitSquashed(finalHead, activeReviews, namespace, censusDomain, sponsorId);
        } else {
            commit = commitMerge(finalHead, activeReviews, namespace, censusDomain, sponsorId);
        }
        localRepo.checkout(commit, true);
        return commit;
    }

    List<CommitMetadata> divergingCommits() {
        return divergingCommits(headHash);
    }

    private List<CommitMetadata> divergingCommits(Hash commitHash) {
        try {
            var updatedBase = localRepo.mergeBase(targetHash, commitHash);
            return localRepo.commitMetadata(updatedBase, targetHash);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Optional<Hash> mergeTarget(PrintWriter reply) {
        var divergingCommits = divergingCommits(headHash);
        if (divergingCommits.size() > 0) {
            reply.print("The following commits have been pushed to ");
            reply.print(pr.targetRef());
            reply.println(" since your change was applied:");
            divergingCommits.forEach(c -> reply.println(" * " + c.hash().hex() + ": " + c.message().get(0)));

            try {
                localRepo.checkout(headHash, true);
                localRepo.merge(targetHash);
                var hash = localRepo.commit("Automatic merge with latest target", "duke", "duke@openjdk.org");
                reply.println();
                reply.println("Your commit was automatically rebased without conflicts.");
                return Optional.of(hash);
            } catch (IOException e) {
                reply.println();
                reply.print("It was not possible to rebase your changes automatically. Please merge `");
                reply.print(pr.targetRef());
                reply.println("` into your branch and try again.");
                try {
                    localRepo.checkout(headHash, true);
                } catch (IOException e2) {
                    throw new UncheckedIOException(e2);
                }
                return Optional.empty();
            }
        } else {
            // No merge needed
            return Optional.of(headHash);
        }
    }

    Repository localRepo() {
        return localRepo;
    }

    Hash baseHash() {
        return baseHash;
    }

    Hash targetHash() {
        return targetHash;
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

    PullRequestCheckIssueVisitor createVisitor(Hash localHash, CensusInstance censusInstance) throws IOException {
        var checks = JCheck.checksFor(localRepo(), censusInstance.census(), targetHash);
        return new PullRequestCheckIssueVisitor(checks);
    }

    void executeChecks(Hash localHash, CensusInstance censusInstance, PullRequestCheckIssueVisitor visitor, List<String> additionalConfiguration) throws Exception {
        try (var issues = JCheck.check(localRepo(), censusInstance.census(), CommitMessageParsers.v1, localHash,
                                       targetHash, additionalConfiguration)) {
            for (var issue : issues) {
                issue.accept(visitor);
            }
        }
    }

    List<Reference> remoteBranches() {
        try {
            return localRepo.remoteBranches(pr.repository().url().toString());
        } catch (IOException e) {
            return List.of();
        }
    }
}
