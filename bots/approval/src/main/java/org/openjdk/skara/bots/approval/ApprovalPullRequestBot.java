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

import java.util.ArrayList;
import java.util.List;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.UpdatedPullRequestPoller;
import org.openjdk.skara.issuetracker.IssueProject;

public class ApprovalPullRequestBot extends AbstractApprovalBot implements Bot {
    private final HostedRepository repository;
    private final UpdatedPullRequestPoller poller;

    public ApprovalPullRequestBot(HostedRepository repository, IssueProject issueProject,
                                  List<ApprovalInfo> approvalInfos) {
        super(approvalInfos, issueProject);
        this.repository = repository;
        this.poller = new UpdatedPullRequestPoller(repository);
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var items = new ArrayList<WorkItem>();
        for (var pr : poller.getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests)) {
            if (requiresApproval(pr)) {
                var pullRequestWorkItem = new ApprovalPullRequestWorkItem(repository, pr.id(), issueProject(),
                                approvalInfos().stream().filter(info -> approvalInfoMatch(info, pr)).findFirst().get());
                items.add(pullRequestWorkItem);
            }
        }
        return items;
    }

    @Override
    public String name() {
        return ApprovalBotFactory.NAME;
    }

    @Override
    public String toString() {
        return "ApprovalPullRequestBot@" + repository.name();
    }
}
