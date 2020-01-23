/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class SolvesCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("To add an additional issue to the list of issues that this PR solves: `/solves <id>: <description>`." +
                              "To remove a previously added additional issue: `/solves <id>`.");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (!comment.author().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().userName() + ") is allowed to issue the `solves` command.");
            return;
        }

        if (args.isBlank()) {
            showHelp(reply);
            return;
        }

        var currentSolved = SolvesTracker.currentSolved(pr.repository().forge().currentUser(), allComments)
                                         .stream()
                                         .map(Issue::id)
                                         .collect(Collectors.toSet());

        var issue = Issue.fromString(args);
        if (issue.isEmpty()) {
            issue = Issue.fromString(args + ": deleteme");
            if (issue.isEmpty()) {
                reply.println("Invalid command syntax.");
                showHelp(reply);
                return;
            }

            if (currentSolved.contains(issue.get().id())) {
                reply.println(SolvesTracker.removeSolvesMarker(issue.get()));;
                reply.println("Removing additional issue from solves list: `" + issue.get().id() + "`.");
            } else {
                reply.println("Could not find issue `" + issue.get().id() + "` in the list of additional solved issues.");
            }
            return;
        }

        var titleIssue = Issue.fromString(pr.title());
        if (titleIssue.isEmpty()) {
            reply.print("The primary solved issue for a PR is set through the PR title. Since the current title does ");
            reply.println("not contain an issue reference, it will now be updated.");
            pr.setTitle(issue.get().toString());
            return;
        }
        if (titleIssue.get().id().equals(issue.get().id())) {
            reply.println("This issue is referenced in the PR title - it will now be updated.");
            pr.setTitle(issue.get().toString());
            return;
        }
        reply.println(SolvesTracker.setSolvesMarker(issue.get()));
        if (currentSolved.contains(issue.get().id())) {
            reply.println("Updating description of additional solved issue: `" + issue.get().toString() + "`.");
        } else {
            reply.println("Adding additional issue to solves list: `" + issue.get().toString() + "`.");
        }
    }

    @Override
    public String description() {
        return "add an additional issue that this PR solves";
    }
}
