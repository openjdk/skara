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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Pattern;

import static org.openjdk.skara.bots.common.CommandNameEnum.author;

public class AuthorCommand implements CommandHandler {
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(set|remove)\\s+(.+)$");

    private void showHelp(PrintWriter reply) {
        reply.println("Syntax: `/author (set|remove) [@user | openjdk-user | Full Name <email@address>]`. For example:");
        reply.println();
        reply.println(" * `/author set @openjdk-bot`");
        reply.println(" * `/author set duke`");
        reply.println(" * `/author set J. Duke <duke@openjdk.org>`");
        reply.println();
        reply.println("User names can only be used for users in the census associated with this repository. " +
                "For other authors you need to supply the full name and email address.");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().username() + ") is allowed to issue the `author` command.");
            return;
        }

        if (censusInstance.isCommitter(pr.author())) {
            reply.println("Only committers in this [project](https://openjdk.org/census#" + censusInstance.project().name() + ") are allowed to issue the `author` command.");
            return;
        }

        var matcher = COMMAND_PATTERN.matcher(command.args());
        if (!matcher.matches()) {
            showHelp(reply);
            return;
        }

        var author = ContributorCommand.parseUser(matcher.group(2), pr, censusInstance, reply);
        if (author.isEmpty()) {
            reply.println();
            showHelp(reply);
            return;
        }

        switch (matcher.group(1)) {
            case "set": {
                reply.println(Authors.setAuthorMarker(author.get()));
                reply.println("Author of this pull request has been set to `" + author.get() + "` successfully.");
                break;
            }
            case "remove": {
                var currAuthor = Authors.author(pr.repository().forge().currentUser(), allComments);
                if (currAuthor.isEmpty()) {
                    reply.println("There is no author set in this pull request.");
                } else {
                    if (currAuthor.get().equals(author.get())) {
                        reply.println(Authors.removeAuthorMarker(author.get()));
                        reply.println("Author `" + author.get() + "` successfully removed.");
                    } else {
                        reply.println("`" + author.get() + "` was not set to this pull request's author.");
                        reply.println("Current author of this pull request is set to: `" + currAuthor.get() + "`");
                    }
                }
                break;
            }
        }
    }

    @Override
    public String description() {
        return "sets or removes author for a PR";
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
