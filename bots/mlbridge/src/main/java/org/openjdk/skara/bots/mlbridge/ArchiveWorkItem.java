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

import java.util.logging.Level;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.bots.common.CommandNameEnum;
import org.openjdk.skara.bots.common.PullRequestConstants;
import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openjdk.skara.bots.common.PatternEnum.EXECUTION_COMMAND_PATTERN;
import static org.openjdk.skara.bots.common.PullRequestConstants.WEBREV_COMMENT_MARKER;

class ArchiveWorkItem implements WorkItem {
    private final PullRequest pr;
    private final MailingListBridgeBot bot;
    private final Consumer<RuntimeException> exceptionConsumer;
    private final Consumer<Instant> retryConsumer;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    ArchiveWorkItem(PullRequest pr, MailingListBridgeBot bot, Consumer<RuntimeException> exceptionConsumer, Consumer<Instant> retryConsumer) {
        this.pr = pr;
        this.bot = bot;
        this.exceptionConsumer = exceptionConsumer;
        this.retryConsumer = retryConsumer;
    }

    @Override
    public String toString() {
        return "ArchiveWorkItem@" + bot.codeRepo().name() + "#" + pr.id();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof ArchiveWorkItem otherArchiveItem)) {
            if (!(other instanceof LabelsUpdaterWorkItem otherLabelsUpdaterItem)) {
                return true;
            }
            if (!bot.equals(otherLabelsUpdaterItem.bot())) {
                return true;
            }
            return false;
        }
        if (!pr.isSame(otherArchiveItem.pr)) {
            return true;
        }
        return false;
    }

    private void pushMbox(Repository localRepo, String message) {
        IOException lastException = null;
        Hash hash;
        try {
            localRepo.add(localRepo.root().resolve("."));
            hash = localRepo.commit(message, bot.emailAddress().fullName().orElseThrow(), bot.emailAddress().address());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (int counter = 0; counter < 3; ++counter) {
            try {
                localRepo.push(hash, bot.archiveRepo().authenticatedUrl(), bot.archiveRef());
                return;
            } catch (IOException e) {
                log.info("Push to archive failed: " + e);
                try {
                    var remoteHead = localRepo.fetch(bot.archiveRepo().authenticatedUrl(), bot.archiveRef(), false).orElseThrow();
                    localRepo.rebase(remoteHead, bot.emailAddress().fullName().orElseThrow(), bot.emailAddress().address());
                    hash = localRepo.head();
                    log.info("Rebase successful -  new hash: " + hash);
                } catch (IOException e2) {
                    throw new UncheckedIOException(e2);
                }

                lastException = e;
            }
        }
        throw new UncheckedIOException(lastException);
    }

    private Repository materializeArchive(Path scratchPath) {
        try {
            return Repository.materialize(scratchPath, bot.archiveRepo().authenticatedUrl(),
                                          "+" + bot.archiveRef() + ":mlbridge_archive");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String filterOutCommands(String body) {
        var filteredBody = new StringBuilder();
        boolean readingMultiLineCommandArgs = false;
        for (var line : body.split("\\R")) {
            var preprocessedLine = BotUtils.preprocessCommandLine(line);
            var commandMatcher = EXECUTION_COMMAND_PATTERN.getPattern().matcher(preprocessedLine);
            if (commandMatcher.matches()) {
                readingMultiLineCommandArgs = false;
                var command = commandMatcher.group(1).toLowerCase();
                if (Arrays.stream(CommandNameEnum.values()).anyMatch(commandNameEnum -> commandNameEnum.name().equals(command))
                        && CommandNameEnum.valueOf(command).isMultiLine()) {
                    readingMultiLineCommandArgs = true;
                }
            } else {
                if (!readingMultiLineCommandArgs) {
                    filteredBody.append(line).append(System.lineSeparator());
                }
            }
        }
        return filteredBody.toString().strip();
    }

    private boolean ignoreComment(HostUser author, String body, ZonedDateTime createdTime, ZonedDateTime lastDraftTime, boolean isComment) {
        if (pr.repository().forge().currentUser().equals(author)) {
            if (pr.isOpen()) {
                return !PullRequestConstants.READY_FOR_SPONSOR_MARKER_PATTERN.matcher(body).find();
            } else {
                return true;
            }
        }
        if (bot.ignoredUsers().contains(author.username())) {
            if (pr.isOpen()) {
                return !PullRequestConstants.READY_FOR_SPONSOR_MARKER_PATTERN.matcher(body).find();
            } else {
                return true;
            }
        }

        // Check if this comment only contains command lines
        // For reviews, while the body is empty or only contains command, the bot should still archive it
        if (isComment && filterOutCommands(body).isEmpty()) {
            return true;
        }

        for (var ignoredCommentPattern : bot.ignoredComments()) {
            var ignoredCommentMatcher = ignoredCommentPattern.matcher(body);
            if (ignoredCommentMatcher.find()) {
                return true;
            }
        }
        // If the pull request was converted to draft, the comments
        // after the last converted time should be ignored.
        if (pr.isDraft()) {
            if (lastDraftTime != null && lastDraftTime.isBefore(createdTime)) {
                return true;
            }
        }
        return false;
    }

    private static final String webrevHeaderMarker = "<!-- mlbridge webrev header -->";
    private static final String webrevListMarker = "<!-- mlbridge webrev list -->";

    private void updateWebrevComment(List<Comment> comments, int index, List<WebrevDescription> webrevs) {
        if (webrevs.stream().noneMatch(w -> (w.uri() != null || w.diffTooLarge()))) {
            return;
        }
        var existing = comments.stream()
                               .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
                               .filter(comment -> comment.body().contains(WEBREV_COMMENT_MARKER))
                               .findAny();
        var webrevDescriptions = webrevs.stream()
                .map(d -> d.diffTooLarge() ?
                        String.format("[%s](%s)", d.label(), "Webrev is not available because diff is too large") :
                        String.format("[%s](%s)", d.label(), d.uri()))
                .collect(Collectors.joining(" - "));
        var comment = WEBREV_COMMENT_MARKER + "\n";
        comment += webrevHeaderMarker + "\n";
        comment += "### Webrevs" + "\n";
        comment += webrevListMarker + "\n";
        comment += " * " + String.format("%02d", index) + ": " + webrevDescriptions;
        comment += " ([" + pr.headHash().abbreviate() + "](" + pr.filesUrl(pr.headHash()) + "))\n";

        if (existing.isPresent()) {
            if (existing.get().body().contains(webrevDescriptions)) {
                log.fine("Webrev links already posted - skipping update");
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

    private EmailAddress getAuthorAddress(CensusInstance censusInstance, HostUser originalAuthor) {
        if (bot.ignoredUsers().contains(originalAuthor.username())) {
            return bot.emailAddress();
        }
        if (BridgedComment.isBridgedUser(originalAuthor)) {
            return EmailAddress.from(originalAuthor.fullName(), originalAuthor.email().orElseThrow());
        }

        var contributor = censusInstance.namespace().get(originalAuthor.id());
        if (contributor == null) {
            return EmailAddress.from(originalAuthor.fullName(), bot.emailAddress().address());
        } else {
            return EmailAddress.from(contributor.fullName().orElse(originalAuthor.fullName()),
                                     contributor.username() + "@" + censusInstance.configuration().census().domain());
        }
    }

    private String getAuthorUsername(CensusInstance censusInstance, HostUser originalAuthor) {
        var contributor = censusInstance.namespace().get(originalAuthor.id());
        var username = contributor != null ? contributor.username() : originalAuthor.username() + "@" + censusInstance.namespace().name();
        return username;
    }

    private String getAuthorRole(CensusInstance censusInstance, HostUser originalAuthor) {
        var version = censusInstance.configuration().census().version();
        var contributor = censusInstance.namespace().get(originalAuthor.id());
        if (contributor == null) {
            return "no known OpenJDK username";
        } else if (censusInstance.project().isLead(contributor.username(), version)) {
            return "Lead";
        } else if (censusInstance.project().isReviewer(contributor.username(), version)) {
            return "Reviewer";
        } else if (censusInstance.project().isCommitter(contributor.username(), version)) {
            return "Committer";
        } else if (censusInstance.project().isAuthor(contributor.username(), version)) {
            return "Author";
        }
        return "no project role";
    }

    private String subjectPrefix() {
        var ret = new StringBuilder();
        var branchName = pr.targetRef();
        var repoName = Path.of(pr.repository().name()).getFileName().toString();
        var useBranchInSubject = bot.branchInSubject().matcher(branchName).matches();
        var useRepoInSubject = bot.repoInSubject();

        if (useBranchInSubject || useRepoInSubject) {
            ret.append("[");
            if (useRepoInSubject) {
                ret.append(repoName);
                if (useBranchInSubject) {
                    ret.append(":");
                }
            }
            if (useBranchInSubject) {
                ret.append(branchName);
            }
            ret.append("] ");
        }
        return ret.toString();
    }

    private String mboxFile() {
        return bot.codeRepo().name() + "/" + pr.id() + ".mbox";
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var path = scratchPath.resolve("mlbridge");

        var sentMails = new ArrayList<Email>();
        // Load in already sent emails from the archive, if there are any.
        var archiveContents = bot.archiveRepo().fileContents(mboxFile(), bot.archiveRef());
        archiveContents.ifPresent(s -> sentMails.addAll(Mbox.splitMbox(s, bot.emailAddress())));

        var labels = new HashSet<>(pr.labelNames());

        // First determine if this PR should be inspected further or not
        if (sentMails.isEmpty()) {
            if (pr.state() == Issue.State.OPEN) {
                for (var readyLabel : bot.readyLabels()) {
                    if (!labels.contains(readyLabel)) {
                        log.fine("PR is not yet ready - missing label '" + readyLabel + "'");
                        return List.of();
                    }
                }
            } else {
                if (!labels.contains("integrated")) {
                    log.fine("Closed PR was not integrated - will not initiate an RFR thread");
                    return List.of();
                }
            }
        }

        // If the PR is closed and the target ref no longer exists, we cannot process it
        if (pr.isClosed()) {
            if (pr.repository().branches().stream().noneMatch(n -> n.name().equals(pr.targetRef()))) {
                log.warning("Target branch of PR '" + pr.targetRef() + "' no longer exists, cannot process further");
                return List.of();
            }
        }

        // Also inspect comments before making the first post
        var comments = pr.comments();
        if (sentMails.isEmpty()) {
            for (var readyComment : bot.readyComments().entrySet()) {
                var commentFound = false;
                for (var comment : comments) {
                    if (comment.author().username().equals(readyComment.getKey())) {
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
                    return List.of();
                }
            }
        }

        // Determine recipient list(s)
        var recipients = new ArrayList<EmailAddress>();
        for (var candidateList : bot.lists()) {
            if (candidateList.labels().isEmpty()) {
                recipients.add(candidateList.list());
                continue;
            }
            for (var label : labels) {
                if (candidateList.labels().contains(label)) {
                    recipients.add(candidateList.list());
                    break;
                }
            }
        }
        if (recipients.isEmpty()) {
            log.fine("PR does not match any recipient list: " + pr.repository().name() + "#" + pr.id());
            return List.of();
        }

        var census = CensusInstance.create(bot.censusRepo(), bot.censusRef(), scratchPath.resolve("census"), pr);
        var jbs = census.configuration().general().jbs();
        if (jbs == null) {
            jbs = census.configuration().general().project();
        }

        // Materialize the PR's target ref
        try {
            // Materialize the PR's source and target ref
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepoPath = scratchPath.resolve("mlbridge-mergebase").resolve(pr.repository().name());
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, localRepoPath);

            var jsonWebrevPath = scratchPath.resolve("mlbridge-webrevs").resolve("json");
            var htmlWebrevPath = scratchPath.resolve("mlbridge-webrevs").resolve("html");
            var listServer = MailingListServerFactory.createMailmanServer(bot.listArchive(), bot.smtpServer(), bot.sendInterval());
            var archiver = new ReviewArchive(pr, bot.emailAddress());
            var lastDraftTime = pr.lastMarkedAsDraftTime().orElse(null);

            // Regular comments
            for (var comment : comments) {
                if (ignoreComment(comment.author(), comment.body(), comment.createdAt(), lastDraftTime, true)) {
                    archiver.addIgnored(comment);
                } else {
                    archiver.addComment(comment);
                }
            }

            // Review comments
            var reviews = pr.reviews();
            for (var review : reviews) {
                if (ignoreComment(review.reviewer(), review.body().orElse(""), review.createdAt(), lastDraftTime, false)) {
                    continue;
                }
                archiver.addReview(review);
            }

            // File specific comments
            var reviewComments = pr.reviewComments().stream()
                                   .sorted(Comparator.comparing(ReviewComment::line))
                                   .sorted(Comparator.comparing(ReviewComment::path))
                                   .collect(Collectors.toList());
            for (var reviewComment : reviewComments) {
                if (ignoreComment(reviewComment.author(), reviewComment.body(), reviewComment.createdAt(), lastDraftTime, true)) {
                    continue;
                }
                archiver.addReviewComment(reviewComment);
            }

            var webrevGenerator = bot.webrevStorage().generator(pr, localRepo, jsonWebrevPath, htmlWebrevPath, hostedRepositoryPool);
            var newMails = archiver.generateNewEmails(sentMails, bot.cooldown(), localRepo, bot.issueTracker(), jbs.toUpperCase(), webrevGenerator,
                                                      (index, webrevs) -> updateWebrevComment(comments, index, webrevs),
                                                      user -> getAuthorAddress(census, user),
                                                      user -> getAuthorUsername(census, user),
                                                      user -> getAuthorRole(census, user),
                                                      subjectPrefix(),
                                                      retryConsumer
                                                      );
            if (newMails.isEmpty()) {
                return List.of();
            }

            // Push all new mails to the archive repository
            var newArchivedContents = new StringBuilder();
            archiveContents.ifPresent(newArchivedContents::append);
            for (var newMail : newMails) {
                var forArchiving = Email.from(newMail)
                                        .recipient(EmailAddress.from(pr.id() + "@mbox"))
                                        .build();
                newArchivedContents.append(Mbox.fromMail(forArchiving));
            }
            bot.archiveRepo().writeFileContents(mboxFile(), newArchivedContents.toString(), new Branch(bot.archiveRef()),
                    "Adding comments for PR " + bot.codeRepo().name() + "/" + pr.id(),
                    bot.emailAddress().fullName().orElseThrow(), bot.emailAddress().address(), archiveContents.isEmpty());

            // Finally post all new mails to the actual list
            for (var newMail : newMails) {
                var filteredHeaders = newMail.headers().stream()
                                             .filter(header -> !header.startsWith("PR-"))
                                             .collect(Collectors.toMap(Function.identity(),
                                                                       newMail::headerValue));
                var filteredEmail = Email.from(newMail)
                                         .replaceHeaders(filteredHeaders)
                                         .headers(bot.headers())
                                         .recipients(recipients)
                                         .build();
                listServer.post(filteredEmail);
            }
            // Mixing forge time and local time for the latency is not ideal, but the best
            // we can do here.
            var latency = Duration.between(pr.updatedAt(), ZonedDateTime.now());
            log.log(Level.INFO, "Time from PR updated to emails sent " + latency, latency);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        exceptionConsumer.accept(e);
    }

    @Override
    public String botName() {
        return MailingListBridgeBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "archive";
    }
}
