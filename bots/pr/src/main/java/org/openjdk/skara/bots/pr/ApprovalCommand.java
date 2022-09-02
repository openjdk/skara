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
package org.openjdk.skara.bots.pr;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;

public class ApprovalCommand implements CommandHandler {
    // The tags to re-run the CheckWorkItem of the PRBot.
    private static final String APPROVAL_MARKER = "<!-- approval: 'yes' -->";
    private static final String DISAPPROVAL_MARKER = "<!-- approval: 'no' -->";

    private static void showHelp(PrintWriter writer) {
        writer.println("""
                usage: `/approval [yes|no|y|n]`

                examples:
                * `/approval`
                * `/approval yes`
                * `/approval no`

                Note: Only the repository maintainers are allowed to use the `approval` command.
                """);
    }

    private void approvalReply(PullRequest pr, PrintWriter writer) {
        writer.println("@" + pr.author().username() + " this pull request was approved by the maintainer.");
        writer.println(APPROVAL_MARKER);
    }

    private void disapprovalReply(PullRequest pr, PrintWriter writer) {
        writer.println(String.format("@%s this pull request was rejected by the maintainer. "
                + "The bot will close this pull request automatically.", pr.author().username()));
        writer.println(DISAPPROVAL_MARKER);
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath,
                       CommandInvocation command, List<Comment> allComments, PrintWriter reply, PullRequestWorkItem workItem) {
        if (!workItem.requiresApproval()) {
            reply.println("this repository or the target branch of this pull request have not been configured to use the `approval` command.");
            return;
        }

        var commandUser = censusInstance.namespace().get(command.user().id());
        if (!workItem.isMaintainer(commandUser)) {
            reply.println("only the repository maintainers are allowed to use the `approval` command.");
            return;
        }

        var arg = command.args().trim().toLowerCase();
        if (!arg.isEmpty() && !(arg.equals("yes") || arg.equals("y") || arg.equals("no") || arg.equals("n"))) {
            showHelp(reply);
            return;
        }

        if (arg.equals("no") || arg.equals("n")) {
            for (var vcsIssue : workItem.issues(false, false)) {
                var issueOpt = bot.issueProject().issue(vcsIssue.shortId());
                issueOpt.ifPresent(issue -> {
                    if (!issue.labelNames().contains(workItem.requestLabelName())) {
                        // The maintainers may disapprove the PR before it is ready.
                        // The bot should add the fix request label firstly to avoid the strange
                        // middle state which has disapproval label but has no fix request label.
                        issue.addLabel(workItem.requestLabelName());
                    }
                    if (issue.labelNames().contains(workItem.approvalLabelName())) {
                        // If the maintainers have approved the PR before,
                        // the bot should remove the approval label at first.
                        issue.removeLabel(workItem.approvalLabelName());
                    }
                    if (!issue.labelNames().contains(workItem.disapprovalLabelName())) {
                        issue.addLabel(workItem.disapprovalLabelName());
                    }
                });
            }
            if (pr.labelNames().contains("approval")) {
                pr.removeLabel("approval");
            }
            disapprovalReply(pr, reply);
            pr.setState(Issue.State.CLOSED);
            return;
        }

        pr.setState(Issue.State.OPEN);
        for (var vcsIssue : workItem.issues(false, false)) {
            var issueOpt = bot.issueProject().issue(vcsIssue.shortId());
            issueOpt.ifPresent(issue -> {
                if (!issue.labelNames().contains(workItem.requestLabelName())) {
                    // The maintainers may approve the PR before it is ready.
                    // The bot should add the fix request label firstly to avoid the strange
                    // middle state which has approval label but has no fix request label.
                    issue.addLabel(workItem.requestLabelName());
                }
                if (issue.labelNames().contains(workItem.disapprovalLabelName())) {
                    // If the maintainers have disapproved the PR before,
                    // the bot should remove the disapproval label at first.
                    issue.removeLabel(workItem.disapprovalLabelName());
                }
                if (!issue.labelNames().contains(workItem.approvalLabelName())) {
                    issue.addLabel(workItem.approvalLabelName());
                }
            });
        }
        if (pr.labelNames().contains("approval")) {
            pr.removeLabel("approval");
        }
        approvalReply(pr, reply);
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }

    @Override
    public String description() {
        return "approve or disapprove an update change";
    }
}
