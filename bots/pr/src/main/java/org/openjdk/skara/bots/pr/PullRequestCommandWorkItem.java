/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.Hash;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;

public class PullRequestCommandWorkItem extends PullRequestWorkItem {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private static final String commandReplyMarker = "<!-- Jmerge command reply message (%s) -->";
    private static final Pattern commandReplyPattern = Pattern.compile("<!-- Jmerge command reply message \\((\\S+)\\) -->");
    private final static Pattern pushedPattern = Pattern.compile("Pushed as commit ([a-f0-9]{40})\\.");

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
            Map.entry("open", new OpenCommand())
    );

    public static final String VALID_BOT_COMMAND_MARKER = "<!-- Valid self-command -->";

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
        public void handle(PullRequestBot bot, HostedCommit hash, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
            reply.println("Available commands:");
            Stream.concat(
                    commandHandlers.entrySet().stream()
                                   .filter(entry -> entry.getValue().allowedInCommit())
                                   .map(entry -> entry.getKey() + " - " + entry.getValue().description()),
                    bot.externalPullRequestCommands().entrySet().stream()
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

    PullRequestCommandWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        super(bot, pr, errorHandler);
    }

    private static class InvalidBodyCommandHandler implements CommandHandler {
        @Override
        public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
            reply.println("The command `" + command.name() + "` cannot be used in the pull request body. Please use it in a new comment.");
        }

        @Override
        public String description() {
            return "";
        }
    }

    private Optional<CommandInvocation> nextCommand(PullRequest pr, List<Comment> comments) {
        var self = pr.repository().forge().currentUser();
        var body = PullRequestBody.parse(pr).bodyText();
        var allCommands = Stream.concat(CommandExtractor.extractCommands(commandHandlers, body, "body", pr.author()).stream(),
                                        comments.stream()
                                                .filter(comment -> !comment.author().equals(self) || comment.body().endsWith(VALID_BOT_COMMAND_MARKER))
                                                .flatMap(c -> CommandExtractor.extractCommands(commandHandlers, c.body(), c.id(), c.author()).stream()))
                                .collect(Collectors.toList());

        var handled = comments.stream()
                              .filter(comment -> comment.author().equals(self))
                              .map(comment -> commandReplyPattern.matcher(comment.body()))
                              .filter(Matcher::find)
                              .map(matcher -> matcher.group(1))
                              .collect(Collectors.toSet());

        return allCommands.stream()
                          .filter(ci -> !handled.contains(ci.id()))
                          .filter(ci -> !bot.externalPullRequestCommands().containsKey(ci.name()))
                          .findFirst();
    }

    private Optional<Hash> resultingCommitHash(List<Comment> allComments) {
        return allComments.stream()
                 .filter(comment -> comment.author().id().equals(pr.repository().forge().currentUser().id()))
                 .map(Comment::body)
                 .map(pushedPattern::matcher)
                 .filter(Matcher::find)
                 .map(m -> m.group(1))
                 .map(Hash::new)
                 .findAny();
    }

    private void changeLabelsAfterComment(List<String> labelsToAdd, List<String> labelsToRemove){
        if (labelsToAdd != null && !labelsToAdd.isEmpty()) {
            for (var label : labelsToAdd) {
                if (!pr.labelNames().contains(label)) {
                    log.info("Adding " + label + " label to " + describe(pr));
                    pr.addLabel(label);
                }
            }
        }
        if (labelsToRemove != null && !labelsToRemove.isEmpty()) {
            for (var label : labelsToRemove) {
                if (pr.labelNames().contains(label)) {
                    log.info("Removing " + label + " label from " + describe(pr));
                    pr.removeLabel(label);
                }
            }
        }
    }

    private String describe(PullRequest pr) {
        return pr.repository().name() + "#" + pr.id();
    }

    private void processCommand(PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments,
                                boolean isCommit) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println(String.format(commandReplyMarker, command.id()));
        printer.print("@");
        printer.print(command.user().username());
        printer.print(" ");

        var handler = command.handler();
        if (handler.isPresent()) {
            if (isCommit) {
                if (handler.get().allowedInCommit()) {
                    var hash = resultingCommitHash(allComments);
                    if (hash.isPresent()) {
                        var commit = pr.repository().commit(hash.get()).orElseThrow();
                        handler.get().handle(bot, commit, censusInstance, scratchPath, command, allComments, printer);
                    } else {
                        printer.print("The command `");
                        printer.print(command.name());
                        printer.println("` can only be used in a pull request that has been integrated.");
                    }
                } else {
                    printer.print("The command `");
                    printer.print(command.name());
                    printer.println("` can only be used in open pull requests.");
                }
            } else {
                if (handler.get().allowedInPullRequest()) {
                    if (command.id().startsWith("body") && !handler.get().allowedInBody()) {
                        handler = Optional.of(new PullRequestCommandWorkItem.InvalidBodyCommandHandler());
                    }
                    var labelsToAdd = new ArrayList<String>();
                    var labelsToRemove = new ArrayList<String>();
                    handler.get().handle(bot, pr, censusInstance, scratchPath, command, allComments, printer, labelsToAdd, labelsToRemove);
                    pr.addComment(writer.toString());
                    changeLabelsAfterComment(labelsToAdd, labelsToRemove);
                    return;
                } else {
                    printer.print("The command `");
                    printer.print(command.name());
                    printer.println("` can only be used in a pull request that has not yet been integrated.");
                }
            }
        } else {
            printer.print("Unknown command `");
            printer.print(command.name());
            printer.println("` - for a list of valid commands use `/help`.");
        }

        pr.addComment(writer.toString());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        log.info("Looking for PR commands");

        var comments = pr.comments();
        var nextCommand = nextCommand(pr, comments);

        if (nextCommand.isEmpty()) {
            log.info("No new non-external PR commands found, stopping further processing");

            if (!bot.isAutoLabelled(pr)) {
                // When all commands are processed, it's time to check labels
                // Must re-fetch PR after running the command, the command might have updated the PR
                var updatedPR = pr.repository().pullRequest(pr.id());
                return List.of(new LabelerWorkItem(bot, updatedPR, errorHandler));
            } else {
                return List.of();
            }
        }

        var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
        var hostedRepositoryPool = new HostedRepositoryPool(seedPath);

        var census = CensusInstance.create(hostedRepositoryPool, bot.censusRepo(), bot.censusRef(), scratchPath.resolve("census"), pr,
                                           bot.confOverrideRepository().orElse(null), bot.confOverrideName(), bot.confOverrideRef()).orElseThrow();
        var command = nextCommand.get();
        log.info("Processing command: " + command.id() + " - " + command.name());

        // We can't trust just the integrated label as that gets set before the commit comment.
        // If marked as integrated but there is no commit comment, any integrate command needs
        // to run again to correct the state of the PR.
        if (!pr.labelNames().contains("integrated") || resultingCommitHash(comments).isEmpty()) {
            processCommand(pr, census, scratchPath.resolve("pr").resolve("command"), command, comments, false);
            // Must re-fetch PR after running the command, the command might have updated the PR
            var updatedPR = pr.repository().pullRequest(pr.id());

            // Run another check to reflect potential changes from commands
            return List.of(new CheckWorkItem(bot, updatedPR, errorHandler));
        } else {
            processCommand(pr, census, scratchPath.resolve("pr").resolve("command"), command, comments, true);
            return List.of();
        }
    }

    @Override
    public String toString() {
        return "PullRequestCommandWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public String workItemName() {
        return "pr-command";
    }
}
