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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bots.common.SolvesTracker;
import org.openjdk.skara.census.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
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
    public static final int COMMIT_LIST_LIMIT = 3;

    private static final Pattern BACKPORT_PATTERN = Pattern.compile("<!-- backport ([0-9a-z]{40}) -->");
    private static final Pattern BACKPORT_REPO_PATTERN = Pattern.compile("<!-- repo (.+) -->");

    private final PullRequest pr;
    private final Repository localRepo;
    private final boolean useStaleReviews;
    private final List<String> confOverride;
    private final List<Comment> comments;
    private final MergePullRequestReviewConfiguration reviewMerge;
    private final ReviewCoverage reviewCoverage;

    CheckablePullRequest(PullRequest pr, Repository localRepo, boolean useStaleReviews,
            HostedRepository jcheckRepo, String jcheckName, String jcheckRef, List<Comment> comments, MergePullRequestReviewConfiguration reviewMerge, ReviewCoverage reviewCoverage) {
        this.pr = pr;
        this.localRepo = localRepo;
        this.useStaleReviews = useStaleReviews;
        this.comments = comments;
        this.reviewMerge = reviewMerge;
        this.reviewCoverage = reviewCoverage;

        if (jcheckRepo != null) {
            confOverride = jcheckRepo.fileContents(jcheckName, jcheckRef).orElseThrow(
                    () -> new RuntimeException("Could not find " + jcheckName + " on ref " + jcheckRef + " in repo " + jcheckRepo.name())
            ).lines().collect(Collectors.toList());
        } else {
            confOverride = null;
        }
    }

    private String commitMessage(Hash head, List<Review> activeReviews, Namespace namespace, boolean manualReviewersAndStaleReviewers, Hash original) throws IOException {
        var eligibleReviews = activeReviews.stream()
                                           .filter(reviewCoverage::covers)
                                           .collect(Collectors.toList());
        var reviewers = reviewerNames(eligibleReviews, namespace);
        var currentUser = pr.repository().forge().currentUser();

        if (manualReviewersAndStaleReviewers) {
            reviewers.addAll(Reviewers.reviewers(currentUser, comments));
            if (!useStaleReviews) {
                var staleReviews = new ArrayList<Review>();
                for (var review : activeReviews) {
                    if (review.verdict() == Review.Verdict.APPROVED && !eligibleReviews.contains(review)) {
                        staleReviews.add(review);
                    }
                }
                reviewers.addAll(reviewerNames(staleReviews, namespace));
            }
        }

        var additionalContributors = Contributors.contributors(currentUser,
                                                               comments).stream()
                                                 .map(email -> Author.fromString(email.toString()))
                                                 .collect(Collectors.toList());
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
                var additionalIssues = SolvesTracker.currentSolved(currentUser, comments, pr.title());
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

            if (!pr.author().fullName().isBlank() && pr.author().email().isPresent()) {
                author = new Author(pr.author().fullName(), pr.author().email().get());
            } else {
                var head = localRepo.lookup(pr.headHash()).orElseThrow();
                author = head.author();
            }
        } else {
            author = new Author(contributor.fullName().orElseThrow(), contributor.username() + "@" + censusDomain);
        }

        if (sponsorId != null) {
            var sponsorContributor = namespace.get(sponsorId);
            committer = new Author(sponsorContributor.fullName().orElseThrow(), sponsorContributor.username() + "@" + censusDomain);
        } else {
            committer = author;
        }

        var overridingAuthor = OverridingAuthor.author(pr.repository().forge().currentUser(), comments);
        if (overridingAuthor.isPresent()) {
            author = new Author(overridingAuthor.get().fullName().orElse(""), overridingAuthor.get().address());
        }

        var activeReviews = filterActiveReviews(pr.reviews(), pr.targetRef());
        var commitMessage = commitMessage(finalHead, activeReviews, namespace, false, original);
        return PullRequestUtils.createCommit(pr, localRepo, finalHead, author, committer, commitMessage);
    }

    Hash amendManualReviewersAndStaleReviewers(Hash hash, Namespace namespace, Hash original) throws IOException {
        var activeReviews = filterActiveReviews(pr.reviews(), pr.targetRef());
        var originalCommitMessage = commitMessage(hash, activeReviews, namespace, false, original);
        var amendedCommitMessage = commitMessage(hash, activeReviews, namespace, true, original);

        if (originalCommitMessage.equals(amendedCommitMessage)) {
            return hash;
        } else {
            var commit = localRepo.lookup(hash).orElseThrow();
            return localRepo.amend(amendedCommitMessage, commit.author().name(), commit.author().email(), commit.committer().name(), commit.committer().email());
        }
    }

    PullRequestCheckIssueVisitor createVisitor(JCheckConfiguration conf) throws IOException {
        var checks = JCheck.checksFor(localRepo, conf);
        return new PullRequestCheckIssueVisitor(checks);
    }

    JCheckConfiguration parseJCheckConfiguration(Hash hash) throws IOException {
        var original = confOverride == null ?
            JCheck.parseConfiguration(localRepo, hash, List.of()) :
            JCheck.parseConfiguration(confOverride, List.of());

        if (original.isEmpty()) {
            throw new IllegalStateException("Cannot parse JCheck configuration for commit with hash " + hash.hex());
        }

        var botUser = pr.repository().forge().currentUser();
        var additional = AdditionalConfiguration.get(original.get(), botUser, comments, reviewMerge);
        if (additional.isEmpty()) {
            return original.get();
        }
        var result = confOverride == null ?
            JCheck.parseConfiguration(localRepo, hash, additional) :
            JCheck.parseConfiguration(confOverride, additional);
        return result.orElseThrow(
                    () -> new IllegalStateException("Cannot parse JCheck configuration with additional configuration for commit with hash " + hash.hex()));
    }

    List<org.openjdk.skara.jcheck.Issue> executeChecks(Hash hash, CensusInstance censusInstance, PullRequestCheckIssueVisitor visitor, JCheckConfiguration conf) throws IOException {
        visitor.setConfiguration(conf);
        try (var issues = JCheck.check(localRepo, censusInstance.census(), CommitMessageParsers.v1, hash, conf)) {
            var list = issues.asList();
            for (var issue : list) {
                issue.accept(visitor);
            }
            return list;
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
                            .limit(COMMIT_LIST_LIMIT)
                            .forEach(c -> reply.println(" * " + c.hash().hex() + ": " + c.message().get(0)));
            if (divergingCommits.size() > COMMIT_LIST_LIMIT) {
                try {
                    var baseHash = localRepo.mergeBase(targetHash(), pr.headHash());
                    reply.println(" * ... and " + (divergingCommits.size() - COMMIT_LIST_LIMIT) + " more: " +
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
        return findOriginalBackportHash(pr, comments);
    }

    String findOriginalBackportRepo() {
        return findOriginalBackportRepo(pr, comments);
    }

    static Hash findOriginalBackportHash(PullRequest pr, List<Comment> comments) {
        var botUser = pr.repository().forge().currentUser();
        return comments
                .stream()
                .filter(c -> c.author().equals(botUser))
                .flatMap(c -> Stream.of(c.body().split("\n")))
                .map(BACKPORT_PATTERN::matcher)
                .filter(Matcher::find)
                .reduce((first, second) -> second)
                .map(l -> new Hash(l.group(1)))
                .orElse(null);
    }

    static String findOriginalBackportRepo(PullRequest pr, List<Comment> comments) {
        var botUser = pr.repository().forge().currentUser();
        return comments
                .stream()
                .filter(c -> c.author().equals(botUser))
                .flatMap(c -> Stream.of(c.body().split("\n")))
                .map(BACKPORT_REPO_PATTERN::matcher)
                .filter(Matcher::find)
                .reduce((first, second) -> second)
                .map(l -> l.group(1))
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
