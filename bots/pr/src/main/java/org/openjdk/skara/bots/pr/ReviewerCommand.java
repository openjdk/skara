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

import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class ReviewerCommand implements CommandHandler {
    private static final Pattern commandPattern = Pattern.compile("^(credit|remove)\\s+(.+)$");

    private void showHelp(PullRequest pr, PrintWriter reply) {
        reply.println("Syntax: `/reviewer (credit|remove) [@user | openjdk-user]+`. For example:");
        reply.println();
        reply.println(" * `/reviewer credit @openjdk-bot`");
        reply.println(" * `/reviewer credit duke`");
        reply.println(" * `/reviewer credit @user1 @user2`");
    }

    private Optional<Contributor> parseUser(String user, PullRequest pr, CensusInstance censusInstance) {
        user = user.strip();
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Contributor contributor;
        if (user.charAt(0) == '@') {
            var platformUser = pr.repository().forge().user(user.substring(1));
            if (platformUser.isEmpty()) {
                return Optional.empty();
            }
            contributor = censusInstance.namespace().get(platformUser.get().id());
            if (contributor == null) {
                return Optional.empty();
            }
        } else {
            contributor = censusInstance.census().contributor(user);
            if (contributor == null) {
                return Optional.empty();
            }
        }
        return Optional.of(contributor);
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().username() + ") is allowed to issue the `reviewer` command.");
            return;
        }

        var matcher = commandPattern.matcher(command.args());
        if (!matcher.matches()) {
            showHelp(pr, reply);
            return;
        }

        var reviewers = new ArrayList<Contributor>();
        for (var entry : matcher.group(2).split("[\\s,]+")) {
            var reviewer = parseUser(entry, pr, censusInstance);
            if (reviewer.isEmpty()) {
                reply.println("Could not parse `" + entry + "` as a valid reviewer.");
                showHelp(pr, reply);
                return;
            }

            reviewers.add(reviewer.get());
        }

        var namespace = censusInstance.namespace();
        var authenticatedReviewers = PullRequestUtils.reviewerNames(pr.reviews(), namespace);
        var action = matcher.group(1);
        if (action.equals("credit")) {
            for (var reviewer : reviewers) {
                if (!authenticatedReviewers.contains(reviewer.username())) {
                    reply.println(Reviewers.addReviewerMarker(reviewer));
                    reply.println("Reviewer `" + reviewer.username() + "` successfully credited.");
                } else {
                    reply.println("Reviewer `" + reviewer.username() + "` has already made an authenticated review of this PR, and does not need to be credited manually.");
                }
            }
        } else if (action.equals("remove")) {
            var failed = false;
            var existing = new HashSet<>(Reviewers.reviewers(pr.repository().forge().currentUser(), allComments));
            for (var reviewer : reviewers) {
                if (existing.contains(reviewer.username())) {
                    reply.println(Reviewers.removeReviewerMarker(reviewer));
                    reply.println("Reviewer `" + reviewer.username() + "` successfully removed.");
                } else {
                    if (existing.isEmpty()) {
                        reply.println("There are no manually specified reviewers associated with this pull request.");
                        failed = true;
                    } else {
                        reply.println("Reviewer `" + reviewer.username() + "` was not found.");
                        failed = true;
                    }
                }
            }

            if (failed) {
                reply.println("Current credited reviewers are:");
                for (var e : existing) {
                    reply.println("- `" + e + "`");
                }
            }
        }
    }

    @Override
    public String description() {
        return "manage additional reviewers for a PR";
    }
}
