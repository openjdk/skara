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
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.openjdk.skara.bots.common.CommandNameEnum.approval;

public class ApprovalCommand implements CommandHandler {
    @Override
    public String description() {
        return "request for maintainer's approval";
    }

    @Override
    public String name() {
        return approval.name();
    }

    private static final Pattern APPROVAL_ARG_PATTERN = Pattern.compile("(([A-Za-z]+-)?[0-9]+) (request|cancel)(.*?)?", Pattern.MULTILINE | Pattern.DOTALL);

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().username() + ") is allowed to issue the `/approval` command.");
            return;
        }
        var approval = bot.approval();
        var targetRef = pr.targetRef();
        if (approval == null || !approval.needsApproval(targetRef)) {
            reply.println("No need to apply for maintainer approval");
            return;
        }
        var argMatcher = APPROVAL_ARG_PATTERN.matcher(command.args());
        if (!argMatcher.matches()) {
            showHelp(reply);
            return;
        }

        var issueProject = bot.issueProject();
        String issueId = argMatcher.group(1);
        String option = argMatcher.group(3);
        String message = argMatcher.group(4);
        Issue issue = new Issue(issueId, null);

        if (issue.project().isPresent() && !issue.project().get().equalsIgnoreCase(issueProject.name())) {
            reply.print("Can only request approval for issues in " + issueProject.name() + "!");
            return;
        }
        var titleIssue = Issue.fromStringRelaxed(pr.title());
        var issues = new ArrayList<String>();
        issues.add(titleIssue.get().shortId());
        issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), allComments, pr.title())
                .stream()
                .map(Issue::shortId)
                .toList());

        if (!issues.contains(issue.shortId())) {
            reply.print("Can only request approval for issues that this pr solves");
            return;
        }

        var issueTrackerIssueOpt = issueProject.issue(issue.shortId());
        if (issueTrackerIssueOpt.isEmpty()) {
            reply.print("Can not find " + issue.id() + " in " + issueProject.name());
            return;
        }
        var issueTrackerIssue = issueTrackerIssueOpt.get();
        var requestLabel = approval.requestedLabel(targetRef);
        var approvedLabel = approval.approvedLabel(targetRef);
        var rejectedLabel = approval.rejectedLabel(targetRef);
        var prefix = "[" + requestLabel + "]";
        var comments = issueTrackerIssue.comments();
        var existingComment = comments.stream()
                .filter(comment -> comment.author().equals(issueProject.issueTracker().currentUser()))
                .filter(comment -> comment.body().contains(prefix))
                .findFirst();

        if (option.equals("cancel")) {
            var labels = issueTrackerIssue.labelNames();
            if (labels.contains(approvedLabel) || labels.contains(rejectedLabel)) {
                reply.print("The request has been processed by maintainer! Could not cancel the request.");
            } else {
                issueTrackerIssue.removeLabel(requestLabel);
                existingComment.ifPresent(issueTrackerIssue::removeComment);
                reply.print("The request has already been cancelled successfully!");
            }
        } else if (option.equals("request")) {
            issueTrackerIssue.addLabel(requestLabel);
            var messageToPost = prefix + ":Request from " + command.user().fullName() + "\n" + message.trim();
            if (existingComment.isPresent()) {
                if (!existingComment.get().body().equals(messageToPost)) {
                    issueTrackerIssue.updateComment(existingComment.get().id(), messageToPost);
                }
            } else {
                issueTrackerIssue.addComment(messageToPost);
            }
            reply.print("The request has been created successfully!");
        }
    }

    @Override
    public boolean multiLine() {
        return true;
    }

    private void showHelp(PrintWriter reply) {
        reply.println("usage: `/approval <id> [request|cancel] [<text>]`");
    }
}
