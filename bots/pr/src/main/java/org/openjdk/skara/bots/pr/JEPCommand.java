/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Optional;
import org.openjdk.skara.issuetracker.IssueTrackerIssue;

import static org.openjdk.skara.bots.common.CommandNameEnum.jep;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;
import static org.openjdk.skara.bots.common.PullRequestConstants.*;

public class JEPCommand implements CommandHandler {
    private static final String UNNEEDED_MARKER = "<!-- jep: 'unneeded' 'unneeded' 'unneeded' -->";

    private void showHelp(PrintWriter reply) {
        reply.println("""
                Command syntax:
                 * `/jep <jep-id>|<issue-id>`
                 * `/jep JEP-<jep-id>`
                 * `/jep jep-<jep-id>`
                 * `/jep unneeded`

                Some examples:
                 * `/jep 123`
                 * `/jep JDK-1234567`
                 * `/jep 1234567`
                 * `/jep jep-123`
                 * `/jep JEP-123`
                 * `/jep unneeded`

                Note:
                The prefix (i.e. `JDK-`, `JEP-` or `jep-`) is optional. If the argument is given without prefix, \
                it will be tried first as a JEP ID and second as an issue ID. The issue type must be `JEP`.
                """);
    }

    private Optional<IssueTrackerIssue> getJepIssue(String args, PullRequestBot bot) {
        Optional<IssueTrackerIssue> jbsIssue = Optional.empty();
        var upperArgs = args.toUpperCase();
        if (upperArgs.startsWith("JEP-")) {
            // Handle the JEP ID with `JEP` prefix
            jbsIssue = bot.issueProject().jepIssue(args.substring(4));
        } else {
            if (!upperArgs.startsWith(bot.issueProject().name().toUpperCase())) {
                // Handle the raw JEP ID without `JEP` prefix and project prefix. If the JEP has the same ID
                // as any issue, the bot firstly parse the ID as JEP instead of general issue.
                // For example, if we have a `JEP-12345` (its issue ID is not `JDK-12345`) and an issue `JDK-12345`,
                // when typing `/jep 12345`, the bot firstly parses it as `JEP-12345` instead of `JDK-12345`.
                jbsIssue = bot.issueProject().jepIssue(args);
            }
            if (jbsIssue.isEmpty()) {
                // Handle the issue ID
                jbsIssue = bot.issueProject().issue(args);
            }
        }
        return jbsIssue;
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command,
                       List<Comment> allComments, PrintWriter reply, List<String> labelsToAdd, List<String> labelsToRemove) {
        if (!bot.enableJep()) {
            reply.println("This repository has not been configured to use the `jep` command.");
            return;
        }

        if (!pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
            reply.println("Only the pull request author and [Reviewers](https://openjdk.org/bylaws#reviewer) are allowed to use the `jep` command.");
            return;
        }

        var args = command.args().trim();
        if (args.isEmpty() || args.isBlank()) {
            showHelp(reply);
            return;
        }

        var labelNames = pr.labelNames();
        if ("unneeded".equals(args) || "uneeded".equals(args)) {
            if (labelNames.contains(JEP_LABEL)) {
                labelsToRemove.add(JEP_LABEL);
            }
            reply.println(UNNEEDED_MARKER);
            reply.println("determined that the JEP request is not needed for this pull request.");
            return;
        }

        // Get the issue
        var jbsIssueOpt = getJepIssue(args, bot);
        if (jbsIssueOpt.isEmpty()) {
            reply.println("The JEP issue was not found. Please make sure you have entered it correctly.");
            showHelp(reply);
            return;
        }
        var jbsIssue = jbsIssueOpt.get();

        // Verify whether the issue type is a JEP
        var issueType = jbsIssue.properties().get("issuetype");
        if (issueType == null || !"JEP".equals(issueType.asString())) {
            reply.println("The issue `" + jbsIssue.id() + "` is not a JEP. Please make sure you have entered it correctly.");
            showHelp(reply);
            return;
        }

        // Get the issue status
        var issueStatus = jbsIssue.status();
        var resolution = jbsIssue.resolution();

        // Set the marker and output the result
        var jepNumber = jbsIssue.properties().containsKey(JEP_NUMBER) ? jbsIssue.properties().get(JEP_NUMBER).asString() : "NotAllocated";
        reply.println(String.format(JEP_MARKER, jepNumber, jbsIssue.id(), jbsIssue.title()));
        if ("Targeted".equals(issueStatus) || "Integrated".equals(issueStatus) ||
                "Completed".equals(issueStatus) || ("Closed".equals(issueStatus) && resolution.isPresent() && "Delivered".equals(resolution.get()))) {
            reply.println("The JEP for this pull request, [JEP-" + jepNumber + "](" + jbsIssue.webUrl() + "), has already been targeted.");
            if (labelNames.contains(JEP_LABEL)) {
                labelsToRemove.add(JEP_LABEL);
            }
        } else {
            // The current issue status may be "Draft", "Submitted", "Candidate", "Proposed to Target", "Proposed to Drop" or "Closed without Delivered"
            if (jepNumber.equals("NotAllocated")) {
                reply.println("This pull request will not be integrated until the [JEP " + jbsIssue.id()
                        + "](" + jbsIssue.webUrl() + ")" + " has been targeted.");
            } else {
                reply.println("This pull request will not be integrated until the [JEP-" + jepNumber
                        + "](" + jbsIssue.webUrl() + ")" + " has been targeted.");
            }
            if (!labelNames.contains(JEP_LABEL)) {
                labelsToAdd.add(JEP_LABEL);
            }
        }
    }

    @Override
    public String description() {
        return "require a JDK Enhancement Proposal (JEP) for this pull request";
    }

    @Override
    public String name() {
        return jep.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
