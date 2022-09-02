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
import java.util.stream.Collectors;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.openjdk.Issue;

public class RequestApprovalCommand implements CommandHandler {
    // The tag to re-run the CheckWorkItem of the PRBot.
    private static final String REQUEST_APPROVAL_MARKER = "<!-- request-approval: 'update' -->";

    private void showHelp(PullRequest pr, PrintWriter writer) {
        writer.println(String.format("""
                usage: `/request-approval <comment-text>`

                examples:
                ```
                /request-approval Fix Request (%s)
                The code applies cleanly and the test in this change fails without the patch and succeeds \
                after applying it. The risk of this backport is low because the change is little and the \
                issue fixed by this change also exists in other update repositories.
                ```

                Note: only the pull request author is allowed to use the `request-approval` command.
                """, pr.repository().name()));
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath,
                       CommandInvocation command, List<Comment> allComments, PrintWriter reply, PullRequestWorkItem workItem) {
        if (!workItem.requiresApproval()) {
            reply.println("this repository or the target branch of this pull request have not been configured to use the `request-approval` command.");
            return;
        }

        if (!pr.author().equals(command.user())) {
            reply.println("only the pull request author is allowed to use the `request-approval` command.");
            return;
        }

        if (command.args().isBlank()) {
            showHelp(pr, reply);
            return;
        }

        var vcsIssue = Issue.fromStringRelaxed(pr.title());
        if (vcsIssue.isEmpty()) {
            reply.println("the title of the pull request doesn't contain the main issue.");
            return;
        }

        var issueOpt = bot.issueProject().issue(vcsIssue.get().shortId());
        if (issueOpt.isEmpty()) {
            reply.println("the main issue of the pull request title is not found.");
            return;
        }

        var comment = command.args().lines().map(String::strip)
                .collect(Collectors.joining("\n"));
        issueOpt.get().addComment(comment);
        reply.println("the text you provide has been successfully added to the main issue as a comment.");
        reply.println(REQUEST_APPROVAL_MARKER);
    }

    @Override
    public String description() {
        return "add a comment to the main issue of a pull request which needs maintainer's approval";
    }

    @Override
    public boolean multiLine() {
        return true;
    }
}
