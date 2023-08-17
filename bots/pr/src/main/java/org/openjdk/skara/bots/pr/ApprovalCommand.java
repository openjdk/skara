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
import java.util.Optional;
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

    private static final Pattern APPROVAL_ARG_PATTERN = Pattern.compile("(([A-Za-z]+-)?[0-9]+)? ?(request|cancel)(.*?)?", Pattern.MULTILINE | Pattern.DOTALL);

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().username() + ") is allowed to issue the `/approval` command.");
            return;
        }
        var approval = bot.approval();
        var targetRef = pr.targetRef();
        if (approval == null) {
            reply.println("Changes in this repository do not require maintainer approval.");
            return;
        }
        if (!approval.needsApproval(targetRef)) {
            reply.println("Changes to branch " + pr.targetRef() + " do not require maintainer approval");
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

        var issueOpt = getIssue(issueId, pr, allComments, reply);
        if (issueOpt.isEmpty()) {
            return;
        }
        var issue = issueOpt.get();

        if (issue.project().isPresent() && !issue.project().get().equalsIgnoreCase(issueProject.name())) {
            reply.println("Approval can only be requested for issues in the " + issueProject.name() + " project.");
            return;
        }

        var issueTrackerIssueOpt = issueProject.issue(issue.shortId());
        if (issueTrackerIssueOpt.isEmpty()) {
            reply.println("Can not find " + issue.id() + " in the " + issueProject.name() + " project.");
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
                .filter(comment -> comment.body().startsWith(prefix))
                .findFirst();

        var labels = issueTrackerIssue.labelNames();
        if (option.equals("cancel")) {
            if (labels.contains(approvedLabel) || labels.contains(rejectedLabel)) {
                reply.println("The request has already been handled by a maintainer and can no longer be canceled.");
            } else {
                issueTrackerIssue.removeLabel(requestLabel);
                existingComment.ifPresent(issueTrackerIssue::removeComment);
                reply.println("The approval request has been cancelled successfully.");
            }
        } else if (option.equals("request")) {
            if (labels.contains(approvedLabel)) {
                reply.println("Approval has already been requested and approved.");
            } else if (labels.contains(rejectedLabel)) {
                reply.println("Approval has already been requested and rejected.");
            } else {
                var messageToPost = prefix + " Approval Request from " + command.user().fullName() + "\n" + message.trim();
                if (existingComment.isPresent()) {
                    if (!existingComment.get().body().equals(messageToPost)) {
                        Comment comment = issueTrackerIssue.updateComment(existingComment.get().id(), messageToPost);
                        reply.println("The approval [request](" + issueTrackerIssue.commentUrl(comment) + ") has been updated successfully.");
                    } else {
                        reply.println("The approval [request](" + issueTrackerIssue.commentUrl(existingComment.get()) + ") was already up to date.");
                    }
                } else {
                    Comment comment = issueTrackerIssue.addComment(messageToPost);
                    reply.println("The approval [request](" + issueTrackerIssue.commentUrl(comment) + ") has been created successfully.");
                }
                issueTrackerIssue.addLabel(requestLabel);
            }
        }
    }

    private Optional<Issue> getIssue(String issueId, PullRequest pr, List<Comment> allComments, PrintWriter reply) {
        var titleIssue = Issue.fromStringRelaxed(pr.title());
        var issues = new ArrayList<String>();
        titleIssue.ifPresent(value -> issues.add(value.shortId()));
        issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), allComments, pr.title())
                .stream()
                .map(Issue::shortId)
                .toList());
        // If there is only one issue associated with the pr, then the user don't need to specify the issueID in command
        if (issueId == null) {
            if (issues.size() == 1) {
                issueId = issues.get(0);
            } else if (issues.size() == 0) {
                reply.println("There is no issue associated with this pull request.");
                return Optional.empty();
            } else {
                reply.println("There are multiple issues associated with this pull request, you need to request approval for each one individually.");
                return Optional.empty();
            }
        }
        Issue issue = new Issue(issueId, null);
        if (!issues.contains(issue.shortId())) {
            reply.println("Approval can only be requested for issues that this pull request solves.");
            return Optional.empty();
        }
        return Optional.of(issue);
    }

    @Override
    public boolean multiLine() {
        return true;
    }

    private void showHelp(PrintWriter reply) {
        reply.println("usage: `/approval [<id>] (request|cancel) [<text>]`");
    }
}
