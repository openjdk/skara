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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ReviewersCommand implements CommandHandler {
    private static final Map<String, String> roleMappings = Map.of(
            "lead", "lead",
            "reviewers", "reviewers",
            "reviewer", "reviewers",
            "committers", "committers",
            "committer", "committers",
            "authors", "authors",
            "author", "author",
            "contributors", "contributors",
            "contributor", "contributors");

    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/reviewers <n> [<role>]` where `<n>` is the number of required reviewers. " +
                              "If role is set, the reviewers need to have that project role. If omitted, role defaults to `authors`.");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!censusInstance.isReviewer(command.user())) {
            reply.println("Only [Reviewers](https://openjdk.java.net/bylaws#reviewer) are allowed to change the number of required reviewers.");
            return;
        }

        var splitArgs = command.args().split(" ");
        if (splitArgs.length < 1 || splitArgs.length > 2) {
            showHelp(reply);
            return;
        }

        int numReviewers;
        try {
            numReviewers = Integer.parseInt(splitArgs[0]);
        } catch (NumberFormatException e) {
            showHelp(reply);
            return;
        }
        if (numReviewers > 10) {
            showHelp(reply);
            reply.println("Cannot increase the required number of reviewers above 10 (requested: " + numReviewers + ")");
            return;
        }

        String role = "authors";
        if (splitArgs.length > 1) {
            if (!roleMappings.containsKey(splitArgs[1].toLowerCase())) {
                showHelp(reply);
                reply.println("Unknown role `" + splitArgs[1] + "` specified.");
                return;
            }
            role = roleMappings.get(splitArgs[1].toLowerCase());
        }

        var updatedLimits = ReviewersTracker.updatedRoleLimits(censusInstance.configuration(), numReviewers, role);
        if (updatedLimits.get(role) > numReviewers) {
            showHelp(reply);
            reply.println("Number of required reviewers of role " + role + " cannot be decreased below " + updatedLimits.get(role));
            return;
        }

        reply.println(ReviewersTracker.setReviewersMarker(numReviewers, role));
        var totalRequired = updatedLimits.values().stream().mapToInt(Integer::intValue).sum();
        reply.print("The number of required reviews for this PR is now set to " + totalRequired);

        // Create a helpful message regarding the required distribution (if applicable)
        var nonZeroDescriptions = updatedLimits.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " of role " + entry.getKey())
                .collect(Collectors.toList());
        if (nonZeroDescriptions.size() > 1) {
            nonZeroDescriptions.remove(nonZeroDescriptions.size() - 1);
            reply.print(" (with at least " + String.join(", ", nonZeroDescriptions) + ")");
        }

        reply.println(".");
    }

    @Override
    public String description() {
        return "set the number of additional required reviewers for this PR";
    }
}
