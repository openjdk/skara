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

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.PullRequest;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CommentPosterWorkItem implements WorkItem {
    private final PullRequest pr;
    private final List<Email> newMessages;
    private final Consumer<RuntimeException> errorHandler;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    CommentPosterWorkItem(PullRequest pr, List<Email> newMessages, Consumer<RuntimeException> errorHandler) {
        this.pr = pr;
        this.newMessages = newMessages;
        this.errorHandler = errorHandler;
    }

    @Override
    public String toString() {
        return "CommentPosterWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CommentPosterWorkItem otherItem)) {
            return true;
        }
        if (!pr.isSame(otherItem.pr)) {
            return true;
        }
        var otherItemIds = otherItem.newMessages.stream()
                                                .map(Email::id)
                                                .collect(Collectors.toSet());
        var overlap = newMessages.stream()
                                 .map(Email::id)
                                 .filter(otherItemIds::contains)
                                 .findAny();
        return overlap.isEmpty();
    }


    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var comments = pr.comments();

        var alreadyBridged = new HashSet<EmailAddress>();
        for (var comment : comments) {
            var bridged = BridgedComment.from(comment, pr.repository().forge().currentUser());
            bridged.ifPresent(bridgedComment -> alreadyBridged.add(bridgedComment.messageId()));
        }

        for (var message : newMessages) {
            if (alreadyBridged.contains(message.id())) {
                log.fine("Message " + message.id() + " from " + message.author() + " to " + pr + " has already been bridged - skipping!");
                continue;
            }

            log.info("Bridging new message " + message.id() + " from " + message.author() + " to " + pr);
            BridgedComment.post(pr, message);
            // Timestamp from email and a local date is the best we can do for latency here
            var latency = Duration.between(message.date(), ZonedDateTime.now());
            log.log(Level.INFO, "Time from message date to posting comment " + latency, latency);
        }
        return List.of();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }

    @Override
    public String botName() {
        return MailingListBridgeBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "comment-poster";
    }
}
