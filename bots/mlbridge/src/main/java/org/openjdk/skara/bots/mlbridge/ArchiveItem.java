package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Supplier;
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
    private final Supplier<String> body;
    private final Supplier<String> footer;

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

    static ArchiveItem from(PullRequest pr, Repository localRepo, URI issueTracker, String issuePrefix,
                            WebrevStorage.WebrevGenerator webrevGenerator, WebrevNotification webrevNotification,
                            ZonedDateTime created, ZonedDateTime updated, Hash base, Hash head) {
        return new ArchiveItem(null, "fc", created, updated, pr.author(), Map.of("PR-Head-Hash", head.hex(), "PR-Base-Hash", base.hex()),
                               () -> "RFR: " + pr.title(),
                               () -> "",
                               () -> ArchiveMessages.composeConversation(pr, base, head),
                               () -> {
                                    var fullWebrev = webrevGenerator.generate(base, head, "00");
                                    webrevNotification.notify(0, fullWebrev, null);
                                    return ArchiveMessages.composeConversationFooter(pr, issueTracker, issuePrefix, localRepo, fullWebrev, base, head);
                               });
    }

    static ArchiveItem from(PullRequest pr, Repository localRepo, WebrevStorage.WebrevGenerator webrevGenerator,
                            WebrevNotification webrevNotification, ZonedDateTime created, ZonedDateTime updated,
                            Hash lastBase, Hash lastHead, Hash base, Hash head, int index, ArchiveItem parent) {
        return new ArchiveItem(parent,"ha" + head.hex(), created, updated, pr.author(), Map.of("PR-Head-Hash", head.hex(), "PR-Base-Hash", base.hex()),
                               () -> String.format("Re: [Rev %02d] RFR: %s", index, pr.title()),
                               () -> "",
                               () -> ArchiveMessages.composeRevision(pr, localRepo, base, head, lastBase, lastHead),
                               () -> {
                                    var fullWebrev = webrevGenerator.generate(base, head, String.format("%02d", index));
                                    if (lastBase.equals(base)) {
                                        var incrementalWebrev = webrevGenerator.generate(lastHead, head, String.format("%02d-%02d", index - 1, index));
                                        webrevNotification.notify(index, fullWebrev, incrementalWebrev);
                                        return ArchiveMessages.composeIncrementalFooter(pr, localRepo, fullWebrev, incrementalWebrev, head, lastHead);
                                    } else {
                                        // It may be possible to auto-rebase the last head onto the new base to get an incremental webrev
                                        try {
                                            localRepo.checkout(lastHead, true);
                                            localRepo.rebase(base, "duke", "duke@openjdk.org");
                                            var rebasedLastHead = localRepo.head();
                                            var incrementalWebrev = webrevGenerator.generate(rebasedLastHead, head, String.format("%02d-%02d", index - 1, index));
                                            webrevNotification.notify(index, fullWebrev, incrementalWebrev);
                                            return ArchiveMessages.composeIncrementalFooter(pr, localRepo, fullWebrev, incrementalWebrev, head, lastHead);
                                        } catch (IOException e) {
                                            // If it doesn't work out we just post a full webrev
                                            webrevNotification.notify(index, fullWebrev, null);
                                            return ArchiveMessages.composeRebaseFooter(pr, localRepo, fullWebrev, base, head);
                                        }
                                    }
                               });
    }

    static ArchiveItem from(PullRequest pr, Comment comment, HostUserToEmailAuthor hostUserToEmailAuthor, ArchiveItem parent) {
        return new ArchiveItem(parent, "pc" + comment.id(), comment.createdAt(), comment.updatedAt(), comment.author(), Map.of(),
                               () -> ArchiveMessages.composeReplySubject(parent.subject()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author)),
                               () -> ArchiveMessages.composeComment(comment),
                               () -> ArchiveMessages.composeReplyFooter(pr));
    }

    static ArchiveItem from(PullRequest pr, Review review, HostUserToEmailAuthor hostUserToEmailAuthor, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole, ArchiveItem parent) {
        return new ArchiveItem(parent, "rv" + review.id(), review.createdAt(), review.createdAt(), review.reviewer(), Map.of(),
                               () -> ArchiveMessages.composeReplySubject(parent.subject()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author())),
                               () -> ArchiveMessages.composeReview(pr, review, hostUserToUserName, hostUserToRole),
                               () -> ArchiveMessages.composeReviewFooter(pr, review, hostUserToUserName, hostUserToRole));
    }

    static ArchiveItem from(PullRequest pr, ReviewComment reviewComment, HostUserToEmailAuthor hostUserToEmailAuthor, ArchiveItem parent) {
        return new ArchiveItem(parent, "rc" + reviewComment.id(), reviewComment.createdAt(), reviewComment.updatedAt(), reviewComment.author(), Map.of(),
                               () -> ArchiveMessages.composeReplySubject(parent.subject()),
                               () -> ArchiveMessages.composeReplyHeader(parent.createdAt(), hostUserToEmailAuthor.author(parent.author())),
                               () -> ArchiveMessages.composeReviewComment(pr, reviewComment) ,
                               () -> ArchiveMessages.composeReplyFooter(pr));
    }

    static ArchiveItem findParent(List<ArchiveItem> generated, Comment comment) {
        ArchiveItem lastCommentOrReview = generated.get(0);
        for (var item : generated) {
            if (item.id().startsWith("pc") || item.id().startsWith("rv")) {
                if (item.createdAt().isBefore(comment.createdAt()) && item.createdAt().isAfter(lastCommentOrReview.createdAt())) {
                    lastCommentOrReview = item;
                }
            }
        }

        return lastCommentOrReview;
    }

    static ArchiveItem findRevisionItem(List<ArchiveItem> generated, Hash hash) {
        // Parent is revision update mail with the hash
        ArchiveItem lastRevisionItem = generated.get(0);
        for (var item : generated) {
            if (item.id().startsWith("ha")) {
                lastRevisionItem = item;
            }
            if (item.id().equals("ha" + hash.hex())) {
                return item;
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
        return findRevisionItem(generated, review.hash());
    }

    static ArchiveItem findParent(List<ArchiveItem> generated, List<ReviewComment> reviewComments, ReviewComment reviewComment) {
        // Parent is previous in thread or the revision update mail with the hash

        var threadId = reviewComment.threadId();
        var reviewThread = reviewComments.stream()
                                         .filter(comment -> comment.threadId().equals(threadId))
                                         .collect(Collectors.toList());
        ReviewComment previousComment = null;
        for (var threadComment : reviewThread) {
            if (threadComment.equals(reviewComment)) {
                break;
            }
            previousComment = threadComment;
        }

        if (previousComment == null) {
            return findRevisionItem(generated, reviewComment.hash());
        } else {
            return findReviewCommentItem(generated, previousComment);
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
        return header.get();
    }

    String body() {
        return body.get();
    }

    String footer() {
        return footer.get();
    }
}
