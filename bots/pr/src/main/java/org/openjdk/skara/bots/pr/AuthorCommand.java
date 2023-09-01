/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Pattern;

import static org.openjdk.skara.bots.common.CommandNameEnum.author;

public class AuthorCommand implements CommandHandler {
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(set|remove)?\\s*(.+)?$");

    private void showHelp(PrintWriter reply) {
        reply.println("Syntax: `/author [set|remove] [@user | openjdk-user | Full Name <email@address>]`. For example:");
        reply.println();
        reply.println(" * `/author set @openjdk-bot`");
        reply.println(" * `/author set duke`");
        reply.println(" * `/author set J. Duke <duke@openjdk.org>`");
        reply.println(" * `/author @openjdk-bot`");
        reply.println(" * `/author remove @openjdk-bot`");
        reply.println(" * `/author remove`");
        reply.println();
        reply.println("User names can only be used for users in the census associated with this repository. " +
                "For other authors you need to supply the full name and email address.");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the pull request author (@" + pr.author().username() + ") is allowed to issue the `author` command.");
            return;
        }

        if (!censusInstance.isCommitter(pr.author())) {
            reply.println("Only [Committers](https://openjdk.org/bylaws#committer) are allowed to issue the `author` command.");
            return;
        }

        var matcher = COMMAND_PATTERN.matcher(command.args());
        if (!matcher.matches()) {
            showHelp(reply);
            return;
        }

        String option = matcher.group(1);
        if (option == null) {
            option = "set";
        }

        String authorArg = matcher.group(2);

        switch (option) {
            case "set": {
                if (authorArg == null) {
                    reply.println();
                    showHelp(reply);
                    return;
                }
                var author = ContributorCommand.parseUser(authorArg, pr, censusInstance, reply);
                if (author.isEmpty()) {
                    reply.println();
                    showHelp(reply);
                    return;
                }
                reply.println(OverridingAuthor.setAuthorMarker(author.get()));
                reply.println("Setting overriding author to `" + author.get() + "`. When this pull request is integrated, the overriding author will be used in the commit.");
                break;
            }
            case "remove": {
                var currAuthor = OverridingAuthor.author(pr.repository().forge().currentUser(), allComments);
                Optional<EmailAddress> author;
                if (authorArg == null) {
                    author = currAuthor;
                } else {
                    author = ContributorCommand.parseUser(authorArg, pr, censusInstance, reply);
                    if (author.isEmpty()) {
                        reply.println();
                        showHelp(reply);
                        return;
                    }
                }
                if (currAuthor.isEmpty()) {
                    reply.println("There is no overriding author set for this pull request.");
                } else {
                    if (currAuthor.get().equals(author.get())) {
                        reply.println(OverridingAuthor.removeAuthorMarker(author.get()));
                        reply.println("Overriding author `" + author.get() + "` was successfully removed. When this pull request is integrated, the pull request author will be used as the author of the commit.");
                    } else {
                        reply.println("Cannot remove `" + author.get() + "`, the overriding author is currently set to: `" + currAuthor.get() + "`");
                    }
                }
                break;
            }
        }
    }

    @Override
    public String description() {
        return "sets an overriding author to be used in the commit when the PR is integrated";
    }

    @Override
    public String name() {
        return author.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
