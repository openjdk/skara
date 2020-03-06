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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.mailinglist.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MailingListArchiveReaderBot implements Bot {
    private final EmailAddress archivePoster;
    private final Set<MailingList> lists;
    private final Set<HostedRepository> repositories;
    private final Map<EmailAddress, String> parsedConversations = new HashMap<>();
    private final Map<EmailAddress, PullRequest> resolvedPullRequests = new HashMap<>();
    private final Set<EmailAddress> parsedEmailIds = new HashSet<>();
    private final Queue<CommentPosterWorkItem> commentQueue = new ConcurrentLinkedQueue<>();
    private final Pattern pullRequestLinkPattern = Pattern.compile("^(?:PR: |Pull request:\\R)(.*?)$", Pattern.MULTILINE);
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    MailingListArchiveReaderBot(EmailAddress archivePoster, Set<MailingList> lists, Set<HostedRepository> repositories) {
        this.archivePoster = archivePoster;
        this.lists = lists;
        this.repositories = repositories;
    }

    private synchronized void invalidate(List<Email> messages) {
        messages.forEach(m -> parsedEmailIds.remove(m.id()));
    }

    synchronized void inspect(Conversation conversation) {
        // Is this a new conversation?
        var first = conversation.first();
        if (!parsedConversations.containsKey(first.id())) {
            // This conversation has already been parsed without finding any matching PR
            if (parsedEmailIds.contains(first.id())) {
                return;
            }

            parsedEmailIds.add(first.id());

            // Not an RFR - cannot match a PR
            if (!first.subject().contains("RFR: ")) {
                return;
            }

            // Look for a pull request link
            var matcher = pullRequestLinkPattern.matcher(first.body());
            if (!matcher.find()) {
                log.fine("RFR email without valid pull request link: " + first.date() + " - " + first.subject());
                return;
            }

            // Valid looking pull request link found!
            parsedConversations.put(first.id(), matcher.group(1));
            parsedEmailIds.remove(first.id());
        }

        // Are there any new messages?
        var newMessages = conversation.allMessages().stream()
                                      .filter(email -> !parsedEmailIds.contains(email.id()))
                                      .collect(Collectors.toList());
        if (newMessages.isEmpty()) {
            return;
        }

        for (var newMessage : newMessages) {
            parsedEmailIds.add(newMessage.id());
        }

        var pr = resolvedPullRequests.get(first.id());
        if (pr == null) {
            var prLink = parsedConversations.get(first.id());
            if (prLink.equals("invalid")) {
                return;
            }
            var foundPr = repositories.stream()
                                      .map(repository -> repository.parsePullRequestUrl(prLink))
                                      .filter(Optional::isPresent)
                                      .map(Optional::get).findAny();
            if (foundPr.isEmpty()) {
                log.info("PR link that can't be matched to an actual PR: " + prLink);
                parsedConversations.put(first.id(), "invalid");
                return;
            }
            pr = foundPr.get();
            resolvedPullRequests.put(first.id(), pr);
        }
        var bridgeIdPattern = Pattern.compile("^[^.]+\\.[^.]+@" + pr.repository().url().getHost() + "$");

        // Filter out already bridged comments
        var bridgeCandidates = newMessages.stream()
                                          .filter(email -> !bridgeIdPattern.matcher(email.id().address()).matches())
                                          .collect(Collectors.toList());
        if (bridgeCandidates.isEmpty()) {
            return;
        }

        var workItem = new CommentPosterWorkItem(pr, bridgeCandidates, e -> invalidate(bridgeCandidates));
        commentQueue.add(workItem);
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var readerItems = lists.stream()
                               .map(list -> new ArchiveReaderWorkItem(this, list))
                               .collect(Collectors.toList());

        var ret = new ArrayList<WorkItem>(readerItems);

        // Check if there are any potential new comments to post
        var item = commentQueue.poll();
        while (item != null) {
            ret.add(item);
            item = commentQueue.poll();
        }

        return ret;
    }
}
