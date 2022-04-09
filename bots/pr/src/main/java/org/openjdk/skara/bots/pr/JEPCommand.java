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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.jira.JiraProject;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class JEPCommand implements CommandHandler {
    static final String JEP_LABEL = "jep";
    private static final String jepMarker = "<!-- jep: '%s' '%s' '%s' -->"; // <!-- jep: 'JEP-ID' 'ISSUE-ID' 'ISSUE-TITLE' -->
    static final Pattern jepMarkerPattern = Pattern.compile("<!-- jep: '(.*?)' '(.*?)' '(.*?)' -->");
    private static final String unneededMarker = "<!-- jep: 'unneeded' 'unneeded' 'unneeded' -->";

    private void showHelp(PrintWriter reply) {
        reply.println("""
                Command syntax:
                 * `/jep <issue-id>`
                 * `/jep JEP-<jep-id>`
                 * `/jep jep-<jep-id>`
                 * `/jep unneeded`

                Some examples:

                 * `/jep JDK-1234567`
                 * `/jep 1234567`
                 * `/jep jep-123`
                 * `/jep JEP-123`
                 * `/jep unneeded`

                Note:
                The project prefix of the issue ID (`JDK-` in the above examples) is optional.
                But the `JEP-` or `jep-` prefix is required when you provide a JEP ID.
                And the issue type must be `JEP`.
                """);
    }

    private Optional<Issue> getJepIssue(String args, PullRequestBot bot, PrintWriter reply) {
        Optional<Issue> jbsIssue;
        if (args.startsWith("jep-") || args.startsWith("JEP-")) {
            // Handle the JEP ID
            jbsIssue = bot.issueProject().jepIssue(args.substring(4));
        } else {
            // Handle the issue ID
            jbsIssue = bot.issueProject().issue(args);
        }
        return jbsIssue;
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath,
                       CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
            reply.println("only the pull request author and [Reviewers](https://openjdk.java.net/bylaws#reviewer) are allowed to use the `jep` command.");
            return;
        }

        var args = command.args().trim();
        if (args.isEmpty() || args.isBlank()) {
            showHelp(reply);
            return;
        }

        var labelNames = pr.labelNames();
        if ("unneeded".equals(args) || "uneeded".equals(args)) {
            if (pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
                reply.println("only [Reviewers](https://openjdk.java.net/bylaws#reviewer) can determine that a JEP request is not needed.");
                return;
            }
            if (labelNames.contains(JEP_LABEL)) {
                pr.removeLabel(JEP_LABEL);
            }
            reply.println(unneededMarker);
            reply.println("determined that the [JEP](https://openjdk.java.net/jeps) request " +
                    "is not needed for this pull request.");
            return;
        }

        // Get the issue
        var jbsIssueOpt = getJepIssue(args, bot, reply);
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
        var issueStatus = jbsIssue.properties().get("status").get("name").asString();
        var resolution = jbsIssue.properties().get("resolution");
        String resolutionName = "";
        if (resolution != null && !resolution.isNull() &&
                resolution.get("name") != null && !resolution.get("name").isNull()) {
            resolutionName = resolution.get("name").asString();
        }

        // Set the marker and output the result
        var jepNumber = jbsIssue.properties().get(JiraProject.JEP_NUMBER).asString();
        reply.println(String.format(jepMarker, jepNumber, jbsIssue.id(), jbsIssue.title()));
        if ("Targeted".equals(issueStatus) || "Integrated".equals(issueStatus) ||
            "Completed".equals(issueStatus) || ("Closed".equals(issueStatus) && "Delivered".equals(resolutionName))) {
            reply.println("the JEP for this pull request, [JEP-" + jepNumber + "](" + jbsIssue.webUrl() + "), has already been targeted.");
            if (labelNames.contains(JEP_LABEL)) {
                pr.removeLabel(JEP_LABEL);
            }
        } else if ("Draft".equals(issueStatus) || "Submitted".equals(issueStatus) || "Candidate".equals(issueStatus) ||
                "Proposed to Target".equals(issueStatus) || "Proposed to Drop".equals(issueStatus) || "Closed".equals(issueStatus)) {
            reply.println("this pull request will not be integrated until the [JEP-" + jepNumber
                    + "](" + jbsIssue.webUrl() + ")" + " has been targeted.");
            if (!labelNames.contains(JEP_LABEL)) {
                pr.addLabel(JEP_LABEL);
            }
        }
    }

    @Override
    public String description() {
        return "require a JDK Enhancement Proposal for this pull request";
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
