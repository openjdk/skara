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
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.vcs.openjdk.Issue;

public class ApprovalPullRequestWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.approval");
    // The tag to re-run the CheckWorkItem of the PRBot.
    static final String APPROVAL_UPDATE_MARKER = "<!-- approval: 'update' -->";
    static final String PROGRESS_MARKER = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";

    private final HostedRepository repo;
    private final String prId;
    private final IssueProject issueProject;
    private final ApprovalInfo approvalInfo;

    public ApprovalPullRequestWorkItem(HostedRepository repo, String prId,
                                       IssueProject issueProject,
                                       ApprovalInfo approvalInfo) {
        this.repo = repo;
        this.prId = prId;
        this.issueProject = issueProject;
        this.approvalInfo = approvalInfo;
    }

    private String describe(PullRequest pr) {
        return pr.repository().name() + "#" + pr.id();
    }

    private String getStatusMessage(PullRequest pr) {
        var lastIndex = pr.body().lastIndexOf(PROGRESS_MARKER);
        if (lastIndex == -1) {
            return "";
        } else {
            return pr.body().substring(lastIndex);
        }
    }

    private void addUpdateMarker(PullRequest pr) {
        var statusMessage = getStatusMessage(pr);
        if (!statusMessage.contains(APPROVAL_UPDATE_MARKER)) {
            pr.setBody(pr.body() + "\n" + APPROVAL_UPDATE_MARKER + "\n");
        } else {
            log.info("The pull request " + describe(pr) + " has already had a approval update marker. "
                    + "Do not need to add it again.");
        }
    }

    private boolean hasApprovalProgressChecked(PullRequest pr) {
        var statusMessage = getStatusMessage(pr);
        return statusMessage.contains("- [x] Change must be properly approved by the maintainers");
    }

    private boolean hasApprovalProgress(PullRequest pr) {
        var statusMessage = getStatusMessage(pr);
        return statusMessage.contains("- [ ] Change must be properly approved by the maintainers") ||
                statusMessage.contains("- [x] Change must be properly approved by the maintainers");
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var pr = repo.pullRequest(prId);
        var vcsIssue = Issue.fromStringRelaxed(pr.title());
        if (vcsIssue.isEmpty()) {
            log.info("No issue found in title for " + describe(pr));
            return List.of();
        }
        var issueOpt = vcsIssue.flatMap(value -> issueProject.issue(value.shortId()));
        if (issueOpt.isEmpty()) {
            log.info("No issue found in JBS for " + describe(pr));
            return List.of();
        }
        var issue = issueOpt.get();

        if (!hasApprovalProgress(pr)) {
            log.info("The PR body of " + describe(pr) + " doesn't have the approval progress, adding the approval update marker.");
            addUpdateMarker(pr);
        }

        if (issue.labelNames().contains(approvalInfo.approvalLabel()) ||
                issue.labelNames().contains(approvalInfo.disapprovalLabel())) {
            if (!issue.labelNames().contains(approvalInfo.requestLabel())) {
                // The issue has the approval or disapproval label, it should always have a fix request label.
                log.info("The issue " + issue.id() + " has the approval or disapproval label, "
                        + "adding the missed fix request label for it.");
                issue.addLabel(approvalInfo.requestLabel());
            }
            if (pr.labelNames().contains("approval")) {
                log.info("The issue " + issue.id() + " has the approval or disapproval label, "
                        + "removing the `approval` blocked label for " + describe(pr));
                pr.removeLabel("approval");
            }
            if (issue.labelNames().contains(approvalInfo.approvalLabel()) && !hasApprovalProgressChecked(pr)) {
                log.info("The issue " + issue.id() + " has the approval label and the approval progress of the "
                        + describe(pr) + " is not checked, adding the approval update marker.");
                addUpdateMarker(pr);
            }
            if (issue.labelNames().contains(approvalInfo.disapprovalLabel()) && pr.isOpen()) {
                log.info("The issue " + issue.id() + " has the disapproval label and the approval progress of the "
                        + describe(pr) + " is checked, adding the approval update marker.");
                addUpdateMarker(pr);
            }
        }
        return List.of();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof ApprovalPullRequestWorkItem item)) {
            return true;
        }

        return !(repo.isSame(item.repo) && prId.equals(item.prId));
    }

    @Override
    public String botName() {
        return ApprovalBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "approval-pr";
    }

    @Override
    public String toString() {
        return botName() + "/ApprovalPullRequestWorkItem@" + repo.name() + "#" + prId;
    }
}
