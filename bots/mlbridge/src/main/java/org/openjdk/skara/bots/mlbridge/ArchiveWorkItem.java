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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;

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
        if (!(other instanceof ArchiveWorkItem)) {
            return true;
        }
        ArchiveWorkItem otherItem = (ArchiveWorkItem)other;
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!bot.codeRepo().name().equals(otherItem.bot.codeRepo().name())) {
            return true;
        }
        return false;
    }

    private void pushMbox(Repository localRepo, String message) {
        try {
            localRepo.add(localRepo.root().resolve("."));
            var hash = localRepo.commit(message, bot.emailAddress().fullName().orElseThrow(), bot.emailAddress().address());
            localRepo.push(hash, bot.archiveRepo().url(), bot.archiveRef());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Repository materializeArchive(Path scratchPath) {
        try {
            return Repository.materialize(scratchPath, bot.archiveRepo().url(),
                                          "+" + bot.archiveRef() + ":mlbridge_archive");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final static Pattern commandPattern = Pattern.compile("^/.*$");

    private boolean ignoreComment(HostUser author, String body) {
        if (pr.repository().forge().currentUser().equals(author)) {
            return true;
        }
        if (bot.ignoredUsers().contains(author.userName())) {
            return true;
        }
        var commandMatcher = commandPattern.matcher(body);
        if (commandMatcher.matches()) {
            return true;
        }
        for (var ignoredCommentPattern : bot.ignoredComments()) {
            var ignoredCommentMatcher = ignoredCommentPattern.matcher(body);
            if (ignoredCommentMatcher.find()) {
                return true;
            }
        }
        return false;
    }

    private static final String webrevCommentMarker = "<!-- mlbridge webrev comment -->";
    private static final String webrevHeaderMarker = "<!-- mlbridge webrev header -->";
    private static final String webrevListMarker = "<!-- mlbridge webrev list -->";

    private void updateWebrevComment(List<Comment> comments, int index, URI fullWebrev, URI incWebrev) {
        var existing = comments.stream()
                               .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
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
        comment += " (" + pr.headHash() + ")\n";

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

    private EmailAddress getAuthorAddress(CensusInstance censusInstance, HostUser originalAuthor) {
        var contributor = censusInstance.namespace().get(originalAuthor.id());
        if (contributor == null) {
            return EmailAddress.from(originalAuthor.fullName(),
                                     censusInstance.namespace().name() + "+" +
                                             originalAuthor.id() + "+" + originalAuthor.userName() + "@" +
                                             censusInstance.configuration().census().domain());
        } else {
            return EmailAddress.from(contributor.fullName().orElse(originalAuthor.fullName()),
                                     contributor.username() + "@" + censusInstance.configuration().census().domain());
        }
    }

    private String getAuthorUserName(CensusInstance censusInstance, HostUser originalAuthor) {
        var contributor = censusInstance.namespace().get(originalAuthor.id());
        var userName = contributor != null ? contributor.username() : originalAuthor.userName() + "@" + censusInstance.namespace().name();
        return userName;
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
        var useRepoInSubject = bot.repoInSubject().matcher(repoName).matches();

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

    @Override
    public void run(Path scratchPath) {
        var path = scratchPath.resolve("mlbridge");
        var archiveRepo = materializeArchive(path);
        var mboxBasePath = path.resolve(bot.codeRepo().name());
        var mbox = MailingListServerFactory.createMboxFileServer(mboxBasePath);
        var reviewArchiveList = mbox.getList(pr.id());
        var sentMails = parseArchive(reviewArchiveList);

        // First determine if this PR should be inspected further or not
        if (sentMails.isEmpty()) {
            var labels = new HashSet<>(pr.labels());
            for (var readyLabel : bot.readyLabels()) {
                if (!labels.contains(readyLabel)) {
                    log.fine("PR is not yet ready - missing label '" + readyLabel + "'");
                    return;
                }
            }
        }

        // Also inspect comments before making the first post
        var comments = pr.comments();
        if (sentMails.isEmpty()) {
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
            var repository = pr.repository();
            var localRepoPath = scratchPath.resolve("mlbridge-mergebase");
            var localRepo = hostedRepositoryPool.checkout(pr, localRepoPath.resolve(repository.name()));
            localRepo.fetch(repository.url(), "+" + pr.targetRef() + ":mlbridge_prinstance");

            var targetHash = pr.targetHash();
            var headHash = pr.headHash();
            var baseHash = localRepo.mergeBase(targetHash, headHash);

            var webrevPath = scratchPath.resolve("mlbridge-webrevs");
            var listServer = MailingListServerFactory.createMailmanServer(bot.listArchive(), bot.smtpServer(), bot.sendInterval());
            var list = listServer.getList(bot.listAddress().address());

            var archiver = new ReviewArchive(pr, bot.emailAddress(), baseHash, headHash);

            // Regular comments
            for (var comment : comments) {
                if (ignoreComment(comment.author(), comment.body())) {
                    continue;
                }
                archiver.addComment(comment);
            }

            // Review comments
            var reviews = pr.reviews();
            for (var review : reviews) {
                if (ignoreComment(review.reviewer(), review.body().orElse(""))) {
                    continue;
                }
                archiver.addReview(review);
            }

            // File specific comments
            var reviewComments = pr.reviewComments();
            for (var reviewComment : reviewComments) {
                if (ignoreComment(reviewComment.author(), reviewComment.body())) {
                    continue;
                }
                archiver.addReviewComment(reviewComment);
            }

            var webrevGenerator = bot.webrevStorage().generator(pr, localRepo, webrevPath);
            var newMails = archiver.generateNewEmails(sentMails, bot.cooldown(), localRepo, bot.issueTracker(), jbs.toUpperCase(), webrevGenerator,
                                                      (index, full, inc) -> updateWebrevComment(comments, index, full, inc),
                                                      user -> getAuthorAddress(census, user),
                                                      user -> getAuthorUserName(census, user),
                                                      user -> getAuthorRole(census, user),
                                                      subjectPrefix(),
                                                      retryConsumer
                                                      );
            if (newMails.isEmpty()) {
                return;
            }

            // Push all new mails to the archive repository
            newMails.forEach(reviewArchiveList::post);
            pushMbox(archiveRepo, "Adding comments for PR " + bot.codeRepo().name() + "/" + pr.id());

            // Finally post all new mails to the actual list
            for (var newMail : newMails) {
                var filteredHeaders = newMail.headers().stream()
                                             .filter(header -> !header.startsWith("PR-"))
                                             .collect(Collectors.toMap(Function.identity(),
                                                                       newMail::headerValue));
                var filteredEmail = Email.from(newMail)
                                         .replaceHeaders(filteredHeaders)
                                         .headers(bot.headers())
                                         .build();
                list.post(filteredEmail);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        exceptionConsumer.accept(e);
    }
}
