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

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;

public class CommitCommandWorkItem implements WorkItem {
    private final PullRequestBot bot;
    private final CommitComment commitComment;

    private static final String commandReplyMarker = "<!-- Jmerge command reply message (%s) -->";
    private static final Pattern commandReplyPattern = Pattern.compile("<!-- Jmerge command reply message \\((\\S+)\\) -->");

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private static final Map<String, CommandHandler> commandHandlers = Map.ofEntries(
            Map.entry("help", new HelpCommand())
    );

    static class HelpCommand implements CommandHandler {
        @Override
        public void handleCommit(PullRequestBot bot, Hash hash, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
            reply.println("Available commands:");
            Stream.concat(
                    commandHandlers.entrySet().stream()
                                   .map(entry -> entry.getKey() + " - " + entry.getValue().description()),
                    bot.externalCommands().entrySet().stream()
                       .map(entry -> entry.getKey() + " - " + entry.getValue())
            ).sorted().forEachOrdered(c -> reply.println(" * " + c));
        }

        @Override
        public String description() {
            return "shows this text";
        }

        @Override
        public boolean allowedInCommit() {
            return true;
        }
    }

    CommitCommandWorkItem(PullRequestBot bot, CommitComment commitComment) {
        this.bot = bot;
        this.commitComment = commitComment;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CommitCommandWorkItem)) {
            return true;
        }
        CommitCommandWorkItem otherItem = (CommitCommandWorkItem) other;
        if (!bot.repo().webUrl().equals(otherItem.bot.repo().webUrl())) {
            return true;
        }
        if (!commitComment.commit().equals(otherItem.commitComment.commit())) {
            return true;
        }
        return false;
    }

    private Optional<CommandInvocation> nextCommand(List<CommitComment> allComments) {
        var self = bot.repo().forge().currentUser();
        var command = CommandExtractor.extractCommands(commandHandlers, commitComment.body(),
                                                       commitComment.id(), commitComment.author());
        if (command.isEmpty()) {
            return Optional.empty();
        }

        var handled = allComments.stream()
                              .filter(c -> c.author().equals(self))
                              .map(c -> commandReplyPattern.matcher(c.body()))
                              .filter(Matcher::find)
                              .map(matcher -> matcher.group(1))
                              .collect(Collectors.toSet());

        if (handled.contains(commitComment.id())) {
            return Optional.empty();
        } else {
            return Optional.of(command.get(0));
        }
    }

    private void processCommand(Path scratchPath, CommandInvocation command, List<CommitComment> allComments) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println(String.format(commandReplyMarker, command.id()));

        var handler = command.handler();
        if (handler.isPresent()) {
            if (handler.get().allowedInCommit()) {
                var comments = allComments.stream()
                                          .map(cc -> (Comment)cc)
                                          .collect(Collectors.toList());
                handler.get().handleCommit(bot, commitComment.commit(), scratchPath, command, comments, printer);
            } else {
                printer.print("The command `");
                printer.print(command.name());
                printer.println("` can only be used in pull requests.");
            }
        } else {
            printer.print("Unknown command `");
            printer.print(command.name());
            printer.println("` - for a list of valid commands use `/help`.");
        }

        bot.repo().addCommitComment(commitComment.commit(), writer.toString());
    }
    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        log.info("Looking for commit comment commands");

        var allComments = bot.repo().commitComments(commitComment.commit());
        var nextCommand = nextCommand(allComments);

        if (nextCommand.isEmpty()) {
            log.info("No new commit comments found, stopping further processing");
        } else {
            processCommand(scratchPath, nextCommand.get(), allComments);
        }

        return List.of();
    }
}
