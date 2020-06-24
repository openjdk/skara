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

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class SummaryCommand implements CommandHandler {
    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (!comment.author().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().userName() + ") is allowed to issue the `/summary` command.");
            return;
        }

        var currentSummary = Summary.summary(pr.repository().forge().currentUser(), allComments);

        if (args.isBlank()) {
            var lines = Arrays.asList(comment.body().split("\n"));
            if (lines.size() == 1) {
                if (currentSummary.isPresent()) {
                    reply.println("Removing existing summary");
                    reply.println(Summary.setSummaryMarker(""));
                } else {
                    reply.println("To set a summary, use the syntax `/summary <summary text>`");
                }
            } else {
                // A multi-line summary
                var summary = String.join("\n", lines.subList(1, lines.size()));
                var action = currentSummary.isPresent() ? "Updating existing" : "Setting";
                reply.println(action + " summary to:\n" +
                              "\n" +
                              "```\n" +
                              summary +
                              "\n```");
                reply.println(Summary.setSummaryMarker(summary));
            }
        } else {
            // A single-line summary
            var summary = args.strip();
            var action = currentSummary.isPresent() ? "Updating existing" : "Setting";
            reply.println(action + " summary to `" + summary + "`");
            reply.println(Summary.setSummaryMarker(summary));
        }
    }

    @Override
    public String description() {
        return "updates the summary in the commit message";
    }
}
