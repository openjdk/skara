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

import org.openjdk.skara.bots.common.SolvesTracker;
import org.openjdk.skara.forge.PreIntegrations;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.PrintWriter;
import java.util.ArrayList;
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
            return;
        }

        var approval = bot.approval();
        var targetRef = PreIntegrations.realTargetRef(pr);
        if (approval == null) {
            reply.println("Changes in this repository do not require maintainer approval.");
            return;
        }
        if (!approval.needsApproval(targetRef)) {
            reply.println("Changes to branch " + targetRef + " do not require maintainer approval");
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

        var issues = getIssues(issueId, pr, allComments, reply);
        if (issues.isEmpty()) {
            return;
        }
        reply.println();
        for (var issue : issues) {
            reply.print(issue.id() + ": ");
            if (issue.project().isPresent() && !issue.project().get().equalsIgnoreCase(issueProject.name())) {
                reply.println("Can only approve issues in the " + issueProject.name() + " project.");
                continue;
            }

            var issueTrackerIssueOpt = issueProject.issue(issue.shortId());
            if (issueTrackerIssueOpt.isEmpty()) {
                reply.println("Can not find " + issue.id() + " in the " + issueProject.name() + " project.");
                continue;
            }

            var issueTrackerIssue = issueTrackerIssueOpt.get();
            var approvedLabel = approval.approvedLabel(targetRef);
            var rejectedLabel = approval.rejectedLabel(targetRef);
            var requestLabel = approval.requestedLabel(targetRef);
            var labels = issueTrackerIssue.labelNames();

            if (!labels.contains(requestLabel)) {
                reply.println("There is no maintainer approval request for this issue.");
                continue;
            }

            if (option.equals("yes")) {
                issueTrackerIssue.removeLabel(rejectedLabel);
                issueTrackerIssue.addLabel(approvedLabel);
                reply.println("The approval request has been approved.");
            } else if (option.equals("no")) {
                issueTrackerIssue.removeLabel(approvedLabel);
                issueTrackerIssue.addLabel(rejectedLabel);
                reply.println("The approval request has been rejected.");
            }
        }
    }

    static List<Issue> getIssues(String issueId, PullRequest pr, List<Comment> allComments, PrintWriter reply) {
        var titleIssue = Issue.fromStringRelaxed(pr.title());
        var issueIds = new ArrayList<String>();
        titleIssue.ifPresent(value -> issueIds.add(value.shortId()));
        List<Issue> ret = new ArrayList<>();
        issueIds.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), allComments, pr.title())
                .stream()
                .map(Issue::shortId)
                .toList());

        if (issueId != null) {
            var issue = new Issue(issueId, null);
            if (issueIds.contains(issue.shortId())) {
                ret.add(issue);
            } else {
                reply.println("This issue is not associated with this pull request.");
            }
            // If issueId is not specified, then handle all the issues associated with this pull request
        } else {
            if (issueIds.size() == 0) {
                reply.println("There is no issue associated with this pull request.");
            } else {
                ret.addAll(issueIds.stream()
                        .map(id -> new Issue(id, null))
                        .toList());
            }
        }
        return ret;
    }
}
