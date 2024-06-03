/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;

import java.io.PrintWriter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openjdk.skara.bots.common.CommandNameEnum.csr;
import static org.openjdk.skara.bots.common.PullRequestConstants.*;
import static org.openjdk.skara.bots.pr.CheckRun.CSR_PROCESS_LINK;

public class CSRCommand implements CommandHandler {

    private static final Pattern CSR_PROGRESS_PATTERN = Pattern.compile("- \\[[ x]?\\] Change requires CSR request \\[(.*?)\\]\\((.*?)\\) to be approved");
    private static final Pattern RESOLVED_CSR_PROGRESS_PATTERN = Pattern.compile("- \\[x\\] Change requires CSR request \\[(.*?)\\]\\((.*?)\\) to be approved");

    private static void showHelp(PrintWriter writer) {
        writer.println("usage: `/csr [needed|unneeded]`, requires that the issue the pull request refers to links to an approved [CSR](" + CSR_PROCESS_LINK + ") request.");
    }

    private static void csrReply(PrintWriter writer) {
        writer.println("has indicated that a " +
                "[compatibility and specification](" + CSR_PROCESS_LINK + ") (CSR) request " +
                "is needed for this pull request.");
        writer.println(CSR_NEEDED_MARKER);
    }

    private static void jbsReply(PullRequest pr, PrintWriter writer) {
        writer.println("@" + pr.author().username() + " this pull request must refer to an issue in " +
                "[JBS](https://bugs.openjdk.org) to be able to link it to a [CSR](" + CSR_PROCESS_LINK + ") request. To refer this pull request to " +
                "an issue in JBS, please update the title of this pull request to just the issue ID.");
    }

    private static void multipleIssueReply(PullRequest pr, PrintWriter writer) {
        writer.println("@" + pr.author().username() + " please create a [CSR](" + CSR_PROCESS_LINK + ") request, " +
                "with the correct fix version, for at least one of the issues associated with this pull request." +
                " This pull request cannot be integrated until all the CSR request are approved.");
    }

    private static void singleIssueLinkReply(PullRequest pr, IssueTrackerIssue issue, PrintWriter writer) {
        writer.println("@" + pr.author().username() + " please create a [CSR](" + CSR_PROCESS_LINK + ") request for issue " +
                "[" + issue.id() + "](" + issue.webUrl() + ") with the correct fix version. " +
                "This pull request cannot be integrated until the CSR request is approved.");
    }

    private static void csrUnneededReply(PullRequest pr, PrintWriter writer) {
        writer.println("determined that a [CSR](" + CSR_PROCESS_LINK + ") request " +
                "is not needed for this pull request.");
        writer.println(CSR_UNNEEDED_MARKER);
        if (pr.labelNames().contains(CSR_LABEL)) {
            pr.removeLabel(CSR_LABEL);
        }
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!bot.enableCsr()) {
            reply.println("This repository has not been configured to use the `csr` command.");
            return;
        }

        if (!pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
            reply.println("only the pull request author and [Reviewers](https://openjdk.org/bylaws#reviewer) are allowed to use the `csr` command.");
            return;
        }

        var labels = pr.labelNames();

        var cmd = command.args().trim().toLowerCase();
        if (!cmd.isEmpty() && !(cmd.equals("needed") || cmd.equals("unneeded") || cmd.equals("uneeded"))) {
            showHelp(reply);
            return;
        }

        if (cmd.equals("unneeded") || cmd.equals("uneeded")) {
            if (pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
                reply.println("only [Reviewers](https://openjdk.org/bylaws#reviewer) can determine that a CSR is not needed.");
                return;
            }

            var csrs = pr.body()
                    .lines()
                    .map(CSR_PROGRESS_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .toList();

            // PR's body could be stale, so fetch the csr from jbs to check if the csr has been withdrawn
            var issueProject = bot.issueProject();
            var filteredCsrs = csrs.stream()
                    .filter(csr -> issueProject.issue(csr.group(1))
                            .filter(issueTrackerIssue -> !CheckRun.isWithdrawnCSR(issueTrackerIssue))
                            .isPresent())
                    .toList();

            if (!filteredCsrs.isEmpty()) {
                var csrLinks = new StringBuilder();
                for (Matcher csr : filteredCsrs) {
                    csrLinks.append("[").append(csr.group(1)).append("](").append(csr.group(2)).append(")").append(" ");
                }
                reply.println("The CSR requirement cannot be removed as CSR issues already exist. Please withdraw " + csrLinks +
                        "and then use the command `/csr unneeded` again.");
                reply.println(CSR_NEEDED_MARKER);
            } else {
                // All the issues associated with this pr either don't have csr issue or the csr issue has already been withdrawn,
                // the bot should just remove the csr label and reply the message.
                csrUnneededReply(pr, reply);
            }
            return;
        }

        if (labels.contains(CSR_LABEL)) {
            reply.println("an approved [CSR](" + CSR_PROCESS_LINK + ") request " +
                    "is already required for this pull request.");
            reply.println(CSR_NEEDED_MARKER);
            return;
        }

        var issueProject = bot.issueProject();
        // Main issue is missing, this pr doesn't solve any issue
        var mainIssue = org.openjdk.skara.vcs.openjdk.Issue.fromStringRelaxed(pr.title());
        if (issueProject == null || mainIssue.isEmpty()) {
            csrReply(reply);
            jbsReply(pr, reply);
            pr.addLabel(CSR_LABEL);
            return;
        }

        var jbsMainIssueOpt = issueProject.issue(mainIssue.get().shortId());
        if (jbsMainIssueOpt.isEmpty()) {
            csrReply(reply);
            jbsReply(pr, reply);
            pr.addLabel(CSR_LABEL);
            return;
        }

        var resolvedCSRs = pr.body()
                .lines()
                .map(RESOLVED_CSR_PROGRESS_PATTERN::matcher)
                .filter(Matcher::matches)
                .toList();

        if (!resolvedCSRs.isEmpty()) {
            var csrLinks = new StringBuilder();
            for (Matcher resolvedCSR : resolvedCSRs) {
                csrLinks.append("[").append(resolvedCSR.group(1)).append("](").append(resolvedCSR.group(2)).append(")").append(" ");
            }
            reply.println("This pull request already associated with these approved CSRs: " + csrLinks);
            reply.println(CSR_NEEDED_MARKER);
        } else {
            csrReply(reply);
            var issues = SolvesTracker.currentSolved(pr.repository().forge().currentUser(), allComments, pr.title());
            if (issues.isEmpty()) {
                singleIssueLinkReply(pr, jbsMainIssueOpt.get(), reply);
            } else {
                multipleIssueReply(pr, reply);
            }
            pr.addLabel(CSR_LABEL);
        }
    }

    @Override
    public String description() {
        return "require a compatibility and specification request (CSR) for this pull request";
    }

    @Override
    public String name() {
        return csr.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
