/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CheckablePullRequest {
    private static final Pattern BACKPORT_PATTERN = Pattern.compile("<!-- backport ([0-9a-z]{40}) -->");

    private final PullRequest pr;
    private final Repository localRepo;
    private final boolean ignoreStaleReviews;
    private final List<String> confOverride;

    CheckablePullRequest(PullRequest pr, Repository localRepo, boolean ignoreStaleReviews,
            HostedRepository jcheckRepo, String jcheckName, String jcheckRef) {
        this.pr = pr;
        this.localRepo = localRepo;
        this.ignoreStaleReviews = ignoreStaleReviews;

        if (jcheckRepo != null) {
            confOverride = jcheckRepo.fileContents(jcheckName, jcheckRef).orElseThrow(
                    () -> new RuntimeException("Could not find " + jcheckName + " on ref " + jcheckRef + " in repo " + jcheckRepo.name())
            ).lines().collect(Collectors.toList());
        } else {
            confOverride = null;
        }
    }

    private String commitMessage(Hash head, List<Review> activeReviews, Namespace namespace, boolean manualReviewers, Hash original) throws IOException {
        var eligibleReviews = activeReviews.stream()
                                           // Reviews without a hash are never valid as they referred to no longer
                                           // existing commits.
                                           .filter(review -> review.hash().isPresent())
                                           .filter(review -> review.targetRef().equals(pr.targetRef()))
                                           .filter(review -> !ignoreStaleReviews || review.hash().orElseThrow().equals(pr.headHash()))
                                           .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                           .collect(Collectors.toList());
        var reviewers = reviewerNames(eligibleReviews, namespace);
        var comments = pr.comments();
        var currentUser = pr.repository().forge().currentUser();

        if (manualReviewers) {
            var allReviewers = reviewerNames(activeReviews, namespace);
            var additionalReviewers = Reviewers.reviewers(currentUser, comments);
            for (var additionalReviewer : additionalReviewers) {
                if (!allReviewers.contains(additionalReviewer)) {
                    reviewers.add(additionalReviewer);
                }
            }
        }

        var additionalContributors = Contributors.contributors(currentUser,
                                                               comments).stream()
                                                 .map(email -> Author.fromString(email.toString()))
                                                 .collect(Collectors.toList());

        var additionalIssues = SolvesTracker.currentSolved(currentUser, comments);
        var summary = Summary.summary(currentUser, comments);
        CommitMessageBuilder commitMessageBuilder;
        if (PullRequestUtils.isMerge(pr)) {
            var conf = JCheckConfiguration.from(localRepo, head);
            var title = pr.title();
            if (conf.isPresent() && !conf.get().checks().enabled(List.of(new MergeMessageCheck())).isEmpty()) {
                var mergeConf = conf.get().checks().merge();
                var pattern = Pattern.compile(mergeConf.message());
                while (true)  {
                    var matcher = pattern.matcher(title);
                    if (matcher.matches()) {
                        break;
                    } else {
                        if (title.length() > 1) {
                            title = title.substring(0, title.length() - 1);
                        } else {
                            throw new RuntimeException("Unable to make merge PR title '" + pr.title() + "' conform to '" + mergeConf.message() + "'");
                        }
                    }
                }
            }
            commitMessageBuilder = CommitMessage.title(title);
        } else {
            var issue = Issue.fromStringRelaxed(pr.title());
            commitMessageBuilder = issue.map(CommitMessage::title).orElseGet(() -> CommitMessage.title(pr.title()));
            if (issue.isPresent()) {
                commitMessageBuilder.issues(additionalIssues);
            }
            if (original != null) {
                commitMessageBuilder.original(original);
            }
        }
        commitMessageBuilder.contributors(additionalContributors)
                            .reviewers(new ArrayList<>(reviewers));
        summary.ifPresent(commitMessageBuilder::summary);

        return String.join("\n", commitMessageBuilder.format(CommitMessageFormatters.v1));
    }

    /**
     * The latest one from a particular reviewer is the one that is "active".
     * Always prefer reviews with the same targetRef as the pull request
     * currently has.
     */
    static List<Review> filterActiveReviews(List<Review> allReviews, String targetRef) {
        var reviewPerUser = new LinkedHashMap<HostUser, Review>();
        for (var review : allReviews) {
            if (reviewPerUser.containsKey(review.reviewer())) {
                var prevReview = reviewPerUser.get(review.reviewer());
                var prevReviewCorrectTarget = prevReview.targetRef().equals(targetRef);
                var reviewCorrectTarget = review.targetRef().equals(targetRef);
                var reviewNewer = prevReview.createdAt().isBefore(review.createdAt());

                if ((!review.verdict().equals(Review.Verdict.NONE))
                        && ((prevReviewCorrectTarget && reviewCorrectTarget && reviewNewer)
                        || (!prevReviewCorrectTarget && !reviewCorrectTarget && reviewNewer)
                        || (!prevReviewCorrectTarget && reviewCorrectTarget))) {
                    reviewPerUser.put(review.reviewer(), review);
                }
            } else {
                reviewPerUser.put(review.reviewer(), review);
            }
        }
        return List.copyOf(reviewPerUser.values());
    }

    Hash commit(Hash finalHead, Namespace namespace, String censusDomain, String sponsorId, Hash original) throws IOException, CommitFailure {
        Author committer;
        Author author;
        var contributor = namespace.get(pr.author().id());

        if (contributor == null) {
            if (PullRequestUtils.isMerge(pr)) {
                throw new CommitFailure("Merge PRs can only be created by known OpenJDK authors.");
            }

            var head = localRepo.lookup(pr.headHash()).orElseThrow();
            author = head.author();
        } else {
            author = new Author(contributor.fullName().orElseThrow(), contributor.username() + "@" + censusDomain);
        }

        if (sponsorId != null) {
            var sponsorContributor = namespace.get(sponsorId);
            committer = new Author(sponsorContributor.fullName().orElseThrow(), sponsorContributor.username() + "@" + censusDomain);
        } else {
            committer = author;
        }

        var activeReviews = filterActiveReviews(pr.reviews(), pr.targetRef());
        var commitMessage = commitMessage(finalHead, activeReviews, namespace, false, original);
        return PullRequestUtils.createCommit(pr, localRepo, finalHead, author, committer, commitMessage);
    }

    Hash amendManualReviewers(Hash commit, Namespace namespace, Hash original) throws IOException {
        var activeReviews = filterActiveReviews(pr.reviews(), pr.targetRef());
        var originalCommitMessage = commitMessage(commit, activeReviews, namespace, false, original);
        var amendedCommitMessage = commitMessage(commit, activeReviews, namespace, true, original);

        if (originalCommitMessage.equals(amendedCommitMessage)) {
            return commit;
        } else {
            return localRepo.amend(amendedCommitMessage);
        }
    }

    PullRequestCheckIssueVisitor createVisitor() throws IOException {
        var checks = JCheck.checksFor(localRepo, targetHash());
        return new PullRequestCheckIssueVisitor(checks);
    }

    void executeChecks(Hash localHash, CensusInstance censusInstance, PullRequestCheckIssueVisitor visitor, List<String> additionalConfiguration) throws IOException {
        Optional<JCheckConfiguration> conf;
        if (confOverride != null) {
            conf = JCheck.parseConfiguration(confOverride, additionalConfiguration);
        } else {
            conf = JCheck.parseConfiguration(localRepo, targetHash(), additionalConfiguration);
        }
        if (conf.isEmpty()) {
            throw new RuntimeException("Failed to parse jcheck configuration at: " + targetHash() + " with extra: " + additionalConfiguration);
        }
        visitor.setConfiguration(conf.get());
        try (var issues = JCheck.check(localRepo, censusInstance.census(), CommitMessageParsers.v1, localHash,
                                       conf.get())) {
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
            var updatedBase = localRepo.mergeBase(targetHash(), commitHash);
            return localRepo.commitMetadata(updatedBase, targetHash());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Optional<Hash> mergeTarget(PrintWriter reply) {
        var divergingCommits = divergingCommits(pr.headHash());
        if (divergingCommits.size() > 0) {
            reply.print("Since your change was applied there ");
            if (divergingCommits.size() == 1) {
                reply.print("has been 1 commit ");
            } else {
                reply.print("have been ");
                reply.print(divergingCommits.size());
                reply.print(" commits ");
            }
            reply.print("pushed to the `");
            reply.print(pr.targetRef());
            reply.print("` branch:\n\n");
            divergingCommits.stream()
                            .limit(10)
                            .forEach(c -> reply.println(" * " + c.hash().hex() + ": " + c.message().get(0)));
            if (divergingCommits.size() > 10) {
                try {
                    var baseHash = localRepo.mergeBase(targetHash(), pr.headHash());
                    reply.println(" * ... and " + (divergingCommits.size() - 10) + " more: " +
                                          pr.repository().webUrl(baseHash.hex(), pr.targetRef()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            reply.println();

            try {
                localRepo.checkout(pr.headHash(), true);
                Hash hash;
                try {
                    localRepo.merge(targetHash());
                    hash = localRepo.commit("Automatic merge with latest target", "duke", "duke@openjdk.org");
                } catch (IOException e) {
                    localRepo.abortMerge();
                    localRepo.rebase(targetHash(), "duke", "duke@openjdk.org");
                    hash = localRepo.head();
                }
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

    Hash findOriginalBackportHash() {
        return findOriginalBackportHash(pr);
    }

    static Hash findOriginalBackportHash(PullRequest pr) {
        var botUser = pr.repository().forge().currentUser();
        return pr.comments()
                .stream()
                .filter(c -> c.author().equals(botUser))
                .flatMap(c -> Stream.of(c.body().split("\n")))
                .map(BACKPORT_PATTERN::matcher)
                .filter(Matcher::find)
                .reduce((first, second) -> second)
                .map(l -> new Hash(l.group(1)))
                .orElse(null);
    }

    // Lazily initiated
    private Hash targetHash;

    public Hash targetHash() throws IOException {
        if (targetHash == null) {
            targetHash = PullRequestUtils.targetHash(localRepo);
        }
        return targetHash;
    }

    public static Set<String> reviewerNames(List<Review> reviews, Namespace namespace) {
        return reviews.stream()
                .map(review -> namespace.get(review.reviewer().id()))
                .filter(Objects::nonNull)
                .map(Contributor::username)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
