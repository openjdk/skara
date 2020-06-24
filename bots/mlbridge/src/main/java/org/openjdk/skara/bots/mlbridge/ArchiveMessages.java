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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ArchiveMessages {
    private static final Pattern commentPattern = Pattern.compile("<!--.*?-->",
                                                                  Pattern.DOTALL | Pattern.MULTILINE);

    private static String filterComments(String body) {
        var parsedBody = PullRequestBody.parse(body);
        body = parsedBody.bodyText();

        var commentMatcher = commentPattern.matcher(body);
        body = commentMatcher.replaceAll("");

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
            case 9: return "ten";
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

    private static String fetchCommand(PullRequest pr) {
        var repoUrl = pr.repository().webUrl();
        return "git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id();
    }

    static String composeConversation(PullRequest pr) {
        var filteredBody = filterComments(pr.body());
        if (filteredBody.isEmpty()) {
            filteredBody = pr.title().strip();
        }

        return filteredBody;
    }

    static String composeIncrementalRevision(PullRequest pr, Repository localRepository, String author, Hash head, Hash lastHead) {
        var ret = new StringBuilder();

        var incrementalUpdate = false;
        try {
            incrementalUpdate = localRepository.isAncestor(lastHead, head);
        } catch (IOException ignored) {
        }
        var commits = commits(localRepository, lastHead, head);
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
            ret.append("The incremental views will show differences compared to the previous content of the PR.");
            var commitsDescription = describeCommits(commits, "new");
            newCommitMessages.ifPresent(m -> ret.append(" The pull request contains ")
                                                .append(commitsDescription)
                                                .append(" since the last revision:\n\n")
                                                .append(m));
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

    static String composeReplyFooter(PullRequest pr) {
        return "PR: " + pr.webUrl();
    }

    // When changing this, ensure that the PR pattern in the notifier still matches
    static String composeConversationFooter(PullRequest pr, URI issueProject, String projectPrefix, Repository localRepo, WebrevDescription webrev, Hash base, Hash head) {
        var commits = commits(localRepo, base, head);
        var commitsLink = commitsLink(pr, base, head);
        var issueString = issueUrl(pr, issueProject, projectPrefix).map(url -> "  Issue: " + url + "\n").orElse("");
        return "Commit messages:\n" +
                formatCommitMessagesBrief(commits, commitsLink).orElse("") + "\n\n" +
                "Changes: " + pr.changeUrl() + "\n" +
                " Webrev: " + webrev.uri().toString() + "\n" +
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
            var containsConflicts = webrevs.stream().anyMatch(w -> w.type().equals(WebrevDescription.Type.MERGE_CONFLICT));
            var containsMergeDiffs = webrevs.stream().anyMatch(w -> w.type().equals(WebrevDescription.Type.MERGE_TARGET) ||
                    w.type().equals(WebrevDescription.Type.MERGE_SOURCE));

            webrevLinks = "The webrev" + (webrevs.size() > 1 ? "s" : "") + " contain" + (webrevs.size() == 1 ? "s" : "") + " " +
                    (containsConflicts ? "the conflicts with " + pr.targetRef() : "") +
                    (containsConflicts && containsMergeDiffs ? " and " : "") +
                    (containsMergeDiffs ? "the adjustments done while merging with regards to each parent branch" : "")
                    +":\n" +
                    webrevs.stream()
                           .map(d -> String.format(" - %s: %s", d.shortLabel(), d.uri()))
                           .collect(Collectors.joining("\n")) + "\n\n";
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
                " Webrev: " + fullWebrev.uri().toString() + "\n" +
                "  Stats: " + stats(localRepo, base, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
                "  Fetch: " + fetchCommand(pr) + "\n\n" +
                composeReplyFooter(pr);
    }

    static String composeIncrementalFooter(PullRequest pr, Repository localRepo, WebrevDescription fullWebrev, WebrevDescription incrementalWebrev, Hash head, Hash lastHead) {
        return "Changes:\n" +
                "  - all: " + pr.changeUrl() + "\n" +
                "  - new: " + pr.changeUrl(lastHead) + "\n\n" +
                "Webrevs:\n" +
                " - full: " + fullWebrev.uri().toString() + "\n" +
                " - incr: " + incrementalWebrev.uri().toString() + "\n\n" +
                "  Stats: " + stats(localRepo, lastHead, head) + "\n" +
                "  Patch: " + pr.diffUrl().toString() + "\n" +
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
            var verdict = composeReviewVerdict(review, hostUserToUserName, hostUserToRole);
            if (!verdict.isBlank()) {
                result.append(verdict);
                result.append("\n\n");
            }
        }
        result.append(composeReplyFooter(pr));
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
        result.append("Stats:     ").append(stats(localRepo, commit.hash(), commit.parents().get(0))).append("\n");
        result.append("\n");
        result.append(String.join("\n", commit.message()));

        return result.toString();
    }
}
