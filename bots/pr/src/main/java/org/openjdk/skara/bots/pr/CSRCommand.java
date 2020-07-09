/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSON;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

public class CSRCommand implements CommandHandler {
    private static final String CSR_LABEL = "csr";

    private static void showHelp(PrintWriter writer) {
        writer.println("usage: `/csr [needed|unneeded]`, requires that the issue the pull request refers to links to an approved [CSR](https://wiki.openjdk.java.net/display/csr/Main) request.");
    }

    private static void csrReply(PrintWriter writer) {
        writer.println("has indicated that a " +
                      "[compatibility and specification](https://wiki.openjdk.java.net/display/csr/Main) (CSR) request " +
                      "is needed for this pull request.");
    }

    private static void jbsReply(PullRequest pr, PrintWriter writer) {
        writer.println("@" + pr.author().userName() + " this pull request must refer to an issue in " +
                      "[JBS](https://bugs.openjdk.java.net) to be able to link it to a CSR request. To refer this pull request to " +
                      "an issue in JBS, please use the `/issue` command in a comment in this pull request.");
    }

    private static void linkReply(PullRequest pr, Issue issue, PrintWriter writer) {
        writer.println("@" + pr.author().userName() + " please create a CSR request and add link to it in " +
                      "[" + issue.id() + "](" + issue.webUrl() + "). This pull request cannot be integrated until " +
                      "the CSR request is approved.");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!censusInstance.isReviewer(command.user())) {
            reply.println("only [Reviewers](https://openjdk.java.net/bylaws#reviewer) are allowed require a CSR.");
            return;
        }

        var labels = pr.labels();

        var cmd = command.args().trim().toLowerCase();
        if (cmd.equals("unneeded") || cmd.equals("uneeded")) {
            if (labels.contains(CSR_LABEL)) {
                pr.removeLabel(CSR_LABEL);
            }
            reply.println("determined that a [CSR](https://wiki.openjdk.java.net/display/csr/Main) request " +
                          "is no longer needed for this pull request.");
            return;
        } else if (!cmd.isEmpty() && !cmd.equals("needed")) {
            showHelp(reply);
            return;
        }

        if (labels.contains(CSR_LABEL)) {
            reply.println("an approved [CSR](https://wiki.openjdk.java.net/display/csr/Main) request " +
                          "is already required for this pull request.");
            return;
        }

        var issueProject = bot.issueProject();
        var issue = org.openjdk.skara.vcs.openjdk.Issue.fromStringRelaxed(pr.title());
        if (issue.isEmpty()) {
            csrReply(reply);
            jbsReply(pr, reply);
            pr.addLabel(CSR_LABEL);
            return;
        }

        var jbsIssue = issueProject.issue(issue.get().shortId());
        if (jbsIssue.isEmpty()) {
            csrReply(reply);
            jbsReply(pr, reply);
            pr.addLabel(CSR_LABEL);
            return;

        }
        Issue csr = null;
        for (var link : jbsIssue.get().links()) {
            var relationship = link.relationship();
            if (relationship.isPresent() && relationship.get().equals("csr for")) {
                csr = link.issue().orElseThrow(
                        () -> new IllegalStateException("Link with title 'csr for' does not contain issue")
                );
            }
        }

        if (csr == null && !labels.contains(CSR_LABEL)) {
            csrReply(reply);
            linkReply(pr, jbsIssue.get(), reply);
            pr.addLabel(CSR_LABEL);
            return;
        }

        var resolutionName = "Unresolved";
        var resolution = csr.properties().getOrDefault("resolution", JSON.of());
        if (resolution.isObject() && resolution.asObject().contains("name")) {
            var nameField = resolution.get("name");
            if (nameField.isString()) {
                resolutionName = resolution.get("name").asString();
            }
        }
        if (csr.state() == Issue.State.CLOSED && resolutionName.equals("Approved")) {
            reply.println("the issue for this pull request, [" + jbsIssue.get().id() + "](" + jbsIssue.get().webUrl() + "), already has " +
                          "an approved CSR request: [" + csr.id() + "](" + csr.webUrl() + ")");
        } else {
            reply.println("this pull request will not be integrated until the [CSR](https://wiki.openjdk.java.net/display/csr/Main) " +
                          "request " + "[" + csr.id() + "](" + csr.webUrl() + ")" + " for issue " +
                          "[" + jbsIssue.get().id() + "](" + jbsIssue.get().webUrl() + ") has been approved.");
            pr.addLabel(CSR_LABEL);
        }
    }

    @Override
    public String description() {
        return "require a compatibility and specification request (CSR) for this pull request";
    }
}
