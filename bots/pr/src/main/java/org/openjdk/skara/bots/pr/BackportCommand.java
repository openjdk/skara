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

import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;

public class BackportCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/backport <repository> [<branch>]`");
    }

    @Override
    public String description() {
        return "create a backport";
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
    public void handle(PullRequestBot bot, HostedCommit commit, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        var username = command.user().username();
        if (censusInstance.contributor(command.user()).isEmpty()) {
            reply.println("@" + username + " only OpenJDK [contributors](https://openjdk.java.net/bylaws#contributor) can use the `/backport` command");
            return;
        }

        var args = command.args();
        if (args.isBlank()) {
            showHelp(reply);
            return;
        }

        var parts = args.split(" ");
        if (parts.length > 2) {
            showHelp(reply);
            return;
        }

        var forge = bot.repo().forge();
        var repoNameArg = parts[0].replace("http://", "")
                               .replace("https://", "")
                               .replace(forge.hostname() + "/", "");
        // If the arg is given with a namespace prefix, look for an exact match,
        // otherwise cut off the namespace prefix before comparing with the forks
        // config.
        var includesNamespace = repoNameArg.contains("/");
        var repoName = bot.forks().keySet().stream()
                .filter(s -> includesNamespace
                        ? s.equals(repoNameArg)
                        : s.substring(s.indexOf("/") + 1).equals(repoNameArg))
                .findAny();

        var potentialTargetRepo = repoName.flatMap(forge::repository);
        if (potentialTargetRepo.isEmpty()) {
            reply.println("@" + username + " the target repository `" + repoNameArg + "` is not a valid target for backports. ");
            reply.print("List of valid target repositories: ");
            reply.println(String.join(", ", bot.forks().keySet().stream().sorted().toList()) + ".");
            reply.println("Supplying the organization/group prefix is optional.");
            return;
        }
        var targetRepo = potentialTargetRepo.get();
        var fork = bot.forks().get(targetRepo.name());

        var targetBranchName = parts.length == 2 ? parts[1] : "master";
        var targetBranches = targetRepo.branches();
        if (targetBranches.stream().noneMatch(b -> b.name().equals(targetBranchName))) {
            reply.println("@" + username + " the target branch `" + targetBranchName + "` does not exist");
            return;
        }
        var targetBranch = new Branch(targetBranchName);

        try {
            var hash = commit.hash();
            Hash backportHash;
            var backportBranchName = username + "-backport-" + hash.abbreviate();
            var hostedBackportBranch = fork.branches().stream().filter(b -> b.name().equals(backportBranchName)).findAny();
            if (hostedBackportBranch.isEmpty()) {
                var localRepoDir = scratchPath.resolve("backport-command")
                                              .resolve(targetRepo.name())
                                              .resolve("fork");
                var localRepo = bot.hostedRepositoryPool()
                                   .orElseThrow(() -> new IllegalStateException("Missing repository pool for PR bot"))
                                   .materialize(targetRepo, localRepoDir);
                var fetchHead = localRepo.fetch(bot.repo().url(), hash.hex(), false);
                localRepo.checkout(targetBranch);
                var head = localRepo.head();
                var backportBranch = localRepo.branch(head, backportBranchName);
                localRepo.checkout(backportBranch);
                var didApply = localRepo.cherryPick(fetchHead);
                if (!didApply) {
                    var lines = new ArrayList<String>();
                    lines.add("@" + username + " could **not** automatically backport `" + hash.abbreviate() + "` to " +
                              "[" + repoName + "](" + targetRepo.webUrl() + ") due to conflicts in the following files:");
                    lines.add("");
                    var unmerged = localRepo.status()
                                            .stream()
                                            .filter(e -> e.status().isUnmerged())
                                            .map(e -> e.target().path().orElseGet(() -> e.source().path().orElseThrow()))
                                            .collect(Collectors.toList());
                    for (var path : unmerged) {
                        lines.add("- " + path.toString());
                    }
                    lines.add("");
                    lines.add("To manually resolve these conflicts run the following commands in your personal fork of [" + repoName + "](" + targetRepo.webUrl() + "):");
                    lines.add("");
                    lines.add("```");
                    lines.add("$ git checkout -b " + backportBranchName);
                    lines.add("$ git fetch --no-tags " + bot.repo().webUrl() + " " + hash.hex());
                    lines.add("$ git cherry-pick --no-commit " + hash.hex());
                    lines.add("$ # Resolve conflicts");
                    lines.add("$ git add files/with/resolved/conflicts");
                    lines.add("$ git commit -m 'Backport " + hash.hex() + "'");
                    lines.add("```");
                    lines.add("");
                    lines.add("Once you have resolved the conflicts as explained above continue with creating a pull request towards the [" + repoName + "](" + targetRepo.webUrl() + ") with the title `Backport " + hash.hex() + "`.");

                    reply.println(String.join("\n", lines));
                    localRepo.reset(head, true);
                    return;
                }

                backportHash = localRepo.commit("Backport " + hash.hex(), "duke", "duke@openjdk.org");
                localRepo.push(backportHash, fork.url(), backportBranchName, false);
            } else {
                backportHash = hostedBackportBranch.get().hash();
            }

            if (!fork.canPush(command.user())) {
                fork.addCollaborator(command.user(), true);
            }
            fork.restrictPushAccess(new Branch(backportBranchName), command.user());

            var message = CommitMessageParsers.v1.parse(commit);
            var formatter = DateTimeFormatter.ofPattern("d MMM uuuu");
            var body = new ArrayList<String>();
            body.add("> Hi all,");
            body.add("> ");
            body.add("> this pull request contains a backport of commit " +
                      "[" + hash.abbreviate() + "](" + commit.url() + ") from the " +
                      "[" + bot.repo().name() + "](" + bot.repo().webUrl() + ") repository.");
            body.add(">");
            var info = "> The commit being backported was authored by " + commit.author().name() + " on " +
                        commit.committed().format(formatter);
            if (message.reviewers().isEmpty()) {
                info += " and had no reviewers";
            } else {
                var reviewers = message.reviewers()
                                       .stream()
                                       .map(r -> censusInstance.census().contributor(r))
                                       .map(c -> c.fullName().isPresent() ? c.fullName().get() : c.username())
                                       .collect(Collectors.toList());
                var numReviewers = reviewers.size();
                var listing = numReviewers == 1 ?
                    reviewers.get(0) :
                    String.join(", ", reviewers.subList(0, numReviewers - 1));
                if (numReviewers > 1) {
                    listing += " and " + reviewers.get(numReviewers - 1);
                }
                info += " and was reviewed by " + listing;
            }
            info += ".";
            body.add(info);
            body.add("> ");
            body.add("> Thanks!");

            var createPrUrl = fork.createPullRequestUrl(targetRepo, targetBranch.name(), backportBranchName);
            var targetBranchWebUrl = targetRepo.webUrl(targetBranch);
            var backportBranchWebUrl = fork.webUrl(new Branch(backportBranchName));
            var backportWebUrl = fork.webUrl(backportHash);
            reply.println("@" + command.user().username() + " the [backport](" + backportWebUrl + ")" +
                          " was successfully created on the branch [" + backportBranchName + "](" +
                          backportBranchWebUrl + ") in my [personal fork](" + fork.webUrl() + ") of [" +
                          targetRepo.name() + "](" + targetRepo.webUrl() + "). To create a pull request " +
                          "with this backport targeting [" + targetRepo.name() + ":" + targetBranch.name() + "](" +
                          targetBranchWebUrl + "), just click the following link:\n" +
                          "\n" +
                          "[:arrow_right: ***Create pull request***](" + createPrUrl + ")\n" +
                          "\n" +
                          "The title of the pull request is automatically filled in correctly and below you " +
                          "find a suggestion for the pull request body:\n" +
                          "\n" +
                          String.join("\n", body) +
                          "\n" +
                          "\n" +
                          "If you need to update the [source branch](" + backportBranchWebUrl + ") of the pull " +
                          "then run the following commands in a local clone of your personal fork of " +
                          "[" + targetRepo.name() + "](" + targetRepo.webUrl() + "):\n" +
                          "\n" +
                          "```\n" +
                          "$ git fetch " + fork.webUrl() + " " + backportBranchName + ":" + backportBranchName + "\n" +
                          "$ git checkout " + backportBranchName + "\n" +
                          "# make changes\n" +
                          "$ git add paths/to/changed/files\n" +
                          "$ git commit --message 'Describe additional changes made'\n" +
                          "$ git push " + fork.webUrl() + " " + backportBranchName + "\n" +
                          "```");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
