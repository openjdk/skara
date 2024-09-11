/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.HostedBranch;
import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.jcheck.JCheckConfiguration;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openjdk.skara.bots.common.CommandNameEnum.branch;

public class BranchCommand implements CommandHandler {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/branch <name>`");
    }

    @Override
    public String description() {
        return "create a branch";
    }

    @Override
    public String name() {
        return branch.name();
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
            ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        try {
            if (!bot.integrators().contains(command.user().username())) {
                reply.println("Only integrators for this repository are allowed to use the `/branch` command.");
                return;
            }
            if (censusInstance.contributor(command.user()).isEmpty()) {
                printInvalidUserWarning(bot, reply);
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
            var branchName = parts[0];

            var localRepoDir = scratchArea.get(this)
                    .resolve(bot.repo().name());
            var localRepo = bot.hostedRepositoryPool()
                               .orElseThrow(() -> new IllegalStateException("Missing repository pool for PR bot"))
                               .materialize(bot.repo(), localRepoDir);
            localRepo.fetch(bot.repo().authenticatedUrl(), commit.hash().toString(), true).orElseThrow();

            var remoteBranches = bot.repo().branches();
            var remoteBranchNames = remoteBranches.stream()
                                                  .map(HostedBranch::name)
                                                  .collect(Collectors.toSet());
            if (remoteBranchNames.contains(branchName)) {
                var msg = "A branch with name `" + branchName + "` already exists";
                var remoteBranch = remoteBranches.stream().filter(r -> r.name().equals(branchName)).findFirst();
                if (remoteBranch.isPresent()) {
                    var hash = remoteBranch.get().hash();
                    var hashUrl = bot.repo().webUrl(hash);
                    msg += " that refers to commit [" + hash.abbreviate() + "](" + hashUrl + ").";
                } else {
                    msg += " (could not find the commit it refers to).";
                }
                reply.println(msg);
                return;
            }

            var jcheckConf = JCheckConfiguration.from(localRepo, commit.hash());
            var branchPattern = jcheckConf.map(c -> c.repository().branches());
            if (branchPattern.isPresent() && !branchName.matches(branchPattern.get())) {
                reply.println("The given branch name `" + branchName + "` is not of the form `" + branchPattern.get() + "`.");
                return;
            }

            var branch = localRepo.branch(commit.hash(), branchName);
            log.info("Pushing branch '" + branch + "' to refer to commit: " + commit.hash().hex());
            localRepo.push(commit.hash(), bot.repo().authenticatedUrl(), branch.name(), false, false);
            reply.println("The branch [" + branch.name() + "](" + bot.repo().webUrl(branch) + ") was successfully created.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
