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

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;

public class CommandWorkItem extends PullRequestWorkItem {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private static final Pattern commandPattern = Pattern.compile("^\\s*/(.*)");
    private static final String commandReplyMarker = "<!-- Jmerge command reply message (%s) -->";
    private static final Pattern commandReplyPattern = Pattern.compile("<!-- Jmerge command reply message \\((\\S+)\\) -->");
    private static final String selfCommandMarker = "<!-- Valid self-command -->";

    private static final Map<String, CommandHandler> commandHandlers = Map.ofEntries(
            Map.entry("help", new HelpCommand()),
            Map.entry("integrate", new IntegrateCommand()),
            Map.entry("sponsor", new SponsorCommand()),
            Map.entry("contributor", new ContributorCommand()),
            Map.entry("summary", new SummaryCommand()),
            Map.entry("issue", new IssueCommand()),
            Map.entry("solves", new IssueCommand("solves")),
            Map.entry("reviewers", new ReviewersCommand()),
            Map.entry("csr", new CSRCommand()),
            Map.entry("reviewer", new ReviewerCommand()),
            Map.entry("label", new LabelCommand()),
            Map.entry("cc", new LabelCommand("cc"))
    );

    static class HelpCommand implements CommandHandler {
        static private Map<String, String> external = null;

        @Override
        public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
            reply.println("Available commands:");
            Stream.concat(
                    commandHandlers.entrySet().stream()
                                   .map(entry -> entry.getKey() + " - " + entry.getValue().description()),
                    external.entrySet().stream()
                            .map(entry -> entry.getKey() + " - " + entry.getValue())
            ).sorted().forEachOrdered(command -> reply.println(" * " + command));
        }

        @Override
        public String description() {
            return "shows this text";
        }
    }

    CommandWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        super(bot, pr, errorHandler);
    }

    private List<AbstractMap.SimpleEntry<String, Comment>> findCommandComments(List<Comment> comments) {
        var self = pr.repository().forge().currentUser();
        var handled = comments.stream()
                              .filter(comment -> comment.author().equals(self))
                              .map(comment -> commandReplyPattern.matcher(comment.body()))
                              .filter(Matcher::find)
                              .map(matcher -> matcher.group(1))
                              .collect(Collectors.toSet());

        return comments.stream()
                       .filter(comment -> !comment.author().equals(self) || comment.body().endsWith(selfCommandMarker))
                       .map(comment -> new AbstractMap.SimpleEntry<>(comment, commandPattern.matcher(comment.body())))
                       .filter(entry -> entry.getValue().find())
                       .filter(entry -> !handled.contains(entry.getKey().id()))
                       .map(entry -> new AbstractMap.SimpleEntry<>(entry.getValue().group(1), entry.getKey()))
                       .collect(Collectors.toList());
    }

    private List<CommandInvocation> extractCommands(String text, String baseId) {
        var activeCommandBuffer = new StringBuilder();
        for (var line : text.split("\\R")) {
            var commandMatcher = commandPattern.matcher(line);
            if (commandMatcher.matches()) {
                var handler = commandHandlers.get(commandMatcher.group(1).toLowerCase());
                
            } else {

            }
        }
    }

    private List<CommandInvocation> findCommands(String body, List<Comment> comments) {

    }

    private void processCommand(PullRequest pr, CensusInstance censusInstance, Path scratchPath, String command, Comment comment, List<Comment> allComments) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println(String.format(commandReplyMarker, comment.id()));
        printer.print("@");
        printer.print(comment.author().userName());
        printer.print(" ");

        command = command.strip();
        var argSplit = command.indexOf(' ');
        var commandWord = argSplit > 0 ? command.substring(0, argSplit) : command;
        var commandArgs = argSplit > 0 ? command.substring(argSplit + 1) : "";

        var handler = commandHandlers.get(commandWord);
        if (handler != null) {
            handler.handle(bot, pr, censusInstance, scratchPath, commandArgs, comment, allComments, printer);
        } else {
            if (!bot.externalCommands().containsKey(commandWord)) {
                printer.print("Unknown command `");
                printer.print(command);
                printer.println("` - for a list of valid commands use `/help`.");
            } else {
                // Do not reply to external commands
                return;
            }
        }

        pr.addComment(writer.toString());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        log.info("Looking for merge commands");

        if (pr.labels().contains("integrated")) {
            log.info("Skip checking for commands in integrated PR");
            return List.of();
        }

        var comments = pr.comments();
        var unprocessedCommands = findCommandComments(comments);
        if (unprocessedCommands.isEmpty()) {
            log.fine("No new merge commands found, stopping further processing");
            return List.of();
        }

        if (HelpCommand.external == null) {
            HelpCommand.external = bot.externalCommands();
        }

        var census = CensusInstance.create(bot.censusRepo(), bot.censusRef(), scratchPath.resolve("census"), pr);
        for (var entry : unprocessedCommands) {
            processCommand(pr, census, scratchPath.resolve("pr").resolve("command"), entry.getKey(), entry.getValue(), comments);
        }

        // Run another check to reflect potential changes from commands
        return List.of(new CheckWorkItem(bot, pr, errorHandler));
    }

    @Override
    public String toString() {
        return "CommandWorkItem@" + pr.repository().name() + "#" + pr.id();
    }
}
