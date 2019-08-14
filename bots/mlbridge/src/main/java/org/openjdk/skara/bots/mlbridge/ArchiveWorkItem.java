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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.email.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ArchiveWorkItem implements WorkItem {
    private final PullRequest pr;
    private final MailingListBridgeBot bot;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    ArchiveWorkItem(PullRequest pr, MailingListBridgeBot bot) {
        this.pr = pr;
        this.bot = bot;
    }

    @Override
    public String toString() {
        return "ArchiveWorkItem@" + bot.codeRepo().getName() + "#" + pr.getId();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof ArchiveWorkItem)) {
            return true;
        }
        ArchiveWorkItem otherItem = (ArchiveWorkItem)other;
        if (!pr.getId().equals(otherItem.pr.getId())) {
            return true;
        }
        if (!bot.codeRepo().getName().equals(otherItem.bot.codeRepo().getName())) {
            return true;
        }
        return false;
    }

    private void pushMbox(Repository localRepo, String message) {
        try {
            localRepo.add(localRepo.root().resolve("."));
            var hash = localRepo.commit(message, bot.emailAddress().fullName().orElseThrow(), bot.emailAddress().address());
            localRepo.push(hash, bot.archiveRepo().getUrl(), "master");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern replyToPattern = Pattern.compile("^\\s*@([-A-Za-z0-9]+)");

    private Optional<Comment> getParentPost(Comment post, List<Comment> all) {
        var matcher = replyToPattern.matcher(post.body());
        if (matcher.find()) {
            var replyToName = matcher.group(1);
            var replyToNamePattern = Pattern.compile("^" + replyToName + "$");

            var postIterator = all.listIterator();
            while (postIterator.hasNext()) {
                var cur = postIterator.next();
                if (cur == post) {
                    break;
                }
            }

            while (postIterator.hasPrevious()) {
                var cur = postIterator.previous();
                var userMatcher = replyToNamePattern.matcher(cur.author().userName());
                if (userMatcher.matches()) {
                    return Optional.of(cur);
                }
            }
        }

        return Optional.empty();
    }

    private String quoteBody(String body) {
        return Arrays.stream(body.strip().split("\\R"))
                     .map(line -> line.length() > 0 ? line.charAt(0) == '>' ? ">" + line : "> " + line : "> ")
                     .collect(Collectors.joining("\n"));
    }

    private static final Pattern commentPattern = Pattern.compile("<!--.*?-->",
                                                                  Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern cutoffPattern = Pattern.compile("(.*?)<!-- Anything below this marker will be .*? -->",
                                                                 Pattern.DOTALL | Pattern.MULTILINE);
    private String filterComments(String body) {
        var cutoffMatcher = cutoffPattern.matcher(body);
        if (cutoffMatcher.find()) {
            body = cutoffMatcher.group(1);
        }

        var commentMatcher = commentPattern.matcher(body);
        body = commentMatcher.replaceAll("");

        return body.strip();
    }

    private String formatCommit(Commit commit) {
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

    private String composeConversation(String body, PullRequestInstance prInstance, URI webrev) {
        var commitMessages = prInstance.formatCommitMessages(prInstance.baseHash(), prInstance.headHash(), this::formatCommit);
        var filteredBody = filterComments(body);
        if (filteredBody.isEmpty()) {
            filteredBody = pr.getTitle().strip();
        }
        return filteredBody + "\n\n" +
                infoSeparator + "\n\n" +
                "Commits:\n" +
                commitMessages + "\n\n" +
                "Pull request:\n" +
                pr.getWebUrl() + "\n\n" +
                "Webrev:\n" +
                webrev.toString() + "\n\n" +
                "Patch:\n" +
                prInstance.diffUrl() + "\n\n" +
                "Fetch command:\n" +
                prInstance.fetchCommand();
    }

    private String composeReply(ZonedDateTime date, EmailAddress author, String parentBody, String body) {
        return "On " + date.format(DateTimeFormatter.RFC_1123_DATE_TIME) + ", " + author.toString() + " wrote:\n" +
                "\n" +
                quoteBody(parentBody) +
                "\n\n" +
                filterComments(body) +
                "\n\n" +
                "PR: " + pr.getWebUrl();
    }

    private String verdictToString(Review.Verdict verdict) {
        switch (verdict) {
            case APPROVED:
                return "changes are approved";
            case DISAPPROVED:
                return "more changes are needed";
            case NONE:
                return "a comment has been added";
            default:
                throw new RuntimeException("Unknown verdict: " + verdict);
        }
    }

    private String composeReview(ZonedDateTime date, EmailAddress parentAuthor, String parentBody, Review review) {
        var body = new StringBuilder();
        var author = getAuthorAddress(review.reviewer());
        body.append("This PR has been reviewed by ");
        body.append(author.fullName().orElse(author.localPart()));
        body.append(" - ");
        body.append(verdictToString(review.verdict()));
        body.append(".");
        if (review.body().isPresent()) {
            body.append(" Review comment:\n\n");
            body.append(review.body().get());
        }

        return "On " + date.format(DateTimeFormatter.RFC_1123_DATE_TIME) + ", " + parentAuthor.toString() + " wrote:\n" +
                "\n" +
                quoteBody(parentBody) +
                "\n\n" +
                filterComments(body.toString()) +
                "\n\n" +
                "PR: " + pr.getWebUrl();
    }

    private String composeRebaseComment(Hash lastBase, PullRequestInstance prInstance, URI fullWebrev) {
        var commitMessages = prInstance.formatCommitMessages(prInstance.baseHash(), prInstance.headHash(), this::formatCommit);
        return "The pull request has been updated with a complete new set of changes (possibly due to a rebase).\n\n" +
                infoSeparator + "\n\n" +
                "Commits:\n" +
                commitMessages + "\n\n" +
                "Pull request:\n" +
                pr.getWebUrl() + "\n\n" +
                "Webrev:\n" +
                fullWebrev.toString() + "\n\n" +
                "Updated full patch:\n" +
                prInstance.diffUrl() + "\n\n" +
                "Fetch command:\n" +
                prInstance.fetchCommand();
    }

    private String composeIncrementalComment(Hash lastHead, PullRequestInstance prInstance, URI fullWebrev, URI incrementalWebrev) {
        var newCommitMessages = prInstance.formatCommitMessages(lastHead, prInstance.headHash(), this::formatCommit);
        return "The pull request has been updated with additional changes.\n\n" +
                infoSeparator + "\n\n" +
                "Added commits:\n" +
                newCommitMessages + "\n\n" +
                "Pull request:\n" +
                pr.getWebUrl() + "\n\n" +
                "Webrevs:\n" +
                " - full: " + fullWebrev.toString() + "\n" +
                " - inc: " + incrementalWebrev.toString() + "\n\n" +
                "Updated full patch:\n" +
                prInstance.diffUrl() + "\n\n" +
                "Fetch command:\n" +
                prInstance.fetchCommand();
    }

    private String composeReadyForIntegrationComment() {
        return "This PR now fulfills all the requirements for integration, and is only awaiting the final " +
                "integration command from the author." +
                "\n\n" +
                "PR: " + pr.getWebUrl();
    }

    private Repository materializeArchive(Path scratchPath) {
        try {
            return Repository.materialize(scratchPath, bot.archiveRepo().getUrl(), pr.getTargetRef());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final static Pattern commandPattern = Pattern.compile("^/.*$");

    private boolean ignoreComment(HostUserDetails author, String body) {
        if (pr.repository().host().getCurrentUserDetails().equals(author)) {
            return true;
        }
        if (bot.ignoredUsers().contains(author.userName())) {
            return true;
        }
        var commandMatcher = commandPattern.matcher(body);
        if (commandMatcher.matches()) {
            return true;
        }
        return false;
    }

    private EmailAddress getUniqueMessageId(String identifier) {
        try {
            var prSpecific = pr.repository().getName().replace("/", ".") + "." + pr.getId();
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(prSpecific.getBytes(StandardCharsets.UTF_8));
            digest.update(identifier.getBytes(StandardCharsets.UTF_8));
            var encodedCommon = Base64.getUrlEncoder().encodeToString(digest.digest());

            return EmailAddress.from(encodedCommon + "." + UUID.randomUUID() + "@" + pr.repository().getUrl().getHost());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    private String getStableMessageId(EmailAddress uniqueMessageId) {
        return uniqueMessageId.localPart().split("\\.")[0];
    }

    private EmailAddress getMessageId() {
        return getUniqueMessageId("fc");
    }

    private EmailAddress getMessageId(Comment comment) {
        return getUniqueMessageId("pc" + comment.id());
    }

    private EmailAddress getMessageId(ReviewComment comment) {
        return getUniqueMessageId("rc" + comment.id());
    }

    private EmailAddress getMessageId(Hash hash) {
        return getUniqueMessageId("ha" + hash.hex());
    }

    private EmailAddress getMessageId(String raw) {
        return getUniqueMessageId("rw" + raw);
    }

    private EmailAddress getMessageId(Review review) {
        return getUniqueMessageId("rv" + review.id());
    }

    private EmailAddress getAuthorAddress(HostUserDetails originalAuthor) {
        return EmailAddress.from(originalAuthor.fullName() + " via " + pr.repository().getUrl().getHost(),
                                 bot.emailAddress().address());
    }

    private Email newConversation(PullRequestInstance prInstance, URI webrev) {
        var body = composeConversation(pr.getBody(), prInstance, webrev);
        var email = Email.create(getAuthorAddress(pr.getAuthor()), "RFR: " + pr.getTitle(), body)
                         .sender(bot.emailAddress())
                         .id(getMessageId())
                         .header("PR-Head-Hash", prInstance.headHash().hex())
                         .header("PR-Base-Hash", prInstance.baseHash().hex())
                         .build();
        return email;
    }


    private Email comment(Email parent, Comment comment) {
        var reply = composeReply(parent.date(), parent.author(), parent.body(), comment.body());
        var references = parent.id().toString();
        if (parent.hasHeader("References")) {
            references = parent.headerValue("References") + " " + references;
        }

        var email = Email.create(getAuthorAddress(comment.author()), "Re: RFR: " + pr.getTitle(), reply)
                         .sender(bot.emailAddress())
                         .recipient(parent.author())
                         .id(getMessageId(comment))
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", references)
                         .build();
        return email;
    }

    private Email review(Email parent, Review review) {
        var body = composeReview(parent.date(), parent.author(), parent.body(), review);
        var email = Email.create(getAuthorAddress(review.reviewer()), "Re: RFR: " + pr.getTitle(), body)
                         .sender(bot.emailAddress())
                         .recipient(parent.author())
                         .id(getMessageId(review))
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", parent.id().toString())
                         .build();
        return email;

    }

    private Email reviewComment(Email parent, ReviewComment comment) {
        var body = new StringBuilder();

        // Add some context to the first post
        if (comment.parent().isEmpty()) {
            var contents = pr.repository().getFileContents(comment.path(), comment.hash().hex()).lines().collect(Collectors.toList());

            body.append(comment.path()).append(" line ").append(comment.line()).append(":\n\n");
            for (int i = Math.max(0, comment.line() - 2); i < Math.min(contents.size(), comment.line() + 1); ++i) {
                body.append("> ").append(i + 1).append(": ").append(contents.get(i)).append("\n");
            }
            body.append("\n");
        }
        body.append(comment.body());

        var reply = composeReply(parent.date(), parent.author(), parent.body(), body.toString());
        var references = parent.id().toString();
        if (parent.hasHeader("References")) {
            references = parent.headerValue("References") + " " + references;
        }

        var email = Email.create(getAuthorAddress(comment.author()), "Re: RFR: " + pr.getTitle(), reply)
                         .sender(bot.emailAddress())
                         .recipient(parent.author())
                         .id(getMessageId(comment))
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", references)
                         .build();
        return email;
    }

    private Email rebaseComment(Email parent, Hash lastBase, PullRequestInstance prInstance, URI fullWebrev) {
        var body = composeRebaseComment(lastBase, prInstance, fullWebrev);
        var email = Email.create(getAuthorAddress(pr.getAuthor()), "Re: RFR: " + pr.getTitle(), body)
                         .sender(bot.emailAddress())
                         .recipient(parent.author())
                         .id(getMessageId(prInstance.headHash()))
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", parent.id().toString())
                         .header("PR-Head-Hash", prInstance.headHash().hex())
                         .header("PR-Base-Hash", prInstance.baseHash().hex())
                         .build();
        return email;
    }

    private Email incrementalComment(Email parent, Hash lastHead, PullRequestInstance prInstance, URI fullWebrev, URI incrementalWebrev) {
        var body = composeIncrementalComment(lastHead, prInstance, fullWebrev, incrementalWebrev);
        var email = Email.create(getAuthorAddress(pr.getAuthor()), "Re: RFR: " + pr.getTitle(), body)
                         .sender(bot.emailAddress())
                         .recipient(parent.author())
                         .id(getMessageId(prInstance.headHash()))
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", parent.id().toString())
                         .header("PR-Head-Hash", prInstance.headHash().hex())
                         .header("PR-Base-Hash", prInstance.baseHash().hex())
                         .build();
        return email;
    }

    private Email readyForIntegrationComment(Email parent, Set<String> currentLabels, int numLabelComments) {
        var body = composeReadyForIntegrationComment();
        var email = Email.create(getAuthorAddress(pr.getAuthor()), "Re: RFR: " + pr.getTitle(), body)
                         .sender(bot.emailAddress())
                         .recipient(parent.author())
                         .id(getMessageId("labelcomment" + numLabelComments))
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", parent.id().toString())
                         .header("PR-Labels", String.join(";", currentLabels))
                         .build();
        return email;
    }

    private List<Email> parseArchive(MailingList archive) {
        var conversations = archive.conversations(Duration.ofDays(365));

        if (conversations.size() == 0) {
            return new ArrayList<>();
        } else if (conversations.size() == 1) {
            var conversation = conversations.get(0);
            return conversation.allMessages();
        } else {
            throw new RuntimeException("Something is wrong with the mbox");
        }
    }

    private Map<Email, Email> findParents(Map<EmailAddress, Email> emailIds) {
        var parents = new HashMap<Email, Email>();
        for (var entry : emailIds.entrySet()) {
            if (entry.getValue().hasHeader("In-Reply-To")) {
                var replyId = EmailAddress.parse(entry.getValue().headerValue("In-Reply-To"));
            }
        }
        return parents;
    }

    private final Pattern replyHeaderPattern = Pattern.compile("^On .* <(.*)> wrote:$.*",
                                                               Pattern.DOTALL | Pattern.MULTILINE);

    // Combine mails from the same author, directed at the same person, into a single mail
    private List<Email> combineMails(List<Email> emails) {
        var byAuthor = emails.stream().collect(Collectors.groupingBy(Email::author));
        var ret = new ArrayList<Email>();
        for (var authorMails : byAuthor.entrySet()) {
            var byTarget = authorMails.getValue().stream()
                                      .collect(Collectors.groupingBy(email -> {
                                          var matcher = replyHeaderPattern.matcher(email.body());
                                          if (matcher.matches()) {
                                              return matcher.group(1);
                                          } else {
                                              // No grouping of undirected messages
                                              return "";
                                          }
                                      }));

            for (var targetMails : byTarget.entrySet()) {
                if (!targetMails.getKey().isEmpty()) {
                    var first = targetMails.getValue().get(0);
                    var body = new StringBuilder(first.body());
                    for (int i = 1; i < targetMails.getValue().size(); ++i) {
                        var addon = targetMails.getValue().get(i).body().lines()
                                               .skip(2)
                                               .dropWhile(line -> line.startsWith(">"))
                                               .skip(1)
                                               .collect(Collectors.joining("\n"));
                        body.append("\n\n").append(addon);
                    }
                    var combined = Email.from(first).body(body.toString()).build();
                    ret.add(combined);

                } else {
                    ret.addAll(targetMails.getValue());
                }
            }
        }

        return ret;
    }

    private static final String webrevCommentMarker = "<!-- mlbridge webrev comment -->";
    private static final String webrevHeaderMarker = "<!-- mlbridge webrev header -->";
    private static final String webrevListMarker = "<!-- mlbridge webrev list -->";

    private void updateWebrevComment(List<Comment> comments, int index, URI fullWebrev, URI incWebrev) {
        var existing = comments.stream()
                               .filter(comment -> comment.author().equals(pr.repository().host().getCurrentUserDetails()))
                               .filter(comment -> comment.body().contains(webrevCommentMarker))
                               .findAny();
        var comment = webrevCommentMarker + "\n";
        comment += webrevHeaderMarker + "\n";
        comment += "### Webrevs" + "\n";
        comment += webrevListMarker + "\n";
        comment += " * " + String.format("%02d", index) + ": [Full](" + fullWebrev.toString() + ")";
        if (incWebrev != null) {
            comment += " - [Incremental](" + incWebrev.toString() + ")";
        }
        comment += " (" + pr.getHeadHash() + ")\n";

        if (existing.isPresent()) {
            if (existing.get().body().contains(fullWebrev.toString())) {
                log.fine("Webrev link already posted - skipping update");
                return;
            }
            var previousListStart = existing.get().body().indexOf(webrevListMarker) + webrevListMarker.length() + 1;
            var previousList = existing.get().body().substring(previousListStart);
            comment += previousList;
            pr.updateComment(existing.get().id(), comment);
        } else {
            pr.addComment(comment);
        }
    }

    @Override
    public void run(Path scratchPath) {
        var path = scratchPath.resolve("mlbridge");
        var archiveRepo = materializeArchive(path);
        var mboxBasePath = path.resolve(bot.codeRepo().getName());
        var mbox = MailingListServerFactory.createMboxFileServer(mboxBasePath);
        var archive = mbox.getList(pr.getId());
        var archiveMails = parseArchive(archive);

        // First determine if this PR should be inspected further or not
        if (archiveMails.isEmpty()) {
            var labels = new HashSet<>(pr.getLabels());
            for (var readyLabel : bot.readyLabels()) {
                if (!labels.contains(readyLabel)) {
                    log.fine("PR is not yet ready - missing label '" + readyLabel + "'");
                    return;
                }
            }
        }

        // Also inspect comments before making the first post
        var comments = pr.getComments();
        if (archiveMails.isEmpty()) {
            for (var readyComment : bot.readyComments().entrySet()) {
                var commentFound = false;
                for (var comment : comments) {
                    if (comment.author().userName().equals(readyComment.getKey())) {
                        var matcher = readyComment.getValue().matcher(comment.body());
                        if (matcher.find()) {
                            commentFound = true;
                            break;
                        }
                    }
                }
                if (!commentFound) {
                    log.fine("PR is not yet ready - missing ready comment from '" + readyComment.getKey() +
                                     "containing '" + readyComment.getValue().pattern() + "'");
                    return;
                }
            }
        }

        var webrevPath = scratchPath.resolve("mlbridge-webrevs");
        var listServer = MailingListServerFactory.createMailmanServer(bot.listArchive(), bot.smtpServer());
        var list = listServer.getList(bot.listAddress().address());
        var newMails = new ArrayList<Email>();
        var stableIdToMail = archiveMails.stream().collect(Collectors.toMap(email -> getStableMessageId(email.id()),
                                                                            Function.identity()));
        var prInstance = new PullRequestInstance(scratchPath.resolve("mlbridge-mergebase"), pr);

        // First post
        if (archiveMails.isEmpty()) {
            var webrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, prInstance.baseHash(),
                                                              prInstance.headHash(), "00");
            var firstMail = newConversation(prInstance, webrev);
            archive.post(firstMail);
            newMails.add(firstMail);
            stableIdToMail.put(getStableMessageId(firstMail.id()), firstMail);
            updateWebrevComment(comments, 0, webrev, null);
            log.fine("Posting new PR conversation");
        } else {
            // Determine the latest head hash reported
            var reportedHeads = archiveMails.stream()
                                            .filter(email -> email.hasHeader("PR-Head-Hash"))
                                            .map(email -> email.headerValue("PR-Head-Hash"))
                                            .map(Hash::new)
                                            .collect(Collectors.toList());
            var latestHead = reportedHeads.size() > 0 ? reportedHeads.get(reportedHeads.size() - 1) : pr.getHeadHash();

            // Check if the head has changed
            if (!pr.getHeadHash().equals(latestHead)) {
                log.fine("Head hash change detected: current: " + pr.getHeadHash() + " - existing: " + reportedHeads);

                // Determine the latest base hash reported
                var reportedBases = archiveMails.stream()
                                                .filter(email -> email.hasHeader("PR-Base-Hash"))
                                                .map(email -> email.headerValue("PR-Base-Hash"))
                                                .map(Hash::new)
                                                .collect(Collectors.toList());
                if (reportedBases.size() == 0) {
                    throw new RuntimeException("No previous bases found?");
                }
                var latestBase = reportedBases.get(reportedBases.size() - 1);
                var firstMail = archiveMails.get(0);
                Email commentMail;
                if (!prInstance.baseHash().equals(latestBase)) {
                    var fullWebrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, prInstance.baseHash(),
                                                                          prInstance.headHash(), String.format("%02d", reportedHeads.size()));
                    commentMail = rebaseComment(firstMail, latestBase, prInstance, fullWebrev);
                    updateWebrevComment(comments, reportedHeads.size(), fullWebrev, null);
                } else {
                    var index = reportedHeads.size();
                    var fullWebrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, prInstance.baseHash(),
                                                                          prInstance.headHash(), String.format("%02d", index));
                    var incrementalWebrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, latestHead,
                                                                                 prInstance.headHash(), String.format("%02d-%02d", index - 1, index));
                    commentMail = incrementalComment(firstMail, latestHead, prInstance, fullWebrev, incrementalWebrev);
                    updateWebrevComment(comments, index, fullWebrev, incrementalWebrev);
                }
                archive.post(commentMail);
                newMails.add(commentMail);
                stableIdToMail.put(getStableMessageId(commentMail.id()), commentMail);
            }
        }

        // Regular comments
        var previous = archiveMails.size() > 0 ? archiveMails.get(0) : newMails.get(0);
        for (var comment : comments) {
            var id = getStableMessageId(getMessageId(comment));
            if (stableIdToMail.containsKey(id)) {
                previous = stableIdToMail.get(id);
                continue;
            }
            if (ignoreComment(comment.author(), comment.body())) {
                continue;
            }

            // Try to determine a parent post from @mentions
            var parentComment = getParentPost(comment, comments);
            var parent = parentComment.map(c -> stableIdToMail.get(getStableMessageId(getMessageId(c)))).orElse(previous);

            var commentMail = comment(parent, comment);
            archive.post(commentMail);
            newMails.add(commentMail);
            stableIdToMail.put(getStableMessageId(commentMail.id()), commentMail);
            previous = commentMail;
        }

        // Review comments
        final var first = archiveMails.size() > 0 ? archiveMails.get(0) : newMails.get(0);
        var reviews = pr.getReviews();
        for (var review : reviews) {
            var id = getStableMessageId(getMessageId(review));
            if (stableIdToMail.containsKey(id)) {
                continue;
            }
            if (ignoreComment(review.reviewer(), review.body().orElse(""))) {
                continue;
            }

            var commentMail = review(first, review);
            archive.post(commentMail);
            newMails.add(commentMail);
            stableIdToMail.put(getStableMessageId(commentMail.id()), commentMail);
        }


        // File specific comments
        var reviewComments = pr.getReviewComments();
        for (var reviewComment : reviewComments) {
            var id = getStableMessageId(getMessageId(reviewComment));
            if (stableIdToMail.containsKey(id)) {
                continue;
            }
            if (ignoreComment(reviewComment.author(), reviewComment.body())) {
                continue;
            }

            var parent = reviewComment.parent().map(c -> stableIdToMail.get(getStableMessageId(getMessageId(c)))).orElse(first);
            var commentMail = reviewComment(parent, reviewComment);
            archive.post(commentMail);
            newMails.add(commentMail);
            stableIdToMail.put(getStableMessageId(commentMail.id()), commentMail);
        }

        if (newMails.isEmpty()) {
            return;
        }

        // Push all new mails to the archive repository
        pushMbox(archiveRepo, "Adding comments for PR " + bot.codeRepo().getName() + "/" + pr.getId());

        // Combine and post all new mails to the list
        var listMails = combineMails(newMails);
        for (var mail : listMails) {
            list.post(mail);
        }
    }
}
