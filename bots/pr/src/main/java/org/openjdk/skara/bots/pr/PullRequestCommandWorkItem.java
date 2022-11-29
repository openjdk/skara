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

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;

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

    public static final String VALID_BOT_COMMAND_MARKER = "<!-- Valid self-command -->";

    PullRequestCommandWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler,
            ZonedDateTime prUpdatedAt, boolean needsReadyCheck) {
        super(bot, prId, errorHandler, prUpdatedAt, needsReadyCheck);
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
        var allCommands = findAllCommands(pr, comments);
        var handled = findHandledCommands(pr, comments);
        return allCommands.stream()
                          .filter(ci -> !handled.contains(ci.id()))
                          .filter(ci -> !bot.externalPullRequestCommands().containsKey(ci.name()))
                          .findFirst();
    }

    static List<CommandInvocation> findAllCommands(PullRequest pr, List<Comment> comments) {
        var self = pr.repository().forge().currentUser();
        var body = PullRequestBody.parse(pr).bodyText();
        return Stream.concat(CommandExtractor.extractCommands(body, "body", pr.author(), pr.createdAt()).stream(),
                        comments.stream()
                                .filter(comment -> !comment.author().equals(self) || comment.body().endsWith(VALID_BOT_COMMAND_MARKER))
                                .flatMap(c -> CommandExtractor.extractCommands(c.body(), c.id(), c.author(), c.createdAt()).stream()))
                .collect(Collectors.toList());
    }

    static Set<String> findHandledCommands(PullRequest pr, List<Comment> comments) {
        var self = pr.repository().forge().currentUser();
        return comments.stream()
                .filter(comment -> comment.author().equals(self))
                .map(comment -> commandReplyPattern.matcher(comment.body()))
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .collect(Collectors.toSet());
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
        return pr.repository().name() + "#" + prId;
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
                    var hash = pr.findIntegratedCommitHash();
                    if (hash.isPresent()) {
                        var commit = pr.repository().commit(hash.get()).orElseThrow();
                        handler.get().handle(bot, commit, censusInstance, scratchPath, command, allComments, printer);
                    } else {
                        // FIXME the argument `isCommit` is true here, which means the PR already has the `integrated` label
                        //  and has the integrated commit hash, so this branch would never be run.
                        //  Maybe this branch could be removed. And this branch can not be tested now.
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
                    var newComment = pr.addComment(writer.toString());
                    var latency = Duration.between(command.createdAt(), newComment.createdAt());
                    log.log(Level.INFO, "Time from command '" + command.name() + "' to reply " + latency, latency);
                    changeLabelsAfterComment(labelsToAdd, labelsToRemove);
                    return;
                } else {
                    printer.print("The command `");
                    printer.print(command.name());
                    printer.println("` can not be used in pull requests.");
                }
            }
        } else {
            printer.print("Unknown command `");
            printer.print(command.name());
            printer.println("` - for a list of valid commands use `/help`.");
        }

        var newComment = pr.addComment(writer.toString());
        var latency = Duration.between(command.createdAt(), newComment.createdAt());
        log.log(Level.INFO, "Time from command '" + command.name() + "' to reply " + latency, latency);
    }

    @Override
    public Collection<WorkItem> prRun(Path scratchPath) {
        log.info("Looking for PR commands");

        var comments = pr.comments();
        var nextCommand = nextCommand(pr, comments);

        if (nextCommand.isEmpty()) {
            log.info("No new non-external PR commands found, stopping further processing");

            if (!bot.isAutoLabelled(pr)) {
                // When all commands are processed, it's time to check labels
                return List.of(new LabelerWorkItem(bot, prId, errorHandler, prUpdatedAt));
            } else {
                return List.of();
            }
        }

        var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
        var hostedRepositoryPool = new HostedRepositoryPool(seedPath);

        CensusInstance census;
        try {
            census = CensusInstance.createCensusInstance(hostedRepositoryPool, bot.censusRepo(), bot.censusRef(), scratchPath.resolve("census"), pr,
                    bot.confOverrideRepository().orElse(null), bot.confOverrideName(), bot.confOverrideRef());
        } catch (InvalidJCheckConfException | MissingJCheckConfException e) {
            throw new RuntimeException(e);
        }
        var command = nextCommand.get();
        log.info("Processing command: " + command.id() + " - " + command.name());

        // We can't trust just the integrated label as that gets set before the commit comment.
        // If marked as integrated but there is no commit comment, any integrate command needs
        // to run again to correct the state of the PR.
        if (!pr.labelNames().contains("integrated") || pr.findIntegratedCommitHash().isEmpty()) {
            processCommand(pr, census, scratchPath.resolve("pr").resolve("command"), command, comments, false);
            // Run another check to reflect potential changes from commands
            return List.of(new CheckWorkItem(bot, prId, errorHandler, prUpdatedAt, false));
        } else {
            processCommand(pr, census, scratchPath.resolve("pr").resolve("command"), command, comments, true);
            return List.of();
        }
    }

    @Override
    public String toString() {
        return "PullRequestCommandWorkItem@" + bot.repo().name() + "#" + prId;
    }

    @Override
    public String workItemName() {
        return "pr-command";
    }
}
