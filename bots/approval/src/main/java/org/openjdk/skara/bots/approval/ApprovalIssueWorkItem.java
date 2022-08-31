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
package org.openjdk.skara.bots.approval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Logger;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.issuetracker.Issue;

public class ApprovalIssueWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.approval");
    private final ApprovalIssueBot bot;
    private final Issue issue;

    public ApprovalIssueWorkItem(ApprovalIssueBot bot, Issue issue) {
        this.bot = bot;
        this.issue = issue;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var ret = new ArrayList<WorkItem>();
        PullRequestUtils.pullRequestCommentLink(issue).stream()
                .flatMap(uri -> bot.repositories().stream()
                             .flatMap(r -> r.parsePullRequestUrl(uri.toString()).stream()))
                .filter(pr -> pr.isOpen() && bot.isUpdateChange(pr))
                .map(pr -> new ApprovalPullRequestWorkItem(pr.repository(), pr.id(), issue.project(),
                        bot.approvalInfos().stream().filter(info -> bot.approvalInfoMatch(info, pr)).findFirst().get()))
                .forEach(ret::add);
        ret.forEach(item -> log.fine("Scheduling: " + item.toString() + " due to update in " + issue.id()));
        return ret;
    }

    @Override
    public String botName() {
        return ApprovalBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "approval-issue";
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof ApprovalIssueWorkItem item)) {
            return true;
        }

        return !(issue.project().name().equals(item.issue.project().name()) && issue.id().equals(item.issue.id()));
    }

    @Override
    public String toString() {
        return botName() + "/ApprovalIssueWorkItem@" + issue.id();
    }
}
