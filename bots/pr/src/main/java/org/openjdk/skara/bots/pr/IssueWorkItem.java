/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.issuetracker.Issue;

/**
 * The IssueWorkItem is read-only. Its purpose is to create PullRequestWorkItems for
 * every pull request found in the Backport hierarchy associated with a CSR issue.
 * It should only be triggered when a modified CSR issue has been found.
 */
class IssueWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private final IssueBot bot;
    private final Issue issue;
    private final Consumer<RuntimeException> errorHandler;

    public IssueWorkItem(IssueBot bot, Issue issue, Consumer<RuntimeException> errorHandler) {
        this.bot = bot;
        this.issue = issue;
        this.errorHandler = errorHandler;
    }

    @Override
    public String toString() {
        return botName() + "/IssueWorkItem@" + issue.id();
    }

    /**
     * Concurrency between IssueWorkItems is ok as long as they aren't processing the
     * same issue and are spawned from the same bot instance.
     */
    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof IssueWorkItem otherItem)) {
            return true;
        }

        if (!issue.project().name().equals(otherItem.issue.project().name())) {
            return true;
        }

        if (!issue.id().equals(otherItem.issue.id())) {
            return true;
        }

        if (!bot.equals(otherItem.bot)) {
            return true;
        }
        return false;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var ret = new ArrayList<WorkItem>();

        // find related prs according to the issue
        var prIds = bot.issuePRMap().get(issue.id());
        if (prIds == null) {
            return ret;
        }
        bot.issuePRMap().get(issue.id()).stream()
                .flatMap(id -> bot.repositories().stream()
                        .filter(r -> r.name().equals(id.split("#")[0]))
                        .map(r -> r.pullRequest(id.split("#")[1]))
                )
                .filter(Issue::isOpen)
                // This will mix time stamps from the IssueTracker and the Forge hosting PRs, but it's the
                // best we can do.
                .map(pr -> new CheckWorkItem(bot.getPRBot(pr.repository().name()), pr.id(), errorHandler, issue.updatedAt(), true, false, true))
                .forEach(ret::add);
        return ret;
    }

    @Override
    public String botName() {
        return bot.name();
    }

    @Override
    public String workItemName() {
        return "issue";
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }
}
