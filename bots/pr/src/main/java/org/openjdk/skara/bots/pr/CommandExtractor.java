/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.time.ZonedDateTime;
import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.host.HostUser;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CommandExtractor {
    private static final Pattern commandPattern = Pattern.compile("^\\s*/([A-Za-z\\-]+)(?:\\s+(.*))?");

    private static String formatId(String baseId, int subId) {
        if (subId > 0) {
            return String.format("%s:%d", baseId, subId);
        } else {
            return baseId;
        }
    }

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
            Map.entry("jep", new JEPCommand()),
            Map.entry("reviewer", new ReviewerCommand()),
            Map.entry("label", new LabelCommand()),
            Map.entry("cc", new LabelCommand("cc")),
            Map.entry("clean", new CleanCommand()),
            Map.entry("open", new OpenCommand()),
            Map.entry("backport", new BackportCommand()),
            Map.entry("approve", new ApproveCommand()),
            Map.entry("request-approval", new RequestApprovalCommand()),
            Map.entry("tag", new TagCommand())
    );

    static class HelpCommand implements CommandHandler {
        @Override
        public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
            reply.println("Available commands:");
            Stream.concat(
                    commandHandlers.entrySet().stream()
                            .filter(entry -> entry.getValue().allowedInPullRequest())
                            .map(entry -> entry.getKey() + " - " + entry.getValue().description()),
                    bot.externalPullRequestCommands().entrySet().stream()
                            .map(entry -> entry.getKey() + " - " + entry.getValue())
            ).sorted().forEachOrdered(c -> reply.println(" * " + c));
        }

        @Override
        public void handle(PullRequestBot bot, HostedCommit hash, LimitedCensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
            reply.println("Available commands:");
            Stream.concat(
                    commandHandlers.entrySet().stream()
                            .filter(entry -> entry.getValue().allowedInCommit())
                            .map(entry -> entry.getKey() + " - " + entry.getValue().description()),
                    bot.externalCommitCommands().entrySet().stream()
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

    static List<CommandInvocation> extractCommands(String text, String baseId, HostUser user, ZonedDateTime createdAt) {
        var ret = new ArrayList<CommandInvocation>();
        CommandHandler multiLineHandler = null;
        List<String> multiLineBuffer = null;
        String multiLineCommand = null;
        int subId = 0;
        for (var line : text.split("\\R")) {
            var commandMatcher = commandPattern.matcher(line);
            if (commandMatcher.matches()) {
                if (multiLineHandler != null) {
                    ret.add(new CommandInvocation(formatId(baseId, subId++), user, multiLineHandler, multiLineCommand,
                            String.join("\n", multiLineBuffer), createdAt));
                    multiLineHandler = null;
                }
                var command = commandMatcher.group(1).toLowerCase();
                var handler = commandHandlers.get(command);
                if (handler != null && handler.multiLine()) {
                    multiLineHandler = handler;
                    multiLineBuffer = new ArrayList<>();
                    if (commandMatcher.group(2) != null) {
                        multiLineBuffer.add(commandMatcher.group(2));
                    }
                    multiLineCommand = command;
                } else {
                    ret.add(new CommandInvocation(formatId(baseId, subId++), user, handler, command,
                            commandMatcher.group(2), createdAt));
                }
            } else {
                if (multiLineHandler != null) {
                    multiLineBuffer.add(line);
                }
            }
        }
        if (multiLineHandler != null) {
            ret.add(new CommandInvocation(formatId(baseId, subId), user, multiLineHandler,
                    multiLineCommand, String.join("\n", multiLineBuffer), createdAt));
        }
        return ret;
    }

}
