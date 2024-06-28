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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class ArchiveItem {
    private final String id;
    private final ZonedDateTime created;
    private final ZonedDateTime updated;
    private final HostUser author;
    private final Map<String, String> extraHeaders;
    private final ArchiveItem parent;
    private final Supplier<String> subject;
    private final Supplier<String> header;
    private String resolvedHeader;
    private final Supplier<String> body;
    private String resolvedBody;
    private final Supplier<String> footer;
    private String resolvedFooter;

    private ArchiveItem(ArchiveItem parent, String id, ZonedDateTime created, ZonedDateTime updated, HostUser author, Map<String, String> extraHeaders, Supplier<String> subject, Supplier<String> header, Supplier<String> body, Supplier<String> footer) {
        this.id = id;
        this.created = created;
        this.updated = updated;
        this.author = author;
        this.extraHeaders = extraHeaders;
        this.parent = parent;
        this.subject = subject;
        this.header = header;
        this.body = body;
        this.footer = footer;
    }

    private static Optional<Commit> mergeCommit(PullRequest pr, Repository localRepo, Hash head) {
        try {
            var author = new Author("duke", "duke@openjdk.org");
            var hash = PullRequestUtils.createCommit(pr, localRepo, head, author, author, pr.title());
            return localRepo.lookup(hash);
        } catch (IOException | CommitFailure e) {
            return Optional.empty();
        }
    }

    private static Optional<Commit> conflictCommit(PullRequest pr, Repository localRepo, Hash head) {
        try {
            localRepo.checkout(head, true);
        } catch (IOException e) {
            return Optional.empty();
        }

        try {
            localRepo.merge(PullRequestUtils.targetHash(localRepo));
            // No problem means no conflict
            return Optional.empty();
        } catch (IOException e) {
            try {
                var status = localRepo.status();
                var unmerged = status.stream()
                                     .filter(entry -> entry.status().isUnmerged())
                                     .map(entry -> entry.source().path())
                                     .filter(Optional::isPresent)
                                     .map(Optional::get)
                                     .collect(Collectors.toList());

                // Drop the successful merges from the stage
                localRepo.reset(head, false);
                // Add the unmerged files as-is (retaining the conflict markers)
                localRepo.add(unmerged);
                var hash = localRepo.commit("Conflicts in " + pr.title(), "duke", "duke@openjdk.org");
                localRepo.clean();
                return localRepo.lookup(hash);
            } catch (IOException ioException) {
                return Optional.empty();
            }
        }
    }

    static ArchiveItem from(PullRequest pr, Repository localRepo, HostUserToEmailAuthor hostUserToEmailAuthor,
                            URI issueTracker, String issuePrefix, WebrevStorage.WebrevGenerator webrevGenerator,
                            WebrevNotification webrevNotification, ZonedDateTime created, ZonedDateTime updated,
                            Hash base, Hash head, String subjectPrefix, String threadPrefix) {
        return new ArchiveItem(null, "fc", created, updated, pr.author(), Map.of("PR-Head-Hash", head.hex(),
                                                                                 "PR-Base-Hash", base.hex(),
                                                                                 "PR-Thread-Prefix", threadPrefix),
                               () -> subjectPrefix + threadPrefix + (threadPrefix.isEmpty() ? "" : ": ") + pr.title(),
                               () -> "",
                               () -> ArchiveMessages.composeConversation(pr),
                               () -> {
                                   if (PullRequestUtils.isMerge(pr)) {
                                       var mergeWebrevs = new ArrayList<WebrevDescription>();
                                       var conflictCommit = conflictCommit(pr, localRepo, head);
                                       conflictCommit.ifPresent(commit -> mergeWebrevs.add(
                                               webrevGenerator.generate(commit.parentDiffs().get(0), "00.conflicts", WebrevDescription.Type.MERGE_CONFLICT, pr.targetRef())));
                                       var mergeCommit = mergeCommit(pr, localRepo, head);
                                       if (mergeCommit.isPresent()) {
                                           for (int i = 0; i < mergeCommit.get().parentDiffs().size(); ++i) {
                                               var diff = mergeCommit.get().parentDiffs().get(i);
                                               if (diff.patches().size() == 0) {
                                                   continue;
                                               }
                                               switch (i) {
                                                   case 0:
                                                       mergeWebrevs.add(webrevGenerator.generate(diff, String.format("00.%d", i), WebrevDescription.Type.MERGE_TARGET, pr.targetRef()));
                                                       break;
                                                   case 1:
                                                       var mergeSource = pr.title().length() > 6 ? pr.title().substring(6) : null;
                                                       mergeWebrevs.add(webrevGenerator.generate(diff, String.format("00.%d", i), WebrevDescription.Type.MERGE_SOURCE, mergeSource));
                                                       break;
                                                   default:
                                                       mergeWebrevs.add(webrevGenerator.generate(diff, String.format("00.%d", i), WebrevDescription.Type.MERGE_SOURCE, null));
                                                       break;
                                               }
                                           }
                                           if (!mergeWebrevs.isEmpty()) {
                                               webrevNotification.notify(0, mergeWebrevs);
                                           }
                                       }
                                       return ArchiveMessages.composeMergeConversationFooter(pr, localRepo, mergeWebrevs, base, head);
                                   } else {
                                       var fullWebrev = webrevGenerator.generate(base, head, "00", WebrevDescription.Type.FULL);
                                       webrevNotification.notify(0, List.of(fullWebrev));
                                       return ArchiveMessages.composeConversationFooter(pr, issueTracker, issuePrefix, localRepo, fullWebrev, base, head);
                                   }
                               });
    }

    private static Optional<Hash> rebasedLastHead(Repository localRepo, Hash newBase, Hash lastHead) {
        try {
            localRepo.checkout(lastHead, true);
            localRepo.rebase(newBase, "duke", "duke@openjdk.org");
            var rebasedLastHead = localRepo.head();
            return Optional.of(rebasedLastHead);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Checks if lastHead is available in the local repository and tried to fetch it
     * if not.
     */
    private static boolean lastHeadAvailable(PullRequest pr, Repository localRepo, Hash lastHead, boolean tryFetch) {
        try {
            if (localRepo.resolve(lastHead.hex()).isPresent()) {
                return true;
            }
            if (tryFetch) {
                return localRepo.fetch(pr.repository().authenticatedUrl(), lastHead.hex(), false).isPresent();
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static String hostUserToCommitterName(HostUserToEmailAuthor hostUserToEmailAuthor, HostUser hostUser) {
        var email = hostUserToEmailAuthor.author(hostUser);
        if (email.fullName().isPresent()) {
            return email.fullName().get();
        } else {
            return hostUser.fullName();
        }
    }

    static ArchiveItem from(PullRequest pr, Repository localRepo, HostUserToEmailAuthor hostUserToEmailAuthor,
                            WebrevStorage.WebrevGenerator webrevGenerator, WebrevNotification webrevNotification,
                            ZonedDateTime created, ZonedDateTime updated, Hash lastBase, Hash lastHead, Hash base,
                            Hash head, int index, ArchiveItem parent, String subjectPrefix, String threadPrefix) {
        return new ArchiveItem(parent, "ha" + head.hex(), created, updated, pr.author(), Map.of("PR-Head-Hash", head.hex(), "PR-Base-Hash", base.hex()),
                               () -> String.format("Re: %s%s%s [v%d]", subjectPrefix, threadPrefix + (threadPrefix.isEmpty() ? "" : ": "), pr.title(), index + 1),
                               () -> "",
                               () -> {
                                   if (lastBase.equals(base)) {
                                       // Make sure lastHead is present in the local repo (if possible)
                                       lastHeadAvailable(pr, localRepo, lastHead, true);
                                       return ArchiveMessages.composeIncrementalRevision(pr, localRepo, hostUserToCommitterName(hostUserToEmailAuthor, pr.author()), head, lastHead, base);
                                   } else {
                                       var rebasedLastHead = rebasedLastHead(localRepo, base, lastHead);
                                       if (rebasedLastHead.isPresent()) {
                                           return ArchiveMessages.composeRebasedIncrementalRevision(pr, localRepo, hostUserToCommitterName(hostUserToEmailAuthor, pr.author()), head, rebasedLastHead.get());
                                       } else {
                                           return ArchiveMessages.composeFullRevision(pr, localRepo, hostUserToCommitterName(hostUserToEmailAuthor, pr.author()), base, head);
                                       }
                                   }
                               },
                               () -> {
                                   var fullWebrev = webrevGenerator.generate(base, head, String.format("%02d", index), WebrevDescription.Type.FULL);
                                   if (lastBase.equals(base)) {
                                       if (lastHeadAvailable(pr, localRepo, lastHead, false)) {
                                           var incrementalWebrev = webrevGenerator.generate(lastHead, head, String.format("%02d-%02d", index - 1, index), WebrevDescription.Type.INCREMENTAL);
                                           webrevNotification.notify(index, List.of(fullWebrev, incrementalWebrev));
                                           return ArchiveMessages.composeIncrementalFooter(pr, localRepo, fullWebrev, incrementalWebrev, head, lastHead);
                                       } else {
                                           webrevNotification.notify(index, List.of(fullWebrev));
                                           return ArchiveMessages.composeRebasedFooter(pr, localRepo, fullWebrev, base, head);
                                       }
                                   } else {
                                       var rebasedLastHead = rebasedLastHead(localRepo, base, lastHead);
                                       if (rebasedLastHead.isPresent()) {
                                           var incrementalWebrev = webrevGenerator.generate(rebasedLastHead.get(), head, String.format("%02d-%02d", index - 1, index), WebrevDescription.Type.INCREMENTAL);
                                           webrevNotification.notify(index, List.of(fullWebrev, incrementalWebrev));
                                           return ArchiveMessages.composeIncrementalFooter(pr, localRepo, fullWebrev, incrementalWebrev, head, lastHead);
                                       } else {
                                           webrevNotification.notify(index, List.of(fullWebrev));
                                           return ArchiveMessages.composeRebasedFooter(pr, localRepo, fullWebrev, base, head);
                                       }
                                   }
                               });
    }

    static ArchiveItem from(PullRequest pr, Comment comment, HostUserToEmailAuthor hostUserToEmailAuthor, ArchiveItem parent) {
        return new ArchiveItem(parent, "pc" + comment.id(), comment.createdAt(), comment.updatedAt(), comment.author(), Map.of(),
                               () -> ArchiveMessages.composeReplySubject(parent.subject()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author)),
                               () -> ArchiveMessages.composeComment(comment),
                               () -> ArchiveMessages.composeCommentReplyFooter(pr, comment));
    }

    static ArchiveItem from(PullRequest pr, Review review, HostUserToEmailAuthor hostUserToEmailAuthor, HostUserToUsername hostUserToUsername, HostUserToRole hostUserToRole, ArchiveItem parent) {
        return new ArchiveItem(parent, "rv" + review.id(), review.createdAt(), review.createdAt(), review.reviewer(), Map.of(),
                               () -> ArchiveMessages.composeReplySubject(parent.subject()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author())),
                               () -> ArchiveMessages.composeReview(pr, review, hostUserToUsername, hostUserToRole),
                               () -> ArchiveMessages.composeReviewFooter(pr, review, hostUserToUsername, hostUserToRole));
    }

    static ArchiveItem from(PullRequest pr, ReviewComment reviewComment, HostUserToEmailAuthor hostUserToEmailAuthor, ArchiveItem parent) {
        return new ArchiveItem(parent, "rc" + reviewComment.id(), reviewComment.createdAt(), reviewComment.updatedAt(), reviewComment.author(), Map.of(),
                               () -> ArchiveMessages.composeReplySubject(parent.subject()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author())),
                               () -> ArchiveMessages.composeReviewComment(pr, reviewComment),
                               () -> ArchiveMessages.composeReviewCommentReplyFooter(pr, reviewComment));
    }

    static ArchiveItem closedNotice(PullRequest pr, HostUserToEmailAuthor hostUserToEmailAuthor, ArchiveItem parent, String subjectPrefix) {
        var closedBy = pr.closedBy().orElse(pr.author());
        return new ArchiveItem(parent, "cn", pr.updatedAt(), pr.updatedAt(), closedBy, Map.of("PR-Closed-Notice", "0"),
                               () -> String.format("%sWithdrawn: %s", subjectPrefix, pr.title()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author())),
                               () -> ArchiveMessages.composeClosedNotice(pr),
                               () -> ArchiveMessages.composeReplyFooter(pr));
    }

    static ArchiveItem integratedNotice(PullRequest pr, Repository localRepo, Commit commit, HostUserToEmailAuthor hostUserToEmailAuthor, ArchiveItem parent, String subjectPrefix) {
        return new ArchiveItem(parent, "in", pr.updatedAt(), pr.updatedAt(), pr.author(), Map.of("PR-Integrated-Notice", "0"),
                               () -> String.format("%sIntegrated: %s", subjectPrefix, pr.title()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author())),
                               () -> ArchiveMessages.composeIntegratedNotice(pr, localRepo, commit),
                               () -> ArchiveMessages.composeReplyFooter(pr));
    }

    private static final Pattern mentionPattern = Pattern.compile("@([\\w-]+)");

    private static Optional<ArchiveItem> findLastMention(String commentText, List<ArchiveItem> eligibleParents) {
        var firstLine = commentText.lines().findFirst();
        if (firstLine.isEmpty()) {
            return Optional.empty();
        }
        var mentionMatcher = mentionPattern.matcher(firstLine.get());
        if (mentionMatcher.find()) {
            var username = mentionMatcher.group(1);
            for (int i = eligibleParents.size() - 1; i >= 0; --i) {
                if (eligibleParents.get(i).author.username().equals(username)) {
                    return Optional.of(eligibleParents.get(i));
                }
            }
        }
        return Optional.empty();
    }

    static boolean containsQuote(String quote, String body) {
        var compactQuote = quote.lines()
                                .map(String::strip)
                                .filter(line -> !line.isBlank())
                                .takeWhile(line -> line.startsWith(">"))
                                .map(line -> line.replaceAll("\\W", ""))
                                .collect(Collectors.joining());
        if (!compactQuote.isBlank()) {
            var compactBody = body.replaceAll("\\W", "");
            return compactBody.contains(compactQuote);
        } else {
            return false;
        }
    }

    private static Optional<ArchiveItem> findLastQuoted(String commentText, List<ArchiveItem> eligibleParents) {
        for (int i = eligibleParents.size() - 1; i >= 0; --i) {
            if (containsQuote(commentText, eligibleParents.get(i).body())) {
                return Optional.of(eligibleParents.get(i));
            }
        }
        return Optional.empty();
    }

    static ArchiveItem findParent(List<ArchiveItem> generated, List<BridgedComment> bridgedComments, Comment comment) {
        var eligible = new ArrayList<ArchiveItem>();
        for (var item : generated) {
            if (item.id().startsWith("pc") || item.id().startsWith("rv")) {
                if (item.createdAt().isBefore(comment.createdAt())) {
                    eligible.add(item);
                }
            }
        }

        var lastMention = findLastMention(comment.body(), eligible);
        if (lastMention.isPresent()) {
            return lastMention.get();
        }

        // It is possible to quote a bridged comment when replying - make these eligible as well
        for (var bridged : bridgedComments) {
            var item = new ArchiveItem(generated.get(0), "br" + bridged.messageId().address(), bridged.created(), bridged.created(),
                                       bridged.author(), null, generated.get(0).subject, null, bridged::body, null);
            eligible.add(item);
        }

        var lastQuoted = findLastQuoted(comment.body(), eligible);
        if (lastQuoted.isPresent()) {
            return lastQuoted.get();
        }

        ArchiveItem lastRevisionItem = generated.get(0);
        for (var item : generated) {
            if (item.id().startsWith("ha")) {
                if (item.createdAt().isBefore(comment.createdAt())) {
                    lastRevisionItem = item;
                }
            }
        }
        return lastRevisionItem;
    }

    private static ArchiveItem findRevisionItem(List<ArchiveItem> generated, Hash hash) {
        // Parent is revision update mail with the hash
        ArchiveItem lastRevisionItem = generated.get(0);
        // If no hash is given, that means the commit for the review/comment no longer exists.
        // This means that no properly valid parent exists, but as we need to return one, just
        // return the first element.
        if (hash != null) {
            for (var item : generated) {
                if (item.id().startsWith("ha")) {
                    lastRevisionItem = item;
                }
                if (item.id().equals("ha" + hash.hex())) {
                    return item;
                }
            }
        }
        return lastRevisionItem;
    }

    static ArchiveItem findReviewCommentItem(List<ArchiveItem> generated, ReviewComment reviewComment) {
        for (var item : generated) {
            if (item.id().equals("rc" + reviewComment.id())) {
                return item;
            }
        }
        throw new RuntimeException("Failed to find review comment");
    }

    static ArchiveItem findParent(List<ArchiveItem> generated, Review review) {
        return findRevisionItem(generated, review.hash().orElse(null));
    }

    static ArchiveItem findParent(List<ArchiveItem> generated, List<ReviewComment> reviewComments, ReviewComment reviewComment) {
        // Parent is previous in thread or the revision update mail with the hash

        var threadId = reviewComment.threadId();
        var reviewThread = reviewComments.stream()
                                         .filter(comment -> comment.threadId().equals(threadId))
                                         .collect(Collectors.toList());
        ReviewComment previousComment = null;
        var eligible = new ArrayList<ArchiveItem>();
        for (var threadComment : reviewThread) {
            if (threadComment.equals(reviewComment)) {
                break;
            }
            previousComment = threadComment;
            eligible.add(findReviewCommentItem(generated, previousComment));
        }

        if (previousComment == null) {
            return findRevisionItem(generated, reviewComment.hash().orElse(null));
        } else {
            var mentionedParent = findLastMention(reviewComment.body(), eligible);
            if (mentionedParent.isPresent()) {
                return mentionedParent.get();
            } else {
                return eligible.getLast();
            }
        }
    }

    String id() {
        return id;
    }

    ZonedDateTime createdAt() {
        return created;
    }

    ZonedDateTime updatedAt() {
        return updated;
    }

    HostUser author() {
        return author;
    }

    Map<String, String> extraHeaders() {
        return extraHeaders;
    }

    Optional<ArchiveItem> parent() {
        return Optional.ofNullable(parent);
    }

    String subject() {
        return subject.get();
    }

    String header() {
        if (resolvedHeader == null) {
            resolvedHeader = header.get();
        }
        return resolvedHeader;
    }

    String body() {
        if (resolvedBody == null) {
            resolvedBody = body.get();
        }
        return resolvedBody;
    }

    String footer() {
        if (resolvedFooter == null) {
            resolvedFooter = footer.get();
        }
        return resolvedFooter;
    }

    @Override
    public String toString() {
        return "ArchiveItem From: " + author + " Body: " + body();
    }
}
