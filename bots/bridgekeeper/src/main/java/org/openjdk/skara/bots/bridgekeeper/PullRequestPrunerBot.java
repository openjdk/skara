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
package org.openjdk.skara.bots.bridgekeeper;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;

class PullRequestPrunerBotWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final PullRequest pr;
    private final Duration maxAge;

    PullRequestPrunerBotWorkItem(HostedRepository repository, PullRequest pr, Duration maxAge) {
        this.pr = pr;
        this.repository = repository;
        this.maxAge = maxAge;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestPrunerBotWorkItem)) {
            return true;
        }
        PullRequestPrunerBotWorkItem otherItem = (PullRequestPrunerBotWorkItem) other;
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!repository.name().equals(otherItem.repository.name())) {
            return true;
        }
        return false;
    }

    // Prune durations are on the order of days and weeks
    private String formatDuration(Duration duration) {
        var count = duration.toDays();
        var unit = "day";

        if (count > 14) {
            count /= 7;
            unit = "week";
        }
        if (count != 1) {
            unit += "s";
        }
        return count + " " + unit;
    }

    private final String noticeMarker = "<!-- PullrequestCloserBot auto close notification -->";

    @Override
    public void run(Path scratchPath) {
        var comments = pr.comments();
        if (comments.size() > 0) {
            var lastComment = comments.get(comments.size() - 1);
            if (lastComment.author().equals(repository.forge().currentUser()) && lastComment.body().contains(noticeMarker)) {
                var message = "@" + pr.author().userName() + " This pull request has been inactive for more than " +
                        formatDuration(maxAge.multipliedBy(2)) + " and will now be automatically closed. If you would " +
                        "like to continue working on this pull request in the future, feel free to reopen it!";
                log.fine("Posting prune message");
                pr.addComment(message);
                pr.setState(PullRequest.State.CLOSED);
                return;
            }
        }

        var message = "@" + pr.author().userName() + " This pull request has been inactive for more than " +
                formatDuration(maxAge) + " and will be automatically closed if another " + formatDuration(maxAge) +
                " passes without any activity. To avoid this, simply add a new comment to the pull request. Feel free " +
                "to ask for assistance if you need help with progressing this pull request towards integration!";

        log.fine("Posting prune notification message");
        pr.addComment(noticeMarker + "\n\n" + message);
    }

    @Override
    public String toString() {
        return "PullRequestPrunerBotWorkItem@" + repository.name() + "#" + pr.id();
    }
}

public class PullRequestPrunerBot implements Bot {
    private final HostedRepository repository;
    private final Duration maxAge;

    PullRequestPrunerBot(HostedRepository repository, Duration maxAge) {
        this.repository = repository;
        this.maxAge = maxAge;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        List<WorkItem> ret = new LinkedList<>();
        var oldestAllowed = ZonedDateTime.now().minus(maxAge);

        for (var pr : repository.pullRequests()) {
            if (pr.updatedAt().isBefore(oldestAllowed)) {
                var item = new PullRequestPrunerBotWorkItem(repository, pr, maxAge);
                ret.add(item);
            }
        }

        return ret;
    }
}
