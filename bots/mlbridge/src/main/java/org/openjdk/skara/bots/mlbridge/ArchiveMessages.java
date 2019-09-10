package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.Email;
import org.openjdk.skara.host.Review;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.function.Predicate;
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
            var filler = "\t".repeat(((abbrev.length() + 4 /* additional spacing */) / 8 /* tab size */) + 1 /* rounding */);
            ret.append(" - ").append(abbrev).append(":\t").append(message.get(0).strip());
            message.stream()
                   .skip(1)
                   .map(String::strip)
                   .filter(Predicate.not(String::isEmpty))
                   .forEach(line -> ret.append("\n").append(filler).append("\t").append(line));
        }
        return ret.toString();
    }

    private static final String infoSeparator = "----------------";

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

    private static String quoteBody(String body) {
        return Arrays.stream(body.strip().split("\\R"))
                     .map(line -> line.length() > 0 ? line.charAt(0) == '>' ? ">" + line : "> " + line : "> ")
                     .collect(Collectors.joining("\n"));
    }

    static String composeConversation(PullRequestInstance prInstance, URI webrev) {
        var commitMessages = prInstance.formatCommitMessages(prInstance.baseHash(), prInstance.headHash(), ArchiveMessages::formatCommit);
        var filteredBody = filterComments(prInstance.pr().getBody());
        if (filteredBody.isEmpty()) {
            filteredBody = prInstance.pr().getTitle().strip();
        }
        return filteredBody + "\n\n" +
                infoSeparator + "\n\n" +
                "Commits:\n" +
                commitMessages + "\n\n" +
                "Pull request:\n" +
                prInstance.pr().getWebUrl() + "\n\n" +
                "Webrev:\n" +
                webrev.toString() + "\n\n" +
                "Patch:\n" +
                prInstance.diffUrl() + "\n\n" +
                "Fetch command:\n" +
                prInstance.fetchCommand();
    }

    static String composeRebaseComment(PullRequestInstance prInstance, URI fullWebrev) {
        var commitMessages = prInstance.formatCommitMessages(prInstance.baseHash(), prInstance.headHash(), ArchiveMessages::formatCommit);
        return "The pull request has been updated with a complete new set of changes (possibly due to a rebase).\n\n" +
                infoSeparator + "\n\n" +
                "Commits:\n" +
                commitMessages + "\n\n" +
                "Pull request:\n" +
                prInstance.pr().getWebUrl() + "\n\n" +
                "Webrev:\n" +
                fullWebrev.toString() + "\n\n" +
                "Updated full patch:\n" +
                prInstance.diffUrl() + "\n\n" +
                "Fetch command:\n" +
                prInstance.fetchCommand();
    }

    static String composeIncrementalComment(Hash lastHead, PullRequestInstance prInstance, URI fullWebrev, URI incrementalWebrev) {
        var newCommitMessages = prInstance.formatCommitMessages(lastHead, prInstance.headHash(), ArchiveMessages::formatCommit);
        return "The pull request has been updated with additional changes.\n\n" +
                infoSeparator + "\n\n" +
                "Added commits:\n" +
                newCommitMessages + "\n\n" +
                "Pull request:\n" +
                prInstance.pr().getWebUrl() + "\n\n" +
                "Webrevs:\n" +
                " - full: " + fullWebrev.toString() + "\n" +
                " - inc: " + incrementalWebrev.toString() + "\n\n" +
                "Updated full patch:\n" +
                prInstance.diffUrl() + "\n\n" +
                "Fetch command:\n" +
                prInstance.fetchCommand();
    }

    private static String composeReplyFooter(PullRequestInstance prInstance) {
        return "PR: " + prInstance.pr().getWebUrl();
    }

    static String composeReply(Email parent, String body, PullRequestInstance prInstance) {
        return "On " + parent.date().format(DateTimeFormatter.RFC_1123_DATE_TIME) + ", " + parent.author().toString() + " wrote:\n" +
                "\n" +
                quoteBody(parent.body()) +
                "\n\n" +
                filterComments(body) +
                "\n\n" +
                composeReplyFooter(prInstance);
    }

    static String composeCombinedReply(Email parent, String body, PullRequestInstance prInstance) {
        var parentFooter = ArchiveMessages.composeReplyFooter(prInstance);
        var filteredParentBody = parent.body().substring(0, parent.body().length() - parentFooter.length()).strip();
        return filteredParentBody +
                "\n\n" +
                filterComments(body) +
                "\n\n" +
                parentFooter;
    }

    static String reviewCommentBody(String body, Review.Verdict verdict, String user, String role) {
        var result = new StringBuilder(filterComments(body));
        if (verdict != Review.Verdict.NONE) {
            if (result.length() > 0) {
                result.append("\n\n");
                result.append(infoSeparator);
                result.append("\n\n");
            }
            result.append("Review status set to ");
            if (verdict == Review.Verdict.APPROVED) {
                result.append("Approved");
            } else {
                result.append("Disapproved");
            }
            result.append(" by ");
            result.append(user);
            result.append(" (project role: ");
            result.append(role);
            result.append(").");
        }
        return result.toString();
    }

    static String reviewApprovalBodyReviewer(String reviewer) {
        return "This PR has been marked as Reviewed by " + reviewer + ".";
    }
}
