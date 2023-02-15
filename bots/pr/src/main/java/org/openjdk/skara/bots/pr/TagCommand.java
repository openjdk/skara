/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.jcheck.JCheckConfiguration;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;

public class TagCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/tag <name>`");
    }

    @Override
    public String description() {
        return "create a tag";
    }

    @Override
    public boolean allowedInCommit() {
        return true;
    }

    @Override
    public boolean allowedInPullRequest() {
        return false;
    }

    @Override
    public void handle(PullRequestBot bot, HostedCommit commit, LimitedCensusInstance censusInstance,
            Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        try {
            if (!bot.integrators().contains(command.user().username())) {
                reply.println("Only integrators for this repository are allowed to use the `/tag` command.");
                return;
            }
            if (censusInstance.contributor(command.user()).isEmpty()) {
                reply.println("To use the `/tag` command, you need to be in the OpenJDK [census](https://openjdk.org/census)"
                        + " and your GitHub account needs to be linked with your OpenJDK username"
                        + " ([how to associate your GitHub account with your OpenJDK username]"
                        + "(https://wiki.openjdk.org/display/skara#Skara-AssociatingyourGitHubaccountandyourOpenJDKusername)).");
                return;
            }

            var args = command.args();
            if (args.isBlank()) {
                showHelp(reply);
                return;
            }

            var parts = args.split(" ");
            if (parts.length > 1) {
                showHelp(reply);
                return;
            }
            var tagName = parts[0];

            var localRepoDir = scratchPath.resolve("tag-command")
                                          .resolve(bot.repo().name());
            var localRepo = bot.hostedRepositoryPool()
                               .orElseThrow(() -> new IllegalStateException("Missing repository pool for PR bot"))
                               .materialize(bot.repo(), localRepoDir);

            var existingTagNames = localRepo.tags().stream().map(Tag::name).collect(Collectors.toSet());
            if (existingTagNames.contains(tagName)) {
                var hash = localRepo.resolve(tagName).orElseThrow(() ->
                        new IllegalStateException("Cannot resolve tag with name " + tagName + " in repo " + bot.repo().name()));
                var hashUrl = bot.repo().webUrl(hash);
                reply.println("A tag with name `" + tagName + "` already exists that refers to commit [" + hash.abbreviate() + "](" + hashUrl + ").");
                return;
            }

            var jcheckConf = JCheckConfiguration.from(localRepo, commit.hash());
            var tagPattern = jcheckConf.isPresent() ? jcheckConf.get().repository().tags() : null;
            if (tagPattern != null && !tagName.matches(tagPattern)) {
                reply.println("The given tag name `" + tagName + "` is not of the form `" + tagPattern + "`.");
                return;
            }

            var domain = censusInstance.configuration().census().domain();
            var contributor = censusInstance.contributor(command.user()).orElseThrow();
            var email = contributor.username() + "@" + domain;
            var message = "Added tag " + tagName + " for changeset " + commit.hash().abbreviate();
            var tag = localRepo.tag(commit.hash(), tagName, message, contributor.username(), email);
            localRepo.push(tag, bot.repo().url(), false);
            reply.println("The tag [" + tag.name() + "](" + bot.repo().webUrl(tag) + ") was successfully created.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
