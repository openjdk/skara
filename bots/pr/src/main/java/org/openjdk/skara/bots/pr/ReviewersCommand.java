/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.*;
import java.util.stream.Collectors;

import static org.openjdk.skara.jcheck.ReviewersConfiguration.BYLAWS_URL;
import static org.openjdk.skara.bots.common.CommandNameEnum.reviewers;

public class ReviewersCommand implements CommandHandler {
    private static final Map<String, String> ROLE_MAPPINGS = Map.of(
            "lead", "lead",
            "reviewers", "reviewers",
            "reviewer", "reviewers",
            "committers", "committers",
            "committer", "committers",
            "authors", "authors",
            "author", "authors",
            "contributors", "contributors",
            "contributor", "contributors");

    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/reviewers <n> [<role>]` where `<n>` is the number of required reviewers. " +
                              "If role is set, the reviewers need to have that project role. If omitted, role defaults to `authors`.");
    }

    private static boolean roleIsLower(String updated, String existing) {
        if (existing.equals("lead")) {
            return !updated.equals("lead");
        }
        if (existing.equals("reviewers")) {
            return !updated.equals("lead") &&
                   !updated.equals("reviewers");
        }
        if (existing.equals("committers")) {
            return !updated.equals("lead") &&
                   !updated.equals("reviewers") &&
                   !updated.equals("committers");
        }
        if (existing.equals("authors")) {
            return !updated.equals("lead") &&
                   !updated.equals("reviewers") &&
                   !updated.equals("committers") &&
                   !updated.equals("authors");
        }
        if (existing.equals("contributors")) {
            return false;
        }
        throw new IllegalArgumentException("Unexpected existing role: " + existing);
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
            reply.println("Only the author of the pull request or [Reviewers](https://openjdk.org/bylaws#reviewer) are allowed to change the number of required reviewers.");
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
        if (numReviewers < 0) {
            showHelp(reply);
            reply.println("Cannot decrease the required number of reviewers below 0 (requested: " + numReviewers + ")");
            return;
        }

        String role = "authors";
        if (splitArgs.length > 1) {
            if (!ROLE_MAPPINGS.containsKey(splitArgs[1].toLowerCase())) {
                showHelp(reply);
                reply.println("Unknown role `" + splitArgs[1] + "` specified.");
                return;
            }
            role = ROLE_MAPPINGS.get(splitArgs[1].toLowerCase());
        }

        if (pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
            var user = pr.repository().forge().currentUser();
            var previous = ReviewersTracker.additionalRequiredReviewers(user, allComments);
            if (previous.isPresent()) {
                if (roleIsLower(role, previous.get().role())) {
                    reply.println("Only [Reviewers](https://openjdk.org/bylaws#reviewer) are allowed to lower the role for additional reviewers.");
                    return;
                }
                if (numReviewers < previous.get().number()) {
                    reply.println("Only [Reviewers](https://openjdk.org/bylaws#reviewer) are allowed to decrease the number of required reviewers.");
                    return;
                }
            }
        }

        var updatedLimits = ReviewersTracker.updatedRoleLimits(censusInstance.configuration(), numReviewers, role);
        // The role name of the configuration should be changed to the official role name.
        var formatLimits = new LinkedHashMap<String, Integer>();
        formatLimits.put("[Lead%s](%s#project-lead)", updatedLimits.get("lead"));
        formatLimits.put("[Reviewer%s](%s#reviewer)", updatedLimits.get("reviewers"));
        formatLimits.put("[Committer%s](%s#committer)", updatedLimits.get("committers"));
        formatLimits.put("[Author%s](%s#author)", updatedLimits.get("authors"));
        formatLimits.put("[Contributor%s](%s#contributor)", updatedLimits.get("contributors"));

        reply.println(ReviewersTracker.setReviewersMarker(numReviewers, role));
        var totalRequired = formatLimits.values().stream().mapToInt(Integer::intValue).sum();
        if (pr.labelNames().contains("clean") && pr.labelNames().contains("backport")) {
            reply.println("Warning: By issuing the /reviewers command in this clean backport pull request, the reviewers check has now been enabled.");
        }
        reply.print("The total number of required reviews for this PR (including the jcheck configuration " +
                    "and the last /reviewers command) is now set to " + totalRequired);

        // Create a helpful message regarding the required distribution (if applicable)
        var nonZeroDescriptions = formatLimits.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + String.format(entry.getKey(), entry.getValue() > 1 ? "s" : "", BYLAWS_URL))
                .collect(Collectors.toList());
        if (nonZeroDescriptions.size() > 0) {
            reply.print(" (with at least " + String.join(", ", nonZeroDescriptions) + ")");
        }

        reply.println(".");
    }

    @Override
    public String description() {
        return "set the number of additional required reviewers for this PR";
    }

    @Override
    public String name() {
        return reviewers.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
