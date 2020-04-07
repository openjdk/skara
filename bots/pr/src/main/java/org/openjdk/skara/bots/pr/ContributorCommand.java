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

import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class ContributorCommand implements CommandHandler {
    private static final Pattern commandPattern = Pattern.compile("^(add|remove)\\s+(.+)$");

    private void showHelp(PullRequest pr, PrintWriter reply) {
        reply.println("Syntax: `/contributor (add|remove) [@user | openjdk-user | Full Name <email@address>]`. For example:");
        reply.println();
        reply.println(" * `/contributor add @" + pr.repository().forge().name() + "-User`");
        reply.println(" * `/contributor add duke`");
        reply.println(" * `/contributor add J. Duke <duke@openjdk.org>`");
    }

    private Optional<EmailAddress> parseUser(String user, PullRequest pr, CensusInstance censusInstance) {
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
        } else if (!user.contains("@")) {
            contributor = censusInstance.census().contributor(user);
            if (contributor == null) {
                return Optional.empty();
            }
        } else {
            try {
                return Optional.of(EmailAddress.parse(user));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        if (contributor.fullName().isPresent()) {
            return Optional.of(EmailAddress.from(contributor.fullName().get(), contributor.username() + "@openjdk.org"));
        } else {
            return Optional.of(EmailAddress.from(contributor.username() + "@openjdk.org"));
        }
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (!comment.author().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().userName() + ") is allowed to issue the `contributor` command.");
            return;
        }

        var matcher = commandPattern.matcher(args);
        if (!matcher.matches()) {
            showHelp(pr, reply);
            return;
        }

        var contributor = parseUser(matcher.group(2), pr, censusInstance);
        if (contributor.isEmpty()) {
            reply.println("Could not parse `" + matcher.group(2) + "` as a valid contributor.");
            showHelp(pr, reply);;
            return;
        }

        switch (matcher.group(1)) {
            case "add": {
                reply.println(Contributors.addContributorMarker(contributor.get()));
                reply.println("Contributor `" + contributor.get().toString() + "` successfully added.");
                break;
            }
            case "remove": {
                var existing = new HashSet<>(Contributors.contributors(pr.repository().forge().currentUser(), allComments));
                if (existing.contains(contributor.get())) {
                    reply.println(Contributors.removeContributorMarker(contributor.get()));
                    reply.println("Contributor `" + contributor.get().toString() + "` successfully removed.");
                } else {
                    if (existing.isEmpty()) {
                        reply.println("There are no additional contributors associated with this pull request.");
                    } else {
                        reply.println("Contributor `" + contributor.get().toString() + "` was not found.");
                        reply.println("Current additional contributors are:");
                        for (var e : existing) {
                            reply.println("- `" + e.toString() + "`");
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
        return "adds or removes additional contributors for a PR";
    }
}
