/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.*;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.openjdk.skara.bots.common.PatternEnum.COMMENT_PATTERN;

class ArchiveMessages {

    private static final String WEBREV_UNAVAILABLE_COMMENT = "Webrev is not available because diff is too large";
    private static String filterCommentsAndCommands(String body) {
        var parsedBody = PullRequestBody.parse(body);
        body = parsedBody.bodyText();

        var commentMatcher = COMMENT_PATTERN.getPattern().matcher(body);
        body = commentMatcher.replaceAll("");

        body = ArchiveWorkItem.filterOutCommands(body);

        body = MarkdownToText.removeFormatting(body);
        return body.strip();
    }

    private static String formatCommitBrief(CommitMetadata commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        var abbrev = commit.hash().abbreviate();
        if (message.size() == 0) {
            ret.append(" - ").append(abbrev).append(": <no commit message found>");
        } else {
            ret.append(" - ").append(message.get(0));
        }
        return ret.toString();
    }

    private static String formatSingleCommit(CommitMetadata commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        if (message.size() == 0) {
            var abbrev = commit.hash().abbreviate();
            ret.append("  ").append(abbrev).append(": <no commit message found>");
        } else {
            ret.append("  ").append(String.join("\n  ", message));
        }
        return ret.toString();
    }

    private static String formatCommitInList(CommitMetadata commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        if (message.size() == 0) {
            var abbrev = commit.hash().abbreviate();
            ret.append(" - ").append(abbrev).append(": <no commit message found>");
        } else {
            ret.append(" - ").append(String.join("\n   ", message));
        }
        return ret.toString();
    }

    private static List<CommitMetadata> commits(Repository localRepo, Hash first, Hash last) {
        try {
            return localRepo.commitMetadata(first, last);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static URI commitsLink(PullRequest pr, Hash first, Hash last) {
        return pr.repository().webUrl(first.abbreviate(), last.abbreviate());
    }

    private static String formatNumber(int number) {
        switch (number) {
            case 0: return "no";
            case 1: return "one";
            case 2: return "two";
            case 3: return "three";
            case 4: return "four";
            case 5: return "five";
            case 6: return "six";
            case 7: return "seven";
            case 8: return "eight";
            case 9: return "nine";
            default: return Integer.toString(number);
        }
    }

    private static String describeCommits(List<CommitMetadata> commits, String adjective) {
        return formatNumber(commits.size()) + (adjective.isBlank() ? "" : " " + adjective) +
                " commit" + (commits.size() != 1 ? "s" : "");
    }

    private static Optional<String> formatCommitMessagesFull(List<CommitMetadata> commits, URI commitsLink) {
        if (commits.size() == 0) {
            return Optional.empty();
        } else if (commits.size() == 1) {
            return Optional.of(formatSingleCommit(commits.get(0)));
        } else {
            var commitSummary = commits.stream()
                                      .limit(10)
                                      .map(ArchiveMessages::formatCommitInList)
                                      .collect(Collectors.joining("\n"));
            if (commits.size() > 10) {
                commitSummary += "\n - ... and " + (commits.size() - 10) + " more: ";
                commitSummary += commitsLink.toString();
            }
            return Optional.of(commitSummary);
        }
    }

    private static Optional<String> formatCommitMessagesBrief(List<CommitMetadata> commits, URI commitsLink) {
        if (commits.size() == 0) {
            return Optional.empty();
        } else {
            var commitSummary = commits.stream()
                                       .limit(10)
                                       .map(ArchiveMessages::formatCommitBrief)
                                       .collect(Collectors.joining("\n"));
            if (commits.size() > 10) {
                commitSummary += "\n - ... and " + (commits.size() - 10) + " more: ";
                commitSummary += commitsLink.toString();
            }
            return Optional.of(commitSummary);
        }
    }

    private static Optional<String> issueUrl(PullRequest pr, URI issueTracker, String projectPrefix) {
        var issue = Issue.fromStringRelaxed(pr.title());
        return issue.map(value -> URIBuilder.base(issueTracker).appendPath(projectPrefix + "-" + value.shortId()).build().toString());
    }

    private static String stats(Repository localRepo, Hash base, Hash head) {
        try {
            var diff = localRepo.diff(base, head);
            var diffStats = diff.totalStats();
            var inserted = diffStats.added();
            var deleted = diffStats.removed();
            var modified = diffStats.modified();
            var linesChanged = inserted + deleted + modified;
            var filesChanged = diff.patches().size();
            return String.format("%d line%s in %d file%s changed: %d ins; %d del; %d mod",
                                 linesChanged,
                                 linesChanged == 1 ? "" : "s",
                                 filesChanged,
                                 filesChanged == 1 ? "" : "s",
                                 inserted,
                                 deleted,
                                 modified);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fetchCommand(PullRequest pr) {
        var repoUrl = pr.repository().url();
        return "git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id();
    }

    static String composeConversation(PullRequest pr) {
        var filteredBody = filterCommentsAndCommands(pr.body());
        if (filteredBody.isEmpty()) {
            filteredBody = pr.title();
        }

        return filteredBody;
    }

    static String composeIncrementalRevision(PullRequest pr, Repository localRepository, String author, Hash head, Hash lastHead, Hash base) {
        var ret = new StringBuilder();

        var incrementalUpdate = false;
        try {
            incrementalUpdate = localRepository.isAncestor(lastHead, head);
        } catch (IOException ignored) {
        }
        var commits = commits(localRepository, lastHead, head);
        var noIncrementalCommitsFound = commits.isEmpty();
        if (noIncrementalCommitsFound) {
            // Could not find incremental commits, get everything from the base instead
            lastHead = base;
            commits = commits(localRepository, lastHead, head);
        }
        var commitsLink = commitsLink(pr, lastHead, head);
        var newCommitMessages = formatCommitMessagesFull(commits, commitsLink);

        if (incrementalUpdate) {
            ret.append(author);
            ret.append(" has updated the pull request incrementally");
            var commitsDescription = describeCommits(commits, "additional");
            newCommitMessages.ifPresentOrElse(m -> ret.append(" with ")
                                                      .append(commitsDescription)
                                                      .append(" since the last revision:\n\n")
                                                      .append(m),
                                              () -> ret.append("."));
        } else {
            ret.append(author);
            ret.append(" has refreshed the contents of this pull request, and previous commits have been removed. ");
            if (noIncrementalCommitsFound) {
                ret.append("Incremental views are not available.");
                var commitsDescription = describeCommits(commits, "");
                newCommitMessages.ifPresent(m -> ret.append(" The pull request now contains ")
                        .append(commitsDescription)
                        .append(":\n\n")
                        .append(m));
            } else {
                ret.append("The incremental views will show differences compared to the previous content of the PR.");
                var commitsDescription = describeCommits(commits, "new");
                newCommitMessages.ifPresent(m -> ret.append(" The pull request contains ")
                        .append(commitsDescription)
                        .append(" since the last revision:\n\n")
                        .append(m));
            }
        }
        return ret.toString();
    }

    static String composeRebasedIncrementalRevision(PullRequest pr, Repository localRepository, String author, Hash head, Hash lastHead) {
        var ret = new StringBuilder();

        ret.append(author);
        ret.append(" has updated the pull request with a new target base due to a merge or a rebase. ");
        ret.append("The incremental webrev excludes the unrelated changes brought in by the merge/rebase.");

        var commits = commits(localRepository, lastHead, head);
        var commitsLink = commitsLink(pr, lastHead, head);
        var newCommitMessages = formatCommitMessagesFull(commits, commitsLink);
        var commitsDescription = describeCommits(commits, "additional");
        newCommitMessages.ifPresent(m -> ret.append(" The pull request contains ")
                                            .append(commitsDescription)
                                            .append(" since the last revision:\n\n")
                                            .append(m));
        return ret.toString();
    }

    static String composeFullRevision(PullRequest pr, Repository localRepository, String author, Hash base, Hash head) {
        var ret = new StringBuilder();

        ret.append(author);
        ret.append(" has updated the pull request with a new target base due to a merge or a rebase.");

        var commits = commits(localRepository, base, head);
        var commitsLink = commitsLink(pr, base, head);
        var newCommitMessages = formatCommitMessagesFull(commits, commitsLink);
        var commitsDescription = describeCommits(commits, "");
        newCommitMessages.ifPresent(m -> ret.append(" The pull request now contains ")
                                            .append(commitsDescription)
                                            .append(":\n\n")
                                            .append(m));
        return ret.toString();
    }

    static String composeReplySubject(String parentSubject) {
        if (parentSubject.startsWith("Re: ")) {
            return parentSubject;
        } else {
            return "Re: " + parentSubject;
        }
    }

    private static Optional<String> composeDependsOn(PullRequest pr) {
        var dependsId = PreIntegrations.dependentPullRequestId(pr);
        if (dependsId.isEmpty()) {
            return Optional.empty();
        }

        var dependsPr = pr.repository().pullRequest(dependsId.get());
        return Optional.of("Depends on: " + dependsPr.webUrl());
    }

    static String composeReplyFooter(PullRequest pr) {
        return "PR: " + pr.webUrl();
    }

    static String composeCommentReplyFooter(PullRequest pr, Comment comment) {
        return "PR Comment: " + pr.commentUrl(comment).toString();
    }

    static String composeReviewCommentReplyFooter(PullRequest pr, ReviewComment reviewComment) {
        return "PR Review Comment: " + pr.reviewCommentUrl(reviewComment).toString();
    }

    static String composeReviewReplyFooter(PullRequest pr, Review review) {
        return "PR Review: " + pr.reviewUrl(review).toString();
    }

    // When changing this, ensure that the PR pattern in the notifier still matches
    static String composeConversationFooter(PullRequest pr, URI issueProject, String projectPrefix, Repository localRepo, WebrevDescription webrev, Hash base, Hash head) {
        var commits = commits(localRepo, base, head);
        var commitsLink = commitsLink(pr, base, head);
        var issueString = issueUrl(pr, issueProject, projectPrefix).map(url -> "  Issue: " + url + "\n").orElse("");

        return composeDependsOn(pr).map(line -> line + "\n\n").orElse("") +
                "Commit messages:\n" +
                formatCommitMessagesBrief(commits, commitsLink).orElse("") + "\n\n" +
                "Changes: " + pr.changeUrl() + "\n" +
                (webrev.diffTooLarge() ?
                        "  Webrev: " + WEBREV_UNAVAILABLE_COMMENT + "\n" :
                        (webrev.uri() == null ? "" : "  Webrev: " + webrev.uri().toString() + "\n")) +
                issueString +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeMergeConversationFooter(PullRequest pr, Repository localRepo, List<WebrevDescription> webrevs, Hash base, Hash head) {
        var commits = commits(localRepo, base, head);
        var commitsLink = commitsLink(pr, base, head);
        String webrevLinks;
        if (webrevs.size() > 0) {
            if (webrevs.stream().noneMatch(w -> w.uri() != null || w.diffTooLarge())) {
                webrevLinks = "";
            } else {
                var containsConflicts = webrevs.stream().anyMatch(w -> w.type().equals(WebrevDescription.Type.MERGE_CONFLICT));
                var containsMergeDiffs = webrevs.stream().anyMatch(w -> w.type().equals(WebrevDescription.Type.MERGE_TARGET) ||
                        w.type().equals(WebrevDescription.Type.MERGE_SOURCE));

                webrevLinks = "The webrev" + (webrevs.size() > 1 ? "s" : "") + " contain" + (webrevs.size() == 1 ? "s" : "") + " " +
                        (containsConflicts ? "the conflicts with " + pr.targetRef() : "") +
                        (containsConflicts && containsMergeDiffs ? " and " : "") +
                        (containsMergeDiffs ? "the adjustments done while merging with regards to each parent branch" : "")
                        + ":\n" +
                        webrevs.stream()
                                .map(d -> d.diffTooLarge() ?
                                        String.format(" - %s: %s", d.shortLabel(), WEBREV_UNAVAILABLE_COMMENT) :
                                        String.format(" - %s: %s", d.shortLabel(), d.uri()))
                                .collect(Collectors.joining("\n")) + "\n\n";
            }
        } else {
            webrevLinks = "The merge commit only contains trivial merges, so no merge-specific webrevs have been generated.\n\n";
        }
        return "Commit messages:\n" +
                formatCommitMessagesBrief(commits, commitsLink).orElse("") + "\n\n" +
                webrevLinks +
                "Changes: " + pr.changeUrl() + "\n" +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeRebasedFooter(PullRequest pr, Repository localRepo, WebrevDescription fullWebrev, Hash base, Hash head) {
        return "Changes: " + pr.changeUrl() + "\n" +
                (fullWebrev.diffTooLarge() ?
                        "  Webrev: " + WEBREV_UNAVAILABLE_COMMENT + "\n" :
                        (fullWebrev.uri() == null ? "" : "  Webrev: " + fullWebrev.uri().toString() + "\n")) +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeIncrementalFooter(PullRequest pr, Repository localRepo, WebrevDescription fullWebrev, WebrevDescription incrementalWebrev, Hash head, Hash lastHead) {
        return "Changes:\n" +
                "  - all: " + pr.changeUrl() + "\n" +
                "  - new: " + pr.changeUrl(lastHead) + "\n\n" +
                (fullWebrev.diffTooLarge() ? "Webrevs:\n" : fullWebrev.uri() == null ? "" : "Webrevs:\n") +
                (fullWebrev.diffTooLarge() ? " - full: " + WEBREV_UNAVAILABLE_COMMENT + "\n" :
                        fullWebrev.uri() == null ? "" : " - full: " + fullWebrev.uri().toString() + "\n") +
                (incrementalWebrev.diffTooLarge() ? " - incr: " + WEBREV_UNAVAILABLE_COMMENT + "\n\n" :
                        incrementalWebrev.uri() == null ? "" : " - incr: " + incrementalWebrev.uri().toString() + "\n\n") +
                "  Stats: " + stats(localRepo, lastHead, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeComment(Comment comment) {
        return filterCommentsAndCommands(comment.body());
    }

    static String composeReviewComment(PullRequest pr, ReviewComment reviewComment) {
        var body = new StringBuilder();

        // Add some context to the first post
        if (reviewComment.parent().isEmpty()) {
            body.append(reviewComment.path());
            if (reviewComment.line() > 0) {
                body.append(" line ").append(reviewComment.line());
            }
            body.append(":\n\n");
            if (reviewComment.hash().isPresent() && reviewComment.line() > 0) {
                try {
                    var contents = pr.repository().fileContents(reviewComment.path(), reviewComment.hash().get().hex())
                            .orElseThrow(() -> new RuntimeException("Could not find " + reviewComment.path() + " on ref "
                                    + reviewComment.hash().get().hex() + " in repo " + pr.repository().name()))
                            .lines().collect(Collectors.toList());
                    for (int i = Math.max(0, reviewComment.line() - 3); i < Math.min(contents.size(), reviewComment.line()); ++i) {
                        body.append("> ").append(i + 1).append(": ").append(contents.get(i)).append("\n");
                    }
                    body.append("\n");
                } catch (RuntimeException e) {
                    body.append("> (failed to retrieve contents of file, check the PR for context)\n");
                }
            }
        }
        body.append(filterCommentsAndCommands(reviewComment.body()));
        return body.toString();
    }

    private static String composeReviewVerdict(Review review, HostUserToUsername hostUserToUsername, HostUserToRole hostUserToRole) {
        var result = new StringBuilder();
        if (review.verdict() != Review.Verdict.NONE) {
            if (review.verdict() == Review.Verdict.APPROVED) {
                result.append("Marked as reviewed");
            } else {
                result.append("Changes requested");
            }
            result.append(" by ");
            result.append(hostUserToUsername.username(review.reviewer()));
            result.append(" (");
            result.append(hostUserToRole.role(review.reviewer()));
            result.append(").");
        }
        return result.toString();
    }

    static String composeReview(PullRequest pr, Review review, HostUserToUsername hostUserToUsername, HostUserToRole hostUserToRole) {
        if (review.body().isPresent() && !review.body().get().isBlank()) {
            return filterCommentsAndCommands(review.body().get());
        } else {
            return composeReviewVerdict(review, hostUserToUsername, hostUserToRole);
        }
    }

    static String composeReviewFooter(PullRequest pr, Review review, HostUserToUsername hostUserToUsername, HostUserToRole hostUserToRole) {
        var result = new StringBuilder();
        if (review.body().isPresent() && !review.body().get().isBlank()) {
            var verdict = composeReviewVerdict(review, hostUserToUsername, hostUserToRole);
            if (!verdict.isBlank()) {
                result.append(verdict);
                result.append("\n\n");
            }
        }
        result.append(composeReviewReplyFooter(pr, review));
        return result.toString();
    }

    static String composeReplyHeader(ZonedDateTime parentDate, EmailAddress parentAuthor) {
        return "On " + parentDate.format(DateTimeFormatter.RFC_1123_DATE_TIME) + ", " + parentAuthor.toString() + " wrote:";
    }

    static String composeClosedNotice(PullRequest pr) {
        return "This pull request has been closed without being integrated.";
    }

    static String composeIntegratedNotice(PullRequest pr, Repository localRepo, Commit commit) {
        var result = new StringBuilder();
        result.append("This pull request has now been integrated.\n\n");
        result.append("Changeset: ").append(commit.hash().abbreviate()).append("\n");
        result.append("Author:    ").append(commit.author().name()).append(" <").append(commit.author().email()).append(">\n");
        if (!commit.author().equals(commit.committer())) {
            result.append("Committer: ").append(commit.committer().name()).append(" <").append(commit.committer().email()).append(">\n");
        }
        result.append("URL:       ").append(pr.repository().webUrl(commit.hash())).append("\n");
        result.append("Stats:     ").append(stats(localRepo, commit.parents().get(0), commit.hash())).append("\n");
        result.append("\n");
        result.append(String.join("\n", commit.message()));

        return result.toString();
    }
}
