/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.bots.common.SolvesTracker;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jbs.Backports;
import org.openjdk.skara.json.JSON;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CSRCommand implements CommandHandler {
    private static final String CSR_LABEL = "csr";
    private static final String CSR_NEEDED_MARKER = "<!-- csr: 'needed' -->";
    private static final String CSR_UNNEEDED_MARKER = "<!-- csr: 'unneeded' -->";

    private static void showHelp(PrintWriter writer) {
        writer.println("usage: `/csr [needed|unneeded]`, requires that the issue the pull request refers to links to an approved [CSR](https://wiki.openjdk.org/display/csr/Main) request.");
    }

    private static void csrReply(PrintWriter writer) {
        writer.println("has indicated that a " +
                      "[compatibility and specification](https://wiki.openjdk.org/display/csr/Main) (CSR) request " +
                      "is needed for this pull request.");
        writer.println(CSR_NEEDED_MARKER);
    }

    private static void jbsReply(PullRequest pr, PrintWriter writer) {
        writer.println("@" + pr.author().username() + " this pull request must refer to an issue in " +
                      "[JBS](https://bugs.openjdk.org) to be able to link it to a [CSR](https://wiki.openjdk.org/display/csr/Main) request. To refer this pull request to " +
                      "an issue in JBS, please use the `/issue` command in a comment in this pull request.");
    }

    private static void linkReply(PullRequest pr, Issue issue, PrintWriter writer) {
        writer.println("@" + pr.author().username() + " please create a [CSR](https://wiki.openjdk.org/display/csr/Main) request for issue " +
                "[" + issue.id() + "](" + issue.webUrl() + ") with the correct fix version. " +
                "This pull request cannot be integrated until the CSR request is approved.");
    }

    private static void csrUnneededReply(PullRequest pr, PrintWriter writer) {
        writer.println("determined that a [CSR](https://wiki.openjdk.org/display/csr/Main) request " +
                "is not needed for this pull request.");
        writer.println(CSR_UNNEEDED_MARKER);
        if (pr.labelNames().contains(CSR_LABEL)) {
            pr.removeLabel(CSR_LABEL);
        }
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
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

            var issueProject = bot.issueProject();
            var mainIssue = org.openjdk.skara.vcs.openjdk.Issue.fromStringRelaxed(pr.title());
            if (issueProject == null || mainIssue.isEmpty()) {
                csrUnneededReply(pr, reply);
                return;
            }

            // All the issues this pr solves
            var issues = new ArrayList<org.openjdk.skara.vcs.openjdk.Issue>();
            issues.add(mainIssue.get());
            issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), pr.comments()));

            for (var issue : issues) {
                var jbsIssueOpt = issueProject.issue(issue.shortId());
                if (jbsIssueOpt.isEmpty()) {
                    continue;
                }
                var jbsIssue = jbsIssueOpt.get();

                var versionOpt = BotUtils.getVersion(pr);
                if (versionOpt.isEmpty()) {
                    break;
                }

                var csrOptional = Backports.findCsr(jbsIssue, versionOpt.get());
                if (csrOptional.isEmpty()) {
                    continue;
                }
                var csrIssue = csrOptional.get();

                var resolution = csrIssue.properties().get("resolution");
                if (resolution == null || resolution.isNull()
                        || resolution.get("name") == null || resolution.get("name").isNull()
                        || csrIssue.state() != Issue.State.CLOSED
                        || !resolution.get("name").asString().equals("Withdrawn")) {
                    // The issue has a non-withdrawn csr issue, the bot should direct the user to withdraw the csr firstly.
                    reply.println("The CSR requirement cannot be removed as there is already a CSR associated with the issue [" +
                            jbsIssue.id() + "](" + jbsIssue.webUrl() + "). Please withdraw the CSR [" + csrIssue.id() +
                            "](" + csrIssue.webUrl() + ") and then use the command `/csr unneeded` again.");
                    reply.println(CSR_NEEDED_MARKER);
                    return;
                }
            }
            // All the issues associated with this pr either don't have csr issue or the csr issue has already been withdrawn,
            // the bot should just remove the csr label and reply the message.
            csrUnneededReply(pr, reply);
            return;
        }

        if (labels.contains(CSR_LABEL)) {
            reply.println("an approved [CSR](https://wiki.openjdk.org/display/csr/Main) request " +
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

        // All the issues this pr solves
        var issues = new ArrayList<org.openjdk.skara.vcs.openjdk.Issue>();
        issues.add(mainIssue.get());
        issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), pr.comments()));

        for (var issue : issues) {
            var jbsIssueOpt = issueProject.issue(issue.shortId());
            if (jbsIssueOpt.isEmpty()) {
                continue;
            }
            var jbsIssue = jbsIssueOpt.get();
            var versionOpt = BotUtils.getVersion(pr);
            if (versionOpt.isEmpty()) {
                break;
            }

            var csrOptional = Backports.findCsr(jbsIssueOpt.get(), versionOpt.get());
            if (csrOptional.isEmpty()) {
                continue;
            }
            // Found a csr issue for one of the issues associated with this pr
            var csr = csrOptional.get();

            var resolutionName = "Unresolved";
            var resolution = csr.properties().getOrDefault("resolution", JSON.of());
            if (resolution.isObject() && resolution.asObject().contains("name")) {
                var nameField = resolution.get("name");
                if (nameField.isString()) {
                    resolutionName = resolution.get("name").asString();
                }
            }
            if (csr.state() == Issue.State.CLOSED && resolutionName.equals("Approved")) {
                reply.println("the issue for this pull request, [" + jbsIssue.id() + "](" + jbsIssue.webUrl() + "), already has " +
                        "an approved CSR request: [" + csr.id() + "](" + csr.webUrl() + ")");
                reply.println(CSR_NEEDED_MARKER);
            } else if (csr.state() == Issue.State.CLOSED && resolutionName.equals("Withdrawn")) {
                continue;
            } else {
                reply.println("this pull request will not be integrated until the [CSR](https://wiki.openjdk.org/display/csr/Main) " +
                        "request " + "[" + csr.id() + "](" + csr.webUrl() + ")" + " for issue " +
                        "[" + jbsIssue.id() + "](" + jbsIssue.webUrl() + ") has been approved.");
                reply.println(CSR_NEEDED_MARKER);
                pr.addLabel(CSR_LABEL);
            }
            return;
        }
        // All the issues associated with pr either don't have csr issue or the csr issue has already been withdrawn
        csrReply(reply);
        if (issues.size() == 1) {
            linkReply(pr, jbsMainIssueOpt.get(), reply);
        }
        pr.addLabel(CSR_LABEL);
    }

    @Override
    public String description() {
        return "require a compatibility and specification request (CSR) for this pull request";
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
