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
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ArchiveWorkItem implements WorkItem {
    private final PullRequest pr;
    private final MailingListBridgeBot bot;
    private final Consumer<RuntimeException> exceptionConsumer;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    ArchiveWorkItem(PullRequest pr, MailingListBridgeBot bot, Consumer<RuntimeException> exceptionConsumer) {
        this.pr = pr;
        this.bot = bot;
        this.exceptionConsumer = exceptionConsumer;
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

    @Override
    public void run(Path scratchPath) {
        var path = scratchPath.resolve("mlbridge");
        var archiveRepo = materializeArchive(path);
        var mboxBasePath = path.resolve(bot.codeRepo().getName());
        var mbox = MailingListServerFactory.createMboxFileServer(mboxBasePath);
        var reviewArchiveList = mbox.getList(pr.getId());
        var sentMails = parseArchive(reviewArchiveList);

        // First determine if this PR should be inspected further or not
        if (sentMails.isEmpty()) {
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
        var prInstance = new PullRequestInstance(scratchPath.resolve("mlbridge-mergebase"), pr, bot.issueTracker(),
                                                 jbs.toUpperCase());
        var reviewArchive = new ReviewArchive(bot.emailAddress(), prInstance, census, sentMails);
        var webrevPath = scratchPath.resolve("mlbridge-webrevs");
        var listServer = MailingListServerFactory.createMailmanServer(bot.listArchive(), bot.smtpServer(), bot.sendInterval());
        var list = listServer.getList(bot.listAddress().address());

        // First post
        if (sentMails.isEmpty()) {
            log.fine("Creating new PR review archive");
            var webrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, prInstance.baseHash(),
                                                              prInstance.headHash(), "00");
            reviewArchive.create(webrev);
            updateWebrevComment(comments, 0, webrev, null);
        } else {
            var latestHead = reviewArchive.latestHead();

            // Check if the head has changed
            if (!pr.getHeadHash().equals(latestHead)) {
                log.fine("Head hash change detected: current: " + pr.getHeadHash() + " - last: " + latestHead);

                var latestBase = reviewArchive.latestBase();
                if (!prInstance.baseHash().equals(latestBase)) {
                    // FIXME: Could try harder to make an incremental
                    var fullWebrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, prInstance.baseHash(),
                                                                          prInstance.headHash(), String.format("%02d", reviewArchive.revisionCount()));
                    reviewArchive.addFull(fullWebrev);
                    updateWebrevComment(comments, reviewArchive.revisionCount(), fullWebrev, null);
                } else {
                    var index = reviewArchive.revisionCount();
                    var fullWebrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, prInstance.baseHash(),
                                                                          prInstance.headHash(), String.format("%02d", index));
                    var incrementalWebrev = bot.webrevStorage().createAndArchive(prInstance, webrevPath, latestHead,
                                                                                 prInstance.headHash(), String.format("%02d-%02d", index - 1, index));
                    reviewArchive.addIncremental(fullWebrev, incrementalWebrev);
                    updateWebrevComment(comments, index, fullWebrev, incrementalWebrev);
                }
            }
        }

        // Regular comments
        for (var comment : comments) {
            if (ignoreComment(comment.author(), comment.body())) {
                continue;
            }
            reviewArchive.addComment(comment);
        }

        // File specific comments
        var reviewComments = pr.getReviewComments();
        for (var reviewComment : reviewComments) {
            if (ignoreComment(reviewComment.author(), reviewComment.body())) {
                continue;
            }
            reviewArchive.addReviewComment(reviewComment);
        }

        // Review comments
        var reviews = pr.getReviews();
        for (var review : reviews) {
            if (ignoreComment(review.reviewer(), review.body().orElse(""))) {
                continue;
            }
            reviewArchive.addReview(review);
        }

        var newMails = reviewArchive.generatedEmails();
        if (newMails.isEmpty()) {
            return;
        }

        // Push all new mails to the archive repository
        newMails.forEach(reviewArchiveList::post);
        pushMbox(archiveRepo, "Adding comments for PR " + bot.codeRepo().getName() + "/" + pr.getId());

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
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        exceptionConsumer.accept(e);
    }
}
