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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.util.List;
import java.util.regex.Pattern;

public class ApproveCommand implements CommandHandler {

    private static final Pattern APPROVE_ARG_PATTERN = Pattern.compile("(([A-Za-z]+-)?[0-9]+)? ?(yes|no)");

    @Override
    public String description() {
        return null;
    }

    @Override
    public String name() {
        return null;
    }

    private void showHelp(PrintWriter reply) {
        reply.println("usage: `/approve [<id>] (yes|no)`");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!bot.integrators().contains(command.user().username())) {
            reply.println("Only integrators for this repository are allowed to issue the `/approve` command.");
        }

        var approval = bot.approval();
        var targetRef = pr.targetRef();
        if (approval == null || !approval.needsApproval(targetRef)) {
            reply.println("This target branch doesn't need maintainer approval.");
            return;
        }
        var argMatcher = APPROVE_ARG_PATTERN.matcher(command.args());
        if (!argMatcher.matches()) {
            showHelp(reply);
            return;
        }
        var issueProject = bot.issueProject();
        String issueId = argMatcher.group(1);
        String option = argMatcher.group(3);

        var issueOpt = ApprovalCommand.getIssue(issueId, pr, allComments, reply);
        if (issueOpt.isEmpty()) {
            return;
        }
        var issue = issueOpt.get();

        if (issue.project().isPresent() && !issue.project().get().equalsIgnoreCase(issueProject.name())) {
            reply.print("Can only approve issues in the " + issueProject.name() + " project.");
            return;
        }

        var issueTrackerIssueOpt = issueProject.issue(issue.shortId());
        if (issueTrackerIssueOpt.isEmpty()) {
            reply.print("Can not find " + issue.id() + " in " + issueProject.name() + ".");
            return;
        }
        var issueTrackerIssue = issueTrackerIssueOpt.get();
        var approvedLabel = approval.approvedLabel(targetRef);
        var rejectedLabel = approval.rejectedLabel(targetRef);

        if (option.equals("yes")) {
            issueTrackerIssue.removeLabel(rejectedLabel);
            issueTrackerIssue.addLabel(approvedLabel);
            reply.print("The approval request has been approved.");
        } else if (option.equals("no")) {
            issueTrackerIssue.removeLabel(approvedLabel);
            issueTrackerIssue.addLabel(rejectedLabel);
            reply.print("The approval request has been rejected.");
        }
    }
}
