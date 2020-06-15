package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;

class ReviewArchive {
    private final PullRequest pr;
    private final EmailAddress sender;

    private final List<Comment> comments = new ArrayList<>();
    private final List<Comment> ignoredComments = new ArrayList<>();
    private final List<Review> reviews = new ArrayList<>();
    private final List<ReviewComment> reviewComments = new ArrayList<>();

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    ReviewArchive(PullRequest pr, EmailAddress sender) {
        this.pr = pr;
        this.sender = sender;
    }

    void addComment(Comment comment) {
        comments.add(comment);
    }

    void addIgnored(Comment comment) {
        ignoredComments.add(comment);
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

    private final static Pattern pushedPattern = Pattern.compile("Pushed as commit ([a-f0-9]{40})\\.");

    private Optional<Hash> findIntegratedHash() {
        return ignoredComments.stream()
                              .map(Comment::body)
                              .map(pushedPattern::matcher)
                              .filter(Matcher::find)
                              .map(m -> m.group(1))
                              .map(Hash::new)
                              .findAny();
    }

    private boolean hasLegacyIntegrationNotice(Repository localRepo, Commit commit) {
        // Commits before this date are assumed to have been serviced by the old PR notifier
        return commit.date().isBefore(ZonedDateTime.of(2020, 4, 28, 14, 0, 0, 0, ZoneId.of("UTC")));
    }

    private List<ArchiveItem> generateArchiveItems(List<Email> sentEmails, Repository localRepo, URI issueTracker, String issuePrefix, HostUserToEmailAuthor hostUserToEmailAuthor, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole, WebrevStorage.WebrevGenerator webrevGenerator, WebrevNotification webrevNotification, String subjectPrefix) throws IOException {
        var generated = new ArrayList<ArchiveItem>();
        Hash lastBase = null;
        Hash lastHead = null;
        int revisionIndex = 0;
        var threadPrefix = "RFR";

        if (!sentEmails.isEmpty()) {
            var first = sentEmails.get(0);
            if (first.hasHeader("PR-Thread-Prefix")) {
                threadPrefix = first.headerValue("PR-Thread-Prefix");
            }
        } else {
            if (pr.state() != Issue.State.OPEN) {
                threadPrefix = "Integrated";
            }
        }

        // Check existing generated mails to find which hashes have been previously reported
        for (var email : sentEmails) {
            if (email.hasHeader("PR-Base-Hash")) {
                var curBase = new Hash(email.headerValue("PR-Base-Hash"));
                var curHead = new Hash(email.headerValue("PR-Head-Hash"));
                var created = email.date();

                if (generated.isEmpty()) {
                    var first = ArchiveItem.from(pr, localRepo, hostUserToEmailAuthor, issueTracker, issuePrefix, webrevGenerator, webrevNotification, pr.createdAt(), pr.updatedAt(), curBase, curHead, subjectPrefix, threadPrefix);
                    generated.add(first);
                } else {
                    var revision = ArchiveItem.from(pr, localRepo, hostUserToEmailAuthor, webrevGenerator, webrevNotification, created, created, lastBase, lastHead, curBase, curHead, ++revisionIndex, generated.get(0), subjectPrefix, threadPrefix);
                    generated.add(revision);
                }

                lastBase = curBase;
                lastHead = curHead;
            }
        }

        // Check if we're at a revision not previously reported
        var baseHash = PullRequestUtils.baseHash(pr, localRepo);
        if (!baseHash.equals(lastBase) || !pr.headHash().equals(lastHead)) {
            if (generated.isEmpty()) {
                var first = ArchiveItem.from(pr, localRepo, hostUserToEmailAuthor, issueTracker, issuePrefix, webrevGenerator, webrevNotification, pr.createdAt(), pr.updatedAt(), baseHash, pr.headHash(), subjectPrefix, threadPrefix);
                generated.add(first);
            } else {
                var revision = ArchiveItem.from(pr, localRepo, hostUserToEmailAuthor, webrevGenerator, webrevNotification, pr.updatedAt(), pr.updatedAt(), lastBase, lastHead, baseHash, pr.headHash(), ++revisionIndex, generated.get(0), subjectPrefix, threadPrefix);
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

        // Post a closed notice for regular RFR threads that weren't integrated
        if (pr.state() != Issue.State.OPEN) {
            var parent = generated.get(0);
            if (pr.labels().contains("integrated")) {
                var hash = findIntegratedHash();
                if (hash.isPresent()) {
                    var commit = localRepo.lookup(hash.get());
                    if (!hasLegacyIntegrationNotice(localRepo, commit.orElseThrow())) {
                        var reply = ArchiveItem.integratedNotice(pr, localRepo, commit.orElseThrow(), hostUserToEmailAuthor, parent, subjectPrefix);
                        generated.add(reply);
                    }
                } else {
                    throw new RuntimeException("PR " + pr.webUrl() + " has integrated label but no integration comment");
                }
            } else if (localRepo.isAncestor(pr.headHash(), pr.targetHash())) {
                var commit = localRepo.lookup(pr.headHash());
                var reply = ArchiveItem.integratedNotice(pr, localRepo, commit.orElseThrow(), hostUserToEmailAuthor, parent, subjectPrefix);
                generated.add(reply);
            } else if (threadPrefix.equals("RFR")) {
                var reply = ArchiveItem.closedNotice(pr, hostUserToEmailAuthor, parent, subjectPrefix);
                generated.add(reply);
            }
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

    private String parentAuthorPath(ArchiveItem item) {
        var ret = new StringBuilder();
        ret.append(item.author().id());
        while (item.parent().isPresent()) {
            item = item.parent().get();
            ret.append(".");
            ret.append(item.author().id());
        }
        return ret.toString();
    }

    // Group items that has the same author and the same parent
    private List<List<ArchiveItem>> collapsableItems(List<ArchiveItem> items) {
        var grouped = items.stream()
                           .collect(Collectors.groupingBy(this::parentAuthorPath,
                                                          LinkedHashMap::new, Collectors.toList()));
        return new ArrayList<>(grouped.values());
    }

    private String quoteBody(String body) {
        return Arrays.stream(body.strip().split("\\R"))
                     .map(line -> line.length() > 0 ? line.charAt(0) == '>' ? ">" + line : "> " + line : "> ")
                     .collect(Collectors.joining("\n"));
    }

    private List<ArchiveItem> parentsToQuote(ArchiveItem item, int quoteLevel, Set<ArchiveItem> alreadyQuoted) {
        var ret = new ArrayList<ArchiveItem>();

        if (item.parent().isPresent() && quoteLevel > 0 && !alreadyQuoted.contains(item.parent().get())) {
            ret.add(item.parent().get());
            ret.addAll(parentsToQuote(item.parent().get(), quoteLevel - 1, alreadyQuoted));
        }

        return ret;
    }

    // Parents to quote are provided with the newest item first. If the item already has quoted
    // a parent, use that as the quote and return an empty string.
    private String quoteSelectedParents(List<ArchiveItem> parentsToQuote, ArchiveItem first) {
        if (parentsToQuote.isEmpty()) {
            return "";
        }
        if (ArchiveItem.containsQuote(first.body(), parentsToQuote.get(0).body())) {
            return "";
        }
        Collections.reverse(parentsToQuote);
        var ret = "";
        for (var parent : parentsToQuote) {
            if (!ret.isBlank()) {
                ret = quoteBody(ret) + "\n>\n" + quoteBody(parent.body());
            } else {
                ret = quoteBody(parent.body());
            }
        }
        return ret;
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

    List<Email> generateNewEmails(List<Email> sentEmails, Duration cooldown, Repository localRepo, URI issueTracker, String issuePrefix, WebrevStorage.WebrevGenerator webrevGenerator, WebrevNotification webrevNotification, HostUserToEmailAuthor hostUserToEmailAuthor, HostUserToUserName hostUserToUserName, HostUserToRole hostUserToRole, String subjectPrefix, Consumer<Instant> retryConsumer) throws IOException {
        var ret = new ArrayList<Email>();
        var allItems = generateArchiveItems(sentEmails, localRepo, issueTracker, issuePrefix, hostUserToEmailAuthor, hostUserToUserName, hostUserToRole, webrevGenerator, webrevNotification, subjectPrefix);
        var sentItemIds = sentItemIds(sentEmails);
        var unsentItems = allItems.stream()
                                  .filter(item -> !sentItemIds.contains(getStableMessageId(getUniqueMessageId(item.id()))))
                                  .collect(Collectors.toList());
        if (unsentItems.isEmpty()) {
            return ret;
        }
        var lastUpdate = unsentItems.stream()
                                    .map(ArchiveItem::updatedAt)
                                    .max(ZonedDateTime::compareTo).orElseThrow();
        var mayUpdate = lastUpdate.plus(cooldown);
        if (lastUpdate.plus(cooldown).isAfter(ZonedDateTime.now())) {
            log.info("Waiting for new content to settle down - last update was at " + lastUpdate);
            log.info("Retry again after " + mayUpdate);
            retryConsumer.accept(mayUpdate.toInstant());
            return ret;
        }

        var combinedItems = collapsableItems(unsentItems);
        for (var itemList : combinedItems) {
            var quotedParents = new HashSet<ArchiveItem>();

            // Simply combine all message bodies together with unique quotes
            var body = new StringBuilder();
            for (var item : itemList) {
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                var newQuotes = parentsToQuote(item, 2, quotedParents);
                var quote = quoteSelectedParents(newQuotes, item);
                if (!quote.isBlank()) {
                    body.append(quote);
                    body.append("\n\n");
                }
                quotedParents.addAll(newQuotes);
                body.append(item.body());
            }

            // For footers, we want to combine all unique fragments
            var footer = new StringBuilder();
            var includedFooterFragments = new HashSet<String>();
            for (var item : itemList) {
                var newFooterFragments = Stream.of(item.footer().split("\n\n"))
                                               .filter(line -> !includedFooterFragments.contains(line))
                                               .collect(Collectors.toList());
                footer.append(String.join("\n\n", newFooterFragments));
                includedFooterFragments.addAll(newFooterFragments);
            }

            // All items have parents from the same author after collapsing -> should have the same header
            var firstItem = itemList.get(0);
            var header = firstItem.header();

            var combined = (header.isBlank() ? "" : header +  "\n\n") +
                    WordWrap.wrapBody(body.toString(), 120) +
                    (footer.length() == 0 ? "" : "\n\n-------------\n\n" + footer.toString());

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
