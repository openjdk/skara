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
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ArchiveMessages {
    private static String formatCommit(Commit commit) {
        var ret = new StringBuilder();
        var message = commit.message();
        if (message.size() == 0) {
            ret.append("<no commit message found>");
        } else {
            var abbrev = commit.hash().abbreviate();
            ret.append(" - ").append(abbrev).append(": ").append(message.get(0).strip());
        }
        return ret.toString();
    }

    private static final Pattern commentPattern = Pattern.compile("<!--.*?-->",
                                                                  Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern cutoffPattern = Pattern.compile("(.*?)<!-- Anything below this marker will be .*? -->",
                                                                 Pattern.DOTALL | Pattern.MULTILINE);
    private static String filterComments(String body) {
        var cutoffMatcher = cutoffPattern.matcher(body);
        if (cutoffMatcher.find()) {
            body = cutoffMatcher.group(1);
        }

        var commentMatcher = commentPattern.matcher(body);
        body = commentMatcher.replaceAll("");

        body = MarkdownToText.removeFormatting(body);
        return body.strip();
    }

    @FunctionalInterface
    interface CommitFormatter {
        String format(Commit commit);
    }

    private static String formatCommitMessages(Repository localRepo, Hash first, Hash last, CommitFormatter formatter) {
        try (var commits = localRepo.commits(first.hex() + ".." + last.hex())) {
            return commits.stream()
                          .map(formatter::format)
                          .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<String> issueUrl(PullRequest pr, URI issueTracker, String projectPrefix) {
        var issue = Issue.fromString(pr.title());
        return issue.map(value -> URIBuilder.base(issueTracker).appendPath(projectPrefix + "-" + value.id()).build().toString());
    }

    private static String stats(Repository localRepo, Hash base, Hash head) {
        try {
            var diff = localRepo.diff(base, head);
            var inserted = diff.added();
            var deleted = diff.removed();
            var modified = diff.modified();
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

    private static String diffUrl(PullRequest pr) {
        return pr.webUrl() + ".diff";
    }

    private static String fetchCommand(PullRequest pr) {
        var repoUrl = pr.repository().webUrl();
        return "git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id();
    }

    static String composeConversation(PullRequest pr, Hash base, Hash head) {
        var filteredBody = filterComments(pr.body());
        if (filteredBody.isEmpty()) {
            filteredBody = pr.title().strip();
        }
        return filteredBody;
    }

    static String composeRevision(PullRequest pr, Repository localRepository, Hash base, Hash head, Hash lastBase, Hash lastHead) {
        try {
            if (base.equals(lastBase)) {
                if (localRepository.isAncestor(lastHead, head)) {
                    var updateCount = localRepository.commitMetadata(lastHead.hex() + ".." + head.hex()).size();
                    return "The pull request has been updated with " + updateCount + " additional commit" + (updateCount != 1 ? "s" : "") + ".";
                } else {
                    return "Previous commits in this pull request have been removed, probably due to a force push. " +
                            "The incremental views will show differences compared to the previous content of the PR.";
                }
            } else {
                try {
                    localRepository.checkout(lastHead, true);
                    localRepository.rebase(base, "duke", "duke@openjdk.org");
                    var rebasedLastHead = localRepository.head();
                    return "The pull request has been updated with a new target base due to a merge or a rebase. " +
                            "The incremental webrev excludes the unrelated changes brought in by the merge/rebase.";
                } catch (IOException e) {
                    return "The pull request has been updated with a new target base due to a merge or a rebase.";
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String composeReplySubject(String parentSubject) {
        if (parentSubject.startsWith("Re: ")) {
            return parentSubject;
        } else {
            return "Re: " + parentSubject;
        }
    }

    static String composeReplyFooter(PullRequest pr) {
        return "PR: " + pr.webUrl();
    }

    // When changing this, ensure that the PR pattern in the notifier still matches
    static String composeConversationFooter(PullRequest pr, URI issueProject, String projectPrefix, Repository localRepo, URI webrev, Hash base, Hash head) {
        var commitMessages = formatCommitMessages(localRepo, base, head, ArchiveMessages::formatCommit);
        var issueString = issueUrl(pr, issueProject, projectPrefix).map(url -> "  Issue: " + url + "\n").orElse("");
        return "Commits:\n" +
                commitMessages + "\n\n" +
                "Changes: " + pr.changeUrl() + "\n" +
                " Webrev: " + webrev + "\n" +
                issueString +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + diffUrl(pr) + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeRebaseFooter(PullRequest pr, Repository localRepo, URI fullWebrev, Hash base, Hash head) {
        var commitMessages = formatCommitMessages(localRepo, base, head, ArchiveMessages::formatCommit);
        return "Commits:\n" +
                commitMessages + "\n\n" +
                "Changes: " + pr.changeUrl() + "\n" +
                " Webrev: " + fullWebrev.toString() + "\n" +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + diffUrl(pr) + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeIncrementalFooter(PullRequest pr, Repository localRepo, URI fullWebrev, URI incrementalWebrev, Hash head, Hash lastHead) {
        var newCommitMessages = formatCommitMessages(localRepo, lastHead, head, ArchiveMessages::formatCommit);
        return "Added commits:\n" +
                newCommitMessages + "\n\n" +
                "Changes:\n" +
                "  - all: " + pr.changeUrl() + "\n" +
                "  - new: " + pr.changeUrl(lastHead) + "\n\n" +
                "Webrevs:\n" +
                " - full: " + fullWebrev.toString() + "\n" +
                " - incr: " + incrementalWebrev.toString() + "\n\n" +
                "  Stats: " + stats(localRepo, lastHead, head) + "\n" +
                "  Patch: " + diffUrl(pr) + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeComment(Comment comment) {
        return filterComments(comment.body());
    }

    static String composeReviewComment(PullRequest pr, ReviewComment reviewComment) {
        var body = new StringBuilder();

        // Add some context to the first post
        if (reviewComment.parent().isEmpty()) {
            body.append(reviewComment.path()).append(" line ").append(reviewComment.line()).append(":\n\n");
            try {
                var contents = pr.repository().fileContents(reviewComment.path(), reviewComment.hash().hex()).lines().collect(Collectors.toList());
                for (int i = Math.max(0, reviewComment.line() - 2); i < Math.min(contents.size(), reviewComment.line() + 1); ++i) {
                    body.append("> ").append(i + 1).append(": ").append(contents.get(i)).append("\n");
                }
                body.append("\n");
            } catch (RuntimeException e) {
                body.append("> (failed to retrieve contents of file, check the PR for context)\n");
            }
        }
        body.append(filterComments(reviewComment.body()));
        return body.toString();
    }

    private static String composeReviewVerdict(Review review, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        var result = new StringBuilder();
        if (review.verdict() != Review.Verdict.NONE) {
            if (review.verdict() == Review.Verdict.APPROVED) {
                result.append("Marked as reviewed");
            } else {
                result.append("Changes requested");
            }
            result.append(" by ");
            result.append(hostUserToUserName.userName(review.reviewer()));
            result.append(" (");
            result.append(hostUserToRole.role(review.reviewer()));
            result.append(").");
        }
        return result.toString();
    }

    static String composeReview(PullRequest pr, Review review, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        if (review.body().isPresent() && !review.body().get().isBlank()) {
            return filterComments(review.body().get());
        } else {
            return composeReviewVerdict(review, hostUserToUserName, hostUserToRole);
        }
    }

    static String composeReviewFooter(PullRequest pr, Review review, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        var result = new StringBuilder();
        if (review.body().isPresent() && !review.body().get().isBlank()) {
            result.append(composeReviewVerdict(review, hostUserToUserName, hostUserToRole));
            result.append("\n\n");
        }
        result.append(composeReplyFooter(pr));
        return result.toString();
    }

    static String composeReplyHeader(ZonedDateTime parentDate, EmailAddress parentAuthor) {
        return "On " + parentDate.format(DateTimeFormatter.RFC_1123_DATE_TIME) + ", " + parentAuthor.toString() + " wrote:";
    }
}
