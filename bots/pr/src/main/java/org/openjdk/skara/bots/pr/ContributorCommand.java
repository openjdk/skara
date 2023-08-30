/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.*;
import java.util.regex.Pattern;

import static org.openjdk.skara.bots.common.CommandNameEnum.contributor;

public class ContributorCommand implements CommandHandler {
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(add|remove)\\s+(.+)$");

    private void showHelp(PullRequest pr, PrintWriter reply) {
        reply.println("Syntax: `/contributor (add|remove) [@user | openjdk-user | Full Name <email@address>]`. For example:");
        reply.println();
        reply.println(" * `/contributor add @openjdk-bot`");
        reply.println(" * `/contributor add duke`");
        reply.println(" * `/contributor add J. Duke <duke@openjdk.org>`");
        reply.println();
        reply.println("User names can only be used for users in the census associated with this repository. " +
                "For other contributors you need to supply the full name and email address.");
    }

    public static Optional<EmailAddress> parseUser(String user, PullRequest pr, CensusInstance censusInstance, PrintWriter reply) {
        user = user.strip();
        if (user.isEmpty()) {
            reply.println("Username parameter is empty.");
            return Optional.empty();
        }
        Contributor contributor;
        if (user.charAt(0) == '@') {
            var platformUser = pr.repository().forge().user(user.substring(1));
            if (platformUser.isEmpty()) {
                reply.println("`" + user + "` is not a valid user in this repository.");
                return Optional.empty();
            }
            contributor = censusInstance.namespace().get(platformUser.get().id());
            if (contributor == null) {
                reply.println("`" + user + "` was not found in the census.");
                return Optional.empty();
            }
        } else if (!user.contains("@")) {
            contributor = censusInstance.census().contributor(user);
            if (contributor == null) {
                reply.println("`" + user + "` was not found in the census.");
                return Optional.empty();
            }
        } else {
            try {
                var email = EmailAddress.parse(user);
                if (email.fullName().isPresent()) {
                    return Optional.of(email);
                } else {
                    reply.println("`" + user + "` is not a valid name and email string.");
                    return Optional.empty();
                }
            } catch (RuntimeException e) {
                reply.println("`" + user + "` is not a valid name and email string.");
                return Optional.empty();
            }
        }

        if (contributor.fullName().isPresent()) {
            return Optional.of(EmailAddress.from(contributor.fullName().get(), contributor.username() + "@" +
                    censusInstance.configuration().census().domain()));
        } else {
            reply.println("`" + user + "` does not have a full name recorded in the census.");
            return Optional.empty();
        }
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().username() + ") is allowed to issue the `contributor` command.");
            return;
        }

        var matcher = COMMAND_PATTERN.matcher(command.args());
        if (!matcher.matches()) {
            showHelp(pr, reply);
            return;
        }

        var contributor = parseUser(matcher.group(2), pr, censusInstance, reply);
        if (contributor.isEmpty()) {
            reply.println();
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

    @Override
    public String name() {
        return contributor.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
