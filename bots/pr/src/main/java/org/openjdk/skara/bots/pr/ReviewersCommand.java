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
        reply.println("Usage: `/reviewers <n> [<role>]` where `<n>` is the additional number of required reviewers. " +
                              "If role is set, the reviewers need to have that project role. If omitted, role defaults to `committers`.");
    }

    @Override
    public void handle(PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (!ProjectPermissions.mayReview(censusInstance, comment.author())) {
            reply.println("Only [Reviewers](https://openjdk.java.net/bylaws#reviewer) are allowed to increase the number of required reviewers.");
            return;
        }

        var splitArgs = args.split(" ");
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

        if (numReviewers < 0 || numReviewers > 10) {
            showHelp(reply);
            reply.println("Number of additional required reviewers has to be between 0 and 10.");
            return;
        }

        String role = "committers";
        if (splitArgs.length > 1) {
            if (!roleMappings.containsKey(splitArgs[1].toLowerCase())) {
                showHelp(reply);
                reply.println("Unknown role `" + splitArgs[1] + "` specified.");
                return;
            }
            role = roleMappings.get(splitArgs[1].toLowerCase());
        }

        reply.println(ReviewersTracker.setReviewersMarker(numReviewers, role));
        reply.println("The number of additional required reviews from " + role + " is now set to " + numReviewers + ".");
    }

    @Override
    public String description() {
        return "set the number of additional required reviewers for this PR";
    }
}
