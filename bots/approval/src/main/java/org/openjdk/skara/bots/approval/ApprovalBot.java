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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.issuetracker.IssuePoller;

public class ApprovalBot implements Bot {
    private final List<ApprovalInfo> approvalInfos;
    private final IssueProject issueProject;
    private final List<HostedRepository> repositories;
    private final IssuePoller poller;

    public ApprovalBot(IssueProject issueProject, List<HostedRepository> repositories, List<ApprovalInfo> approvalInfos) {
        this.approvalInfos = approvalInfos;
        this.issueProject = issueProject;
        this.repositories = repositories;
        // When restarting the bot, the bot only polls the issues in one week,
        // because the bot should not be down more than one week.
        this.poller = new IssuePoller(issueProject, Duration.ofDays(7));
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var items = new ArrayList<WorkItem>();
        for (var issue : poller.updatedIssues()) {
            var itemsPerIssue = PullRequestUtils.pullRequestCommentLink(issue).stream()
                    .flatMap(uri -> repositories.stream().flatMap(r -> r.parsePullRequestUrl(uri.toString()).stream()))
                    .filter(pr -> pr.isOpen() && requiresApproval(pr))
                    .map(pr -> new ApprovalWorkItem(pr.repository(), pr.id(), issue,
                            approvalInfos.stream().filter(info -> approvalInfoMatch(info, pr)).findFirst().get()))
                    .collect(Collectors.toList());
            items.addAll(itemsPerIssue);
        }
        poller.lastBatchHandled();
        return items;
    }

    private boolean requiresApproval(PullRequest pr) {
        return approvalInfos != null &&
                approvalInfos.stream().anyMatch(info -> approvalInfoMatch(info, pr));
    }

    private boolean approvalInfoMatch(ApprovalInfo info, PullRequest pr) {
        return info.repo().isSame(pr.repository()) &&
                info.branchPattern().matcher(pr.targetRef()).matches();
    }

    @Override
    public String name() {
        return ApprovalBotFactory.NAME;
    }

    @Override
    public String toString() {
        return "ApprovalBot@" + issueProject.name();
    }
}
