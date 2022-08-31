/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.csr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.jbs.Backports;

/**
 * The IssueWorkItem is read-only. Its purpose is to create PullRequestWorkItems for
 * every pull request found in the Backport hierarchy associated with a CSR issue.
 * It should only be triggered when a modified CSR issue has been found.
 */
class IssueWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.csr");

    private final CSRIssueBot bot;
    private final Issue csrIssue;

    private final Consumer<RuntimeException> errorHandler;

    public IssueWorkItem(CSRIssueBot bot, Issue csrIssue, Consumer<RuntimeException> errorHandler) {
        this.bot = bot;
        this.csrIssue = csrIssue;
        this.errorHandler = errorHandler;
    }

    @Override
    public String toString() {
        return botName() + "/IssueWorkItem@" + csrIssue.id();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof IssueWorkItem otherItem)) {
            return true;
        }

        return !csrIssue.project().name().equals(otherItem.csrIssue.project().name());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var link = csrIssue.links().stream()
                .filter(l -> l.relationship().isPresent() && "csr of".equals(l.relationship().get())).findAny();
        var issue = link.flatMap(Link::issue);
        var mainIssue = issue.flatMap(Backports::findMainIssue);
        if (mainIssue.isEmpty()) {
            return List.of();
        }
        var backports = Backports.findBackports(mainIssue.get(), false);
        var ret = new ArrayList<WorkItem>();
        Stream.concat(mainIssue.stream(), backports.stream())
                .flatMap(i -> PullRequestUtils.pullRequestCommentLink(i).stream())
                .flatMap(uri -> bot.repositories().stream()
                        .flatMap(r -> r.parsePullRequestUrl(uri.toString()).stream()))
                .filter(Issue::isOpen)
                // This will mix time stamps from the IssueTracker and the Forge hosting PRs, but it's the
                // best we can do.
                .map(pr -> new PullRequestWorkItem(pr.repository(), pr.id(), csrIssue.project(),
                        errorHandler, csrIssue.updatedAt()))
                .forEach(ret::add);
        return ret;
    }

    @Override
    public String botName() {
        return CSRBotFactory.NAME;
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
