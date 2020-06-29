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
    private static final Pattern commandPattern = Pattern.compile("^(add|remove)\\s+(.+)$");

    private void showHelp(PullRequest pr, PrintWriter reply) {
        reply.println("Syntax: `/reviewer (add|remove) [@user | openjdk-user]`. For example:");
        reply.println();
        reply.println(" * `/reviewer add @openjdk-bot`");
        reply.println(" * `/reviewer add duke`");
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
            reply.println("Only the author (@" + pr.author().userName() + ") is allowed to issue the `reviewer` command.");
            return;
        }
        if (bot.ignoreStaleReviews()) {
            reply.println("This project requires authenticated reviews - please ask your reviewer to flag this PR as reviewed.");
            return;
        }

        var matcher = commandPattern.matcher(command.args());
        if (!matcher.matches()) {
            showHelp(pr, reply);
            return;
        }

        var reviewer = parseUser(matcher.group(2), pr, censusInstance);
        if (reviewer.isEmpty()) {
            reply.println("Could not parse `" + matcher.group(2) + "` as a valid reviewer.");
            showHelp(pr, reply);;
            return;
        }

        var namespace = censusInstance.namespace();
        var authenticatedReviewers = PullRequestUtils.reviewerNames(pr.reviews(), namespace);
        switch (matcher.group(1)) {
            case "add": {
                if (!authenticatedReviewers.contains(reviewer.get().username())) {
                    reply.println(Reviewers.addReviewerMarker(reviewer.get()));
                    reply.println("Reviewer `" + reviewer.get().username() + "` successfully added.");
                } else {
                    reply.println("Reviewer `" + reviewer.get().username() + "` has already made an authenticated review of this PR, and does not need to be added manually.");
                }
                break;
            }
            case "remove": {
                var existing = new HashSet<>(Reviewers.reviewers(pr.repository().forge().currentUser(), allComments));
                if (existing.contains(reviewer.get().username())) {
                    reply.println(Reviewers.removeReviewerMarker(reviewer.get()));
                    reply.println("Reviewer `" + reviewer.get().username() + "` successfully removed.");
                } else {
                    if (existing.isEmpty()) {
                        reply.println("There are no additional reviewers associated with this pull request.");
                    } else {
                        reply.println("Reviewer `" + reviewer.get().username() + "` was not found.");
                        reply.println("Current additional reviewers are:");
                        for (var e : existing) {
                            reply.println("- `" + e + "`");
                        }
                    }
                    break;
                }
                break;
            }
        }
    }

    @Override
    public String description() {
        return "adds or removes additional reviewers for a PR";
    }
}
