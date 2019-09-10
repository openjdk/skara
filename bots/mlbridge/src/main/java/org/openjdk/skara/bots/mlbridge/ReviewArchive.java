package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.email.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.stream.*;

class ReviewArchive {
    private final PullRequestInstance prInstance;
    private final EmailAddress sender;
    private final String nameDecoration;
    private final List<Email> existing;
    private final Map<String, Email> existingIds = new HashMap<>();
    private final List<Email> generated = new ArrayList<>();
    private final Map<String, Email> generatedIds = new HashMap<>();
    private final List<Hash> reportedHeads;
    private final List<Hash> reportedBases;

    private EmailAddress getAuthorAddress(HostUserDetails originalAuthor) {
        return EmailAddress.from(originalAuthor.fullName() + nameDecoration,
                                 sender.address());
    }

    private EmailAddress getUniqueMessageId(String identifier) {
        try {
            var prSpecific = prInstance.pr().repository().getName().replace("/", ".") + "." + prInstance.id();
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(prSpecific.getBytes(StandardCharsets.UTF_8));
            digest.update(identifier.getBytes(StandardCharsets.UTF_8));
            var encodedCommon = Base64.getUrlEncoder().encodeToString(digest.digest());

            return EmailAddress.from(encodedCommon + "." + UUID.randomUUID() + "@" + prInstance.pr().repository().getUrl().getHost());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
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

    private EmailAddress getMessageId(Review review, boolean verdict) {
        if (verdict) {
            return getUniqueMessageId("rvvd" + review.id());
        } else {
            return getUniqueMessageId("rv" + review.id());
        }
    }

    private String getStableMessageId(EmailAddress uniqueMessageId) {
        return uniqueMessageId.localPart().split("\\.")[0];
    }

    private Set<String> getStableMessageIds(Email email) {
        var ret = new HashSet<String>();
        ret.add(getStableMessageId(email.id()));
        if (email.hasHeader("PR-Collapsed-IDs")) {
            var additional = email.headerValue("PR-Collapsed-IDs").split(" ");
            ret.addAll(Arrays.asList(additional));
        }
        return ret;
    }

    private Email topEmail() {
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return generated.get(0);
    }

    // Returns a suitable parent to use for a general comment
    private Email latestGeneralComment() {
        return Stream.concat(existing.stream(), generated.stream())
                     .filter(email -> !email.hasHeader("PR-Head-Hash"))
                     .filter(email -> email.subject().startsWith("Re: RFR"))
                     .max(Comparator.comparingInt(email -> Integer.parseInt(email.headerValue("PR-Sequence"))))
                     .orElse(topEmail());
    }

    // Returns the top-level comment for a certain head hash
    private Email topCommentForHash(Hash hash) {
        return Stream.concat(existing.stream(), generated.stream())
                     .filter(email -> email.hasHeader("PR-Head-Hash"))
                     .filter(email -> email.headerValue("PR-Head-Hash").equals(hash.hex()))
                     .findFirst()
                     .orElse(topEmail());
    }

    private Email parentForReviewComment(ReviewComment reviewComment) {
        var parent = topCommentForHash(reviewComment.hash());
        if (reviewComment.parent().isPresent()) {
            var parentId = getStableMessageId(getMessageId(reviewComment.parent().get()));
            var last = Stream.concat(existing.stream(), generated.stream())
                             .filter(email -> (email.hasHeader("References") && email.headerValue("References").contains(parentId)) ||
                                     (getStableMessageId(email.id()).equals(parentId)))
                             .max(Comparator.comparingInt(email -> Integer.parseInt(email.headerValue("PR-Sequence"))));

            if (last.isEmpty()) {
                throw  new RuntimeException("Failed to find parent");
            } else {
                return last.get();
            }
        }
        return parent;
    }

    ReviewArchive(EmailAddress sender, PullRequestInstance prInstance, List<Email> sentMails, String nameDecoration) {
        this.sender = sender;
        this.prInstance = prInstance;
        this.nameDecoration = nameDecoration;

        existing = sentMails;
        for (var email : existing) {
            var stableIds = getStableMessageIds(email);
            for (var stableId : stableIds) {
                existingIds.put(stableId, email);
            }
        }

        // Determine the latest hashes reported
        reportedHeads = existing.stream()
                                .filter(email -> email.hasHeader("PR-Head-Hash"))
                                .map(email -> email.headerValue("PR-Head-Hash"))
                                .map(Hash::new)
                                .collect(Collectors.toList());
        reportedBases = existing.stream()
                                .filter(email -> email.hasHeader("PR-Base-Hash"))
                                .map(email -> email.headerValue("PR-Base-Hash"))
                                .map(Hash::new)
                                .collect(Collectors.toList());
    }

    Hash latestHead() {
        if (reportedHeads.isEmpty()) {
            throw new IllegalArgumentException("No head reported yet");
        }
        return reportedHeads.get(reportedHeads.size() - 1);
    }

    Hash latestBase() {
        if (reportedBases.isEmpty()) {
            throw new IllegalArgumentException("No base reported yet");
        }
        return reportedBases.get(reportedBases.size() - 1);
    }

    int revisionCount() {
        return reportedHeads.size();
    }

    void create(URI webrev) {
        var body = ArchiveMessages.composeConversation(prInstance, webrev);
        var id = getMessageId();
        var email = Email.create("RFR: " + prInstance.pr().getTitle(), body)
                         .sender(sender)
                         .author(getAuthorAddress(prInstance.pr().getAuthor()))
                         .id(id)
                         .header("PR-Head-Hash", prInstance.headHash().hex())
                         .header("PR-Base-Hash", prInstance.baseHash().hex())
                         .build();
        generated.add(email);
        generatedIds.put(getStableMessageId(id), email);
    }

    private String latestHeadSubject() {
        try {
            var latestCommit = prInstance.localRepo().lookup(prInstance.headHash()).orElseThrow(RuntimeException::new);
            var firstLine = latestCommit.message().size() > 0 ? latestCommit.message().get(0) : prInstance.pr().getTitle();
            return String.format("%02d: %s", revisionCount(), firstLine);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void addFull(URI webrev) {
        var body = ArchiveMessages.composeRebaseComment(prInstance, webrev);
        var id = getMessageId(prInstance.headHash());
        var parent = topEmail();
        var email = Email.create(latestHeadSubject(), body)
                         .sender(sender)
                         .author(getAuthorAddress(prInstance.pr().getAuthor()))
                         .recipient(parent.author())
                         .id(id)
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", parent.id().toString())
                         .header("PR-Head-Hash", prInstance.headHash().hex())
                         .header("PR-Base-Hash", prInstance.baseHash().hex())
                         .header("PR-Sequence", Integer.toString(existing.size() + generated.size()))
                         .build();
        generated.add(email);
        generatedIds.put(getStableMessageId(id), email);
    }

    void addIncremental(URI fullWebrev, URI incrementalWebrev) {
        var body = ArchiveMessages.composeIncrementalComment(latestHead(), prInstance, fullWebrev, incrementalWebrev);
        var id = getMessageId(prInstance.headHash());
        var parent = topEmail();
        var email = Email.create(latestHeadSubject(), body)
                         .sender(sender)
                         .author(getAuthorAddress(prInstance.pr().getAuthor()))
                         .recipient(parent.author())
                         .id(id)
                         .header("In-Reply-To", parent.id().toString())
                         .header("References", parent.id().toString())
                         .header("PR-Head-Hash", prInstance.headHash().hex())
                         .header("PR-Base-Hash", prInstance.baseHash().hex())
                         .header("PR-Sequence", Integer.toString(existing.size() + generated.size()))
                         .build();
        generated.add(email);
        generatedIds.put(getStableMessageId(id), email);
    }

    private Optional<Email> findCollapsable(Email parent, HostUserDetails author) {
        var parentId = getStableMessageId(parent.id());

        // Is it a self-reply?
        if (parent.author().equals(getAuthorAddress(author)) && generatedIds.containsKey(parentId)) {
            return Optional.of(parent);
        }

        // Have we already replied to the same parent?
        for (var candidate : generated) {
            if (!candidate.hasHeader("In-Reply-To")) {
                continue;
            }
            var inReplyTo = EmailAddress.parse(candidate.headerValue("In-Reply-To"));
            var candidateParentId = getStableMessageId(inReplyTo);
            if (candidateParentId.equals(parentId) && candidate.author().equals(getAuthorAddress(author))) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    private void addReplyCommon(Email parent, HostUserDetails author, String subject, String body, EmailAddress id) {
        var references = parent.id().toString();
        if (parent.hasHeader("References")) {
            references = parent.headerValue("References") + " " + references;
        }

        // Collapse self-replies and replies-to-same that have been created in this run
        var collapsable = findCollapsable(parent, author);
        if (collapsable.isPresent()) {
            // Drop the parent
            var parentEmail = collapsable.get();
            generated.remove(parentEmail);
            generatedIds.remove(getStableMessageId(parentEmail.id()));

            var collapsed = parentEmail.hasHeader("PR-Collapsed-IDs") ? parentEmail.headerValue("PR-Collapsed-IDs") : "";
            collapsed += getStableMessageId(parentEmail.id());

            var reply = ArchiveMessages.composeCombinedReply(parentEmail, body, prInstance);
            var email = Email.from(parentEmail)
                             .body(reply)
                             .id(id)
                             .header("PR-Collapsed-IDs", collapsed)
                             .header("PR-Sequence", Integer.toString(existing.size() + generated.size()))
                             .build();
            generated.add(email);
            generatedIds.put(getStableMessageId(id), email);
        } else {
            var reply = ArchiveMessages.composeReply(parent, body, prInstance);
            var email = Email.create(subject, reply)
                             .sender(sender)
                             .author(getAuthorAddress(author))
                             .recipient(parent.author())
                             .id(id)
                             .header("In-Reply-To", parent.id().toString())
                             .header("References", references)
                             .header("PR-Sequence", Integer.toString(existing.size() + generated.size()))
                             .build();
            generated.add(email);
            generatedIds.put(getStableMessageId(id), email);
        }
    }

    void addComment(Comment comment) {
        var id = getMessageId(comment);
        if (existingIds.containsKey(getStableMessageId(id))) {
            return;
        }

        var parent = latestGeneralComment();
        addReplyCommon(parent, comment.author(), "Re: RFR: " + prInstance.pr().getTitle(), comment.body(), id);
    }

    private String projectRole(Contributor contributor, CensusInstance censusInstance) {
        var version = censusInstance.configuration().census().version();
        if (censusInstance.project().isLead(contributor.username(), version)) {
            return "Lead";
        } else if (censusInstance.project().isReviewer(contributor.username(), version)) {
            return "Reviewer";
        } else if (censusInstance.project().isCommitter(contributor.username(), version)) {
            return "Committer";
        } else if (censusInstance.project().isAuthor(contributor.username(), version)) {
            return "Author";
        }
        return "none";
    }

    void addReview(Review review, CensusInstance censusInstance) {
        var contributor = censusInstance.namespace().get(review.reviewer().id());

        // Post the review body as a regular comment
        if (review.body().isPresent()) {
            var id = getMessageId(review, false);
            if (!existingIds.containsKey(getStableMessageId(id))) {
                var parent = topCommentForHash(review.hash());
                var userName = contributor != null ? contributor.username() : review.reviewer().userName() + "@" + censusInstance.namespace().name();
                var userRole = contributor != null ? projectRole(contributor, censusInstance) : "none";
                var replyBody = ArchiveMessages.reviewCommentBody(review.body().get(), review.verdict(), userName, userRole);
                addReplyCommon(parent, review.reviewer(), "Re: RFR: " + prInstance.pr().getTitle(), replyBody, id);
            }
        }

        if (contributor != null) {
            var isReviewer = censusInstance.project().isReviewer(contributor.username(), censusInstance.configuration().census().version());
            if (review.verdict() == Review.Verdict.APPROVED && isReviewer) {
                var id = getMessageId(review, true);
                if (!existingIds.containsKey(getStableMessageId(id))) {
                    var parent = topEmail();
                    var replyBody = ArchiveMessages.reviewApprovalBodyReviewer(contributor.username());
                    addReplyCommon(parent, review.reviewer(), "Approved and Reviewed by " + contributor.username(), replyBody, id);
                }
            }
        }
    }

    void addReviewComment(ReviewComment reviewComment) {
        var id = getMessageId(reviewComment);
        if (existingIds.containsKey(getStableMessageId(id))) {
            return;
        }

        var parent = parentForReviewComment(reviewComment);
        var body = new StringBuilder();

        // Add some context to the first post
        if (reviewComment.parent().isEmpty()) {
            var contents = prInstance.pr().repository().getFileContents(reviewComment.path(), reviewComment.hash().hex()).lines().collect(Collectors.toList());

            body.append(reviewComment.path()).append(" line ").append(reviewComment.line()).append(":\n\n");
            for (int i = Math.max(0, reviewComment.line() - 2); i < Math.min(contents.size(), reviewComment.line() + 1); ++i) {
                body.append("> ").append(i + 1).append(": ").append(contents.get(i)).append("\n");
            }
            body.append("\n");
        }
        body.append(reviewComment.body());

        addReplyCommon(parent, reviewComment.author(), "Re: RFR: " + prInstance.pr().getTitle(), body.toString(), id);
    }

    List<Email> generatedEmails() {
        return generated;
    }
}
