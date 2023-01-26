/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;
import java.util.function.Consumer;

public class CommitCommandWorkItem implements WorkItem {
    private final PullRequestBot bot;
    private final CommitComment commitComment;
    private final Consumer<RuntimeException> onError;

    static final String COMMAND_REPLY_MARKER = "<!-- Jmerge command reply message (%s) -->";
    static final Pattern COMMAND_REPLY_PATTERN = Pattern.compile("<!-- Jmerge command reply message \\((\\S+)\\) -->");

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    CommitCommandWorkItem(PullRequestBot bot, CommitComment commitComment, Consumer<RuntimeException> onError) {
        this.bot = bot;
        this.commitComment = commitComment;
        this.onError = onError;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CommitCommandWorkItem otherItem)) {
            return true;
        }
        if (!bot.repo().isSame(otherItem.bot.repo())) {
            return true;
        }
        if (!commitComment.id().equals(otherItem.commitComment.id())) {
            return true;
        }
        return false;
    }

    private Optional<CommandInvocation> nextCommand(List<CommitComment> allComments) {
        var self = bot.repo().forge().currentUser();
        var command = CommandExtractor.extractCommands(commitComment.body(),
                commitComment.id(), commitComment.author(), commitComment.createdAt());
        if (command.isEmpty()) {
            return Optional.empty();
        }

        var handled = allComments.stream()
                              .filter(c -> c.author().equals(self))
                              .map(c -> COMMAND_REPLY_PATTERN.matcher(c.body()))
                              .filter(Matcher::find)
                              .map(matcher -> matcher.group(1))
                              .collect(Collectors.toSet());

        if (handled.contains(commitComment.id())) {
            return Optional.empty();
        } else {
            return Optional.of(command.get(0));
        }
    }

    private void processCommand(Path scratchPath, HostedCommit commit, LimitedCensusInstance censusInstance,
            CommandInvocation command, List<CommitComment> allComments) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println(String.format(COMMAND_REPLY_MARKER, command.id()));
        printer.print("@");
        printer.print(command.user().username());
        printer.print(" ");

        var handler = command.handler();
        if (handler.isPresent()) {
            if (handler.get().allowedInCommit()) {
                var comments = allComments.stream()
                                          .map(cc -> (Comment)cc)
                                          .collect(Collectors.toList());
                handler.get().handle(bot, commit, censusInstance, scratchPath, command, comments, printer);
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

        var hash = commitComment.commit();
        var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
        var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
        var allComments = bot.repo().commitComments(hash);
        var nextCommand = nextCommand(allComments);
        if (nextCommand.isEmpty()) {
            log.info("No new commit comments found, stopping further processing");
        } else {
            LimitedCensusInstance census;
            var command = nextCommand.get();
            try {
                census = LimitedCensusInstance.createLimitedCensusInstance(hostedRepositoryPool, bot.censusRepo(), bot.censusRef(),
                        scratchPath.resolve("census"), bot.repo(), hash.hex(),
                        bot.confOverrideRepository().orElse(null),
                        bot.confOverrideName(),
                        bot.confOverrideRef());
            } catch (MissingJCheckConfException e) {
                if (bot.confOverrideRepository().isEmpty()) {
                    log.log(Level.SEVERE, "No .jcheck/conf found in repo " + bot.repo().name(), e);
                    var comment = String.format(COMMAND_REPLY_MARKER, command.id()) + "\n" +
                            "@" + command.user().username() +
                            " There is no `.jcheck/conf` present at revision " +
                            hash.abbreviate() + " - cannot process command.";
                    bot.repo().addCommitComment(hash, comment);
                } else {
                    log.log(Level.SEVERE, "Jcheck configuration file " + bot.confOverrideName()
                            + " not found in external repo " + bot.confOverrideRepository().get().name(), e);
                    var comment = String.format(COMMAND_REPLY_MARKER, command.id()) + "\n" +
                            "@" + command.user().username() +
                            " There is no Jcheck configuration file " + bot.confOverrideName()
                            + " present in external repo " + bot.confOverrideRepository().get().name() + " at revision "
                            + hash.abbreviate() + " - cannot process command.";
                    bot.repo().addCommitComment(hash, comment);
                }
                return List.of();
            } catch (InvalidJCheckConfException e) {
                if (bot.confOverrideRepository().isEmpty()) {
                    log.log(Level.SEVERE, "Invalid .jcheck/conf found in repo " + bot.repo().name(), e);
                    var comment = String.format(COMMAND_REPLY_MARKER, command.id()) + "\n" +
                            "@" + command.user().username() +
                            " Invalid `.jcheck/conf` present at revision " +
                            hash.abbreviate() + " - cannot process command.";
                    bot.repo().addCommitComment(hash, comment);
                } else {
                    log.log(Level.SEVERE, "Invalid Jcheck configuration file " + bot.confOverrideName()
                            + " in external repo " + bot.confOverrideRepository().get().name(), e);
                    var comment = String.format(COMMAND_REPLY_MARKER, command.id()) + "\n" +
                            "@" + command.user().username() +
                            " Invalid Jcheck configuration file " + bot.confOverrideName() + " present in external repo "
                            + bot.confOverrideRepository().get().name() + " at revision " +
                            hash.abbreviate() + " - cannot process command.";
                    bot.repo().addCommitComment(hash, comment);
                }
                return List.of();
            }
            var commit = bot.repo().commit(hash).orElseThrow(() ->
                    new IllegalStateException("Commit with hash " + hash + " missing"));
            processCommand(scratchPath, commit, census, command, allComments);
        }
        return List.of();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        onError.accept(e);
    }

    @Override
    public String toString() {
        return "CommitCommandWorkItem@" + bot.repo().name() + ":" + commitComment.commit().abbreviate();
    }

    @Override
    public String botName() {
        return PullRequestBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "commit-command";
    }
}
