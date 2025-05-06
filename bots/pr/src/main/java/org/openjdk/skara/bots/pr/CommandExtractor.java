/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.host.HostUser;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Stream;

import static org.openjdk.skara.bots.common.CommandNameEnum.*;
import static org.openjdk.skara.bots.common.PatternEnum.EXECUTION_COMMAND_PATTERN;

public class CommandExtractor {

    private static String formatId(String baseId, int subId) {
        if (subId > 0) {
            return String.format("%s:%d", baseId, subId);
        } else {
            return baseId;
        }
    }

    private static final Map<String, CommandHandler> commandHandlers = Map.ofEntries(
            Map.entry(help.name(), new HelpCommand()),
            Map.entry(integrate.name(), new IntegrateCommand()),
            Map.entry(sponsor.name(), new SponsorCommand()),
            Map.entry(contributor.name(), new ContributorCommand()),
            Map.entry(summary.name(), new SummaryCommand()),
            Map.entry(issue.name(), new IssueCommand()),
            Map.entry(solves.name(), new IssueCommand(solves.name())),
            Map.entry(reviewers.name(), new ReviewersCommand()),
            Map.entry(csr.name(), new CSRCommand()),
            Map.entry(jep.name(), new JEPCommand()),
            Map.entry(reviewer.name(), new ReviewerCommand()),
            Map.entry(label.name(), new LabelCommand()),
            Map.entry(cc.name(), new LabelCommand(cc.name())),
            Map.entry(clean.name(), new CleanCommand()),
            Map.entry(open.name(), new OpenCommand()),
            Map.entry(backport.name(), new BackportCommand()),
            Map.entry(tag.name(), new TagCommand()),
            Map.entry(branch.name(), new BranchCommand()),
            Map.entry(approval.name(), new ApprovalCommand()),
            Map.entry(approve.name(), new ApproveCommand()),
            Map.entry(author.name(), new AuthorCommand()),
            Map.entry(keepalive.name(), new TouchCommand()),
            Map.entry(touch.name(), new TouchCommand())
    );

    static class HelpCommand implements CommandHandler {
        @Override
        public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
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
        public void handle(PullRequestBot bot, HostedCommit hash, LimitedCensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
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
        public String name() {
            return help.name();
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
            line = BotUtils.preprocessCommandLine(line);
            var commandMatcher = EXECUTION_COMMAND_PATTERN.getPattern().matcher(line);
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
