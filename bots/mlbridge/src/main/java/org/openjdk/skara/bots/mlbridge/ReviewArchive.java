package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;

class ReviewArchive {
    private final PullRequest pr;
    private final EmailAddress sender;
    private final Hash base;
    private final Hash head;

    private final List<Comment> comments = new ArrayList<>();
    private final List<Review> reviews = new ArrayList<>();
    private final List<ReviewComment> reviewComments = new ArrayList<>();

    ReviewArchive(PullRequest pr, EmailAddress sender, Hash base, Hash head) {
        this.pr = pr;
        this.sender = sender;
        this.base = base;
        this.head = head;
    }

    void addComment(Comment comment) {
        comments.add(comment);
    }

    void addReview(Review review) {
        reviews.add(review);
    }

    void addReviewComment(ReviewComment reviewComment) {
        reviewComments.add(reviewComment);
    }

    // Searches for a previous reply to a certain parent by a specific author
    private Optional<ArchiveItem> findPreviousReplyBy(List<ArchiveItem> generated, HostUser author, ArchiveItem parent) {
        return generated.stream()
                        .filter(item -> item.author().equals(author))
                        .filter(item -> item.parent().isPresent())
                        .filter(item -> item.parent().get().equals(parent))
                        .findAny();
    }

    private List<ArchiveItem> generateArchiveItems(List<Email> sentEmails, Repository localRepo, URI issueTracker, String issuePrefix, HostUserToEmailAuthor hostUserToEmailAuthor, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole, WebrevStorage.WebrevGenerator webrevGenerator, WebrevNotification webrevNotification) {
        var generated = new ArrayList<ArchiveItem>();
        Hash lastBase = null;
        Hash lastHead = null;
        int revisionIndex = 0;

        // Check existing generated mails to find which hashes have been previously reported
        for (var email : sentEmails) {
            if (email.hasHeader("PR-Base-Hash")) {
                var curBase = new Hash(email.headerValue("PR-Base-Hash"));
                var curHead = new Hash(email.headerValue("PR-Head-Hash"));

                if (generated.isEmpty()) {
                    var first = ArchiveItem.from(pr, localRepo, issueTracker, issuePrefix, webrevGenerator, webrevNotification, curBase, curHead);
                    generated.add(first);
                } else {
                    var revision = ArchiveItem.from(pr, localRepo, webrevGenerator, webrevNotification, lastBase, lastHead, curBase, curHead, ++revisionIndex, generated.get(0));
                    generated.add(revision);
                }

                lastBase = curBase;
                lastHead = curHead;
            }
        }

        // Check if we're at a revision not previously reported
        if (!base.equals(lastBase) || !head.equals(lastHead)) {
            if (generated.isEmpty()) {
                var first = ArchiveItem.from(pr, localRepo, issueTracker, issuePrefix, webrevGenerator, webrevNotification, base, head);
                generated.add(first);
            } else {
                var revision = ArchiveItem.from(pr, localRepo, webrevGenerator, webrevNotification, lastBase, lastHead, base, head, ++revisionIndex, generated.get(0));
                generated.add(revision);
            }
        }

        // A review always have a revision mail as parent, so start with these
        for (var review : reviews) {
            var parent = ArchiveItem.findParent(generated, review);
            var reply = ArchiveItem.from(pr, review, hostUserToEmailAuthor, hostUserToUserName, hostUserToRole, parent);
            generated.add(reply);
        }
        // Comments have either a comment or a review as parent, the eligible ones have been generated at this point
        for (var comment : comments) {
            var parent = ArchiveItem.findParent(generated, comment);
            var reply = ArchiveItem.from(pr, comment, hostUserToEmailAuthor, parent);
            generated.add(reply);
        }
        // Finally, file specific comments should be seen after general review comments
        for (var reviewComment : reviewComments) {
            var parent = ArchiveItem.findParent(generated, reviewComments, reviewComment);
            var reply = ArchiveItem.from(pr, reviewComment, hostUserToEmailAuthor, parent);
            generated.add(reply);
        }

        return generated;
    }

    private Set<String> sentItemIds(List<Email> sentEmails) {
        var primary = sentEmails.stream()
                                .map(email -> getStableMessageId(email.id()));
        var collapsed = sentEmails.stream()
                                  .filter(email -> email.hasHeader("PR-Collapsed-IDs"))
                                  .flatMap(email -> Stream.of(email.headerValue("PR-Collapsed-IDs").split(" ")));
        return Stream.concat(primary, collapsed)
                     .collect(Collectors.toSet());
    }

    // Group items that has the same author and the same parent
    private List<List<ArchiveItem>> collapsableItems(List<ArchiveItem> items) {
        var grouped = items.stream()
                           .collect(Collectors.groupingBy(item -> item.author().id() + "." + (item.parent().isPresent() ? item.parent().get() : "xxx"),
                                                          LinkedHashMap::new, Collectors.toList()));
        return new ArrayList<>(grouped.values());
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

    private String quoteBody(String body) {
        return Arrays.stream(body.strip().split("\\R"))
                     .map(line -> line.length() > 0 ? line.charAt(0) == '>' ? ">" + line : "> " + line : "> ")
                     .collect(Collectors.joining("\n"));
    }

    private String quotedParent(ArchiveItem item, int quoteLevel) {
        if (item.parent().isPresent() && quoteLevel > 0) {
            var quotedParentBody = quotedParent(item.parent().get(), quoteLevel - 1);
            if (!quotedParentBody.isBlank()) {
                return quoteBody(quotedParentBody) + "\n> \n" + quoteBody(item.parent().get().body());
            } else {
                return quoteBody(item.parent().get().body());
            }
        }
        return "";
    }

    private Email findArchiveItemEmail(ArchiveItem item, List<Email> sentEmails, List<Email> newEmails) {
        var uniqueItemId = getUniqueMessageId(item.id());
        var stableItemId = getStableMessageId(uniqueItemId);
        return Stream.concat(sentEmails.stream(), newEmails.stream())
                     .filter(email -> getStableMessageId(email.id()).equals(stableItemId) ||
                             (email.hasHeader("PR-Collapsed-IDs") && email.headerValue("PR-Collapsed-IDs").contains(stableItemId)))
                     .findAny()
                     .orElseThrow();
    }

    private EmailAddress getUniqueMessageId(String identifier) {
        try {
            var prSpecific = pr.repository().name().replace("/", ".") + "." + pr.id();
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(prSpecific.getBytes(StandardCharsets.UTF_8));
            digest.update(identifier.getBytes(StandardCharsets.UTF_8));
            var encodedCommon = Base64.getUrlEncoder().encodeToString(digest.digest());

            return EmailAddress.from(encodedCommon + "." + UUID.randomUUID() + "@" + pr.repository().url().getHost());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    private String getStableMessageId(EmailAddress uniqueMessageId) {
        return uniqueMessageId.localPart().split("\\.")[0];
    }

    List<Email> generateNewEmails(List<Email> sentEmails, Repository localRepo, URI issueTracker, String issuePrefix, WebrevStorage.WebrevGenerator webrevGenerator, WebrevNotification webrevNotification, HostUserToEmailAuthor hostUserToEmailAuthor, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole) {
        var allItems = generateArchiveItems(sentEmails, localRepo, issueTracker, issuePrefix, hostUserToEmailAuthor, hostUserToUserName, hostUserToRole, webrevGenerator, webrevNotification);
        var sentItemIds = sentItemIds(sentEmails);
        var unsentItems = allItems.stream()
                                  .filter(item -> !sentItemIds.contains(getStableMessageId(getUniqueMessageId(item.id()))))
                                  .collect(Collectors.toList());

        var combinedItems = collapsableItems(unsentItems);
        var ret = new ArrayList<Email>();
        for (var itemList : combinedItems) {
            var body = new StringBuilder();
            for (var item : itemList) {
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append(item.body());
            }

            // All items have the same parent and author after collapsing -> should have the same header and footer
            var firstItem = itemList.get(0);
            var header = firstItem.header();
            var quote = quotedParent(firstItem, 2);
            if (!quote.isBlank()) {
                quote = quote + "\n\n";
            }
            var footer = firstItem.footer();

            var combined = (header.isBlank() ? "" : header +  "\n\n") + quote + body.toString() + (footer.isBlank() ? "" : "\n\n-------------\n\n" + footer);

            var emailBuilder = Email.create(firstItem.subject(), combined);
            if (firstItem.parent().isPresent()) {
                emailBuilder.reply(findArchiveItemEmail(firstItem.parent().get(), sentEmails, ret));
            }
            emailBuilder.sender(sender);
            emailBuilder.author(hostUserToEmailAuthor.author(firstItem.author()));
            emailBuilder.id(getUniqueMessageId(firstItem.id()));

            var collapsedItems = itemList.stream()
                                         .skip(1)
                                         .map(item -> getStableMessageId(getUniqueMessageId(item.id())))
                                         .collect(Collectors.toSet());
            if (collapsedItems.size() > 0) {
                emailBuilder.header("PR-Collapsed-IDs", String.join(" ", collapsedItems));
            }
            emailBuilder.headers(firstItem.extraHeaders());
            var email = emailBuilder.build();
            ret.add(email);
        }

        return ret;
    }
}
