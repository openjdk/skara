/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.time.format.DateTimeFormatter;

import static org.openjdk.skara.bots.common.CommandNameEnum.backport;

public class BackportCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage:  `/backport <repository> [<branch>]` " +
                "or `/backport [<repository>]:<branch>`");
    }

    private void showHelpInPR(PrintWriter reply) {
        reply.println("Usage: `/backport [disable] <repository> [<branch>]` " +
                "or `/backport [disable] [<repository>]:<branch>`");
    }

    @Override
    public String description() {
        return "create a backport";
    }

    @Override
    public String name() {
        return backport.name();
    }

    @Override
    public boolean allowedInCommit() {
        return true;
    }

    private static final String INSUFFICIENT_ACCESS_WARNING = "The backport can not be created because you don't have access to the target repository.";

    private static final int BRANCHES_LIMIT = 10;

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command,
                       List<Comment> allComments, PrintWriter reply, List<String> labelsToAdd, List<String> labelsToRemove) {
        if (bot.checkContributorStatusForBackportCommand() && censusInstance.contributor(command.user()).isEmpty()) {
            printInvalidUserWarning(bot, reply);
            return;
        }

        if (pr.isClosed() && !pr.labelNames().contains("integrated")) {
            reply.println("`/backport` command can not be used in a closed but not integrated pull request");
            return;
        }

        var args = command.args();
        if (args.isBlank()) {
            showHelpInPR(reply);
            return;
        }

        var parts = args.split(" ");

        // Preprocess args to support "repo:branch" argument
        if (parts[0].equals("disable")) {
            if (parts.length == 2 && parts[1].contains(":")) {
                List<String> tempList = new ArrayList<>();
                tempList.add("disable");
                tempList.addAll(Arrays.asList(parts[1].split(":")));
                parts = tempList.toArray(new String[0]);
            }
        } else {
            if (parts.length == 1 && parts[0].contains(":")) {
                parts = parts[0].split(":");
            }
        }

        boolean argIsValid = parts[0].equals("disable") ? parts.length == 2 || parts.length == 3 : parts.length <= 2;
        if (!argIsValid) {
            showHelpInPR(reply);
            return;
        }

        if (parts[0].equals("disable")) {
            // Remove label
            var targetRepo = getTargetRepo(bot, parts[1], reply);
            if (targetRepo == null) {
                return;
            }
            var targetRepoName = targetRepo.name();

            var targetBranch = getTargetBranch(parts, 2, targetRepo, reply);
            if (targetBranch == null) {
                return;
            }
            var targetBranchName = targetBranch.name();

            var backportLabel = generateBackportLabel(targetRepoName, targetBranchName);
            if (pr.labelNames().contains(backportLabel)) {
                labelsToRemove.add(backportLabel);
                reply.println("Backport for repo `" + targetRepoName + "` on branch `" + targetBranchName + "` was successfully disabled.");
            } else {
                reply.println("Backport for repo `" + targetRepoName + "` on branch `" + targetBranchName + "` was already disabled.");
            }
        } else {
            // Get target repo
            var targetRepo = getTargetRepo(bot, parts[0], reply);
            if (targetRepo == null) {
                return;
            }
            var targetRepoName = targetRepo.name();

            // Get target branch
            var targetBranch = getTargetBranch(parts, 1, targetRepo, reply);
            if (targetBranch == null) {
                return;
            }
            var targetBranchName = targetBranch.name();

            if (!targetRepo.canCreatePullRequest(command.user())) {
                reply.println(INSUFFICIENT_ACCESS_WARNING);
                return;
            }

            // Add label
            var backportLabel = generateBackportLabel(targetRepoName, targetBranchName);
            if (pr.labelNames().contains(backportLabel)) {
                reply.println("Backport for repo `" + targetRepoName + "` on branch `" + targetBranchName + "` has already been enabled.");
            } else {
                labelsToAdd.add(backportLabel);
                reply.print("Backport for repo `" + targetRepoName + "` on branch `" + targetBranchName + "` was successfully enabled and will be performed once this pull request has been integrated.");
                reply.println(" Further instructions will be provided at that time.");
                reply.println("<!-- add backport " + targetRepoName + ":" + targetBranchName + " -->");
                reply.println("<!-- " + command.user().username() + " -->");
            }
        }
    }

    private String generateBackportLabel(String targetRepo, String targetBranchName) {
        return "backport=" + targetRepo + ":" + targetBranchName;
    }

    private HostedRepository getTargetRepo(PullRequestBot bot, String repoName, PrintWriter reply) {
        var forge = bot.repo().forge();
        if (repoName.isEmpty()) {
            repoName = bot.repo().name();
        }
        var repoNameArg = repoName.replace("http://", "")
                .replace("https://", "")
                .replace(forge.hostname() + "/", "");
        // If the arg is given with a namespace prefix, look for an exact match,
        // otherwise cut off the namespace prefix before comparing with the forks
        // config.
        var includesNamespace = repoNameArg.contains("/");
        var repoNameOptional = bot.forks().keySet().stream()
                .filter(s -> includesNamespace
                        ? s.equals(repoNameArg)
                        : s.substring(s.indexOf("/") + 1).equals(repoNameArg))
                .findAny();

        var potentialTargetRepo = repoNameOptional.flatMap(forge::repository);
        if (potentialTargetRepo.isEmpty()) {
            reply.println("The target repository `" + repoNameArg + "` is not a valid target for backports. ");
            reply.print("List of valid target repositories: ");
            reply.println(String.join(", ", bot.forks().keySet().stream()
                    .sorted()
                    .map(repo -> "`" + repo + "`")
                    .toList()) + ".");
            reply.println("Supplying the organization/group prefix is optional.");
            var branchNamesInCurrentRepo = bot.repo().branches().stream().map(HostedBranch::name).toList();
            if (branchNamesInCurrentRepo.contains(repoName)) {
                reply.println();
                reply.println("There is a branch `" + repoName + "` in the current repository `" + bot.repo().name() + "`.");
                reply.println("To target a backport to this branch in the current repository use:");
                reply.println("`/backport :" + repoName + "`");
            }
            return null;
        }
        return potentialTargetRepo.get();
    }

    private Branch getTargetBranch(String[] parts, int index, HostedRepository targetRepo, PrintWriter reply) {
        var targetBranchName = parts.length == index + 1 ? parts[index] : targetRepo.defaultBranchName();
        var targetBranchHash = targetRepo.branchHash(targetBranchName);
        if (targetBranchHash.isEmpty()) {
            reply.println("The target branch `" + targetBranchName + "` does not exist");
            reply.print("List of valid branches: ");
            var branches = targetRepo.branches().stream()
                    .map(HostedBranch::name)
                    .filter(name -> !name.startsWith("pr/"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            reply.println(String.join(", ", branches.stream()
                    .limit(BRANCHES_LIMIT)
                    .map(branch -> "`" + branch + "`")
                    .toList()) + (branches.size() > BRANCHES_LIMIT ? "..." : "."));
            return null;
        }
        return new Branch(targetBranchName);
    }

    @Override
    public void handle(PullRequestBot bot, HostedCommit commit, LimitedCensusInstance censusInstance,
                       ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (bot.checkContributorStatusForBackportCommand() && censusInstance.contributor(command.user()).isEmpty()
                && !command.user().equals(bot.repo().forge().currentUser())) {
            printInvalidUserWarning(bot, reply);
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

        // Preprocess args to support "repo:branch" argument
        if (parts.length == 1 && parts[0].contains(":")) {
            parts = parts[0].split(":");
        }

        // Get target repo
        var targetRepo = getTargetRepo(bot, parts[0], reply);
        if (targetRepo == null) {
            return;
        }
        var targetRepoName = targetRepo.name();
        var fork = bot.forks().get(targetRepo.name());

        // Get target branch
        var targetBranch = getTargetBranch(parts, 1, targetRepo, reply);
        if (targetBranch == null) {
            return;
        }
        var targetBranchName = targetBranch.name();

        // Find real user when the command user is bot
        HostUser realUser = command.user();
        if (realUser.equals(bot.repo().forge().currentUser())) {
            var botComment = allComments.stream()
                    .filter(comment -> comment.author().equals(bot.repo().forge().currentUser()))
                    .filter(comment -> comment.body().contains("<!-- add backport " + targetRepoName + ":" + targetBranchName + " -->"))
                    .reduce((first, second) -> second).orElse(null);
            if (botComment != null) {
                String[] lines = botComment.body().split("\\n");
                String userName = lines[lines.length - 1].split(" ")[1];
                var user = bot.repo().forge().user(userName);
                if (user.isPresent()) {
                    realUser = user.get();
                    reply.print("@");
                    reply.print(realUser.username());
                    reply.print(" ");
                } else {
                    reply.println("Error: can not find the real user of Backport for repo `" + targetRepoName + "` on branch `" + targetBranchName);
                    return;
                }
            }
        }

        if (!targetRepo.canCreatePullRequest(realUser)) {
            reply.println(INSUFFICIENT_ACCESS_WARNING);
            return;
        }

        try {
            var hash = commit.hash();
            Hash backportHash;
            var backportBranchName = "backport-" + realUser.username() + "-" + hash.abbreviate() + "-" + targetBranchName;
            var backportBranchHash = fork.branchHash(backportBranchName);

            var message = CommitMessageParsers.v1.parse(commit);
            var formatter = DateTimeFormatter.ofPattern("d MMM uuuu");
            var body = new ArrayList<String>();
            body.add("> Hi all,");
            body.add("> ");
            body.add("> This pull request contains a backport of commit " +
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

            if (backportBranchHash.isEmpty()) {
                var localRepoDir = scratchArea.get(this)
                        .resolve(targetRepo.name())
                        .resolve("fork");
                var localRepo = bot.hostedRepositoryPool()
                                   .orElseThrow(() -> new IllegalStateException("Missing repository pool for PR bot"))
                                   .materialize(targetRepo, localRepoDir);
                var fetchHead = localRepo.fetch(bot.repo().authenticatedUrl(), hash.hex(), false).orElseThrow();
                var head = localRepo.fetch(targetRepo.authenticatedUrl(), targetBranchName, false).orElseThrow();
                var backportBranch = localRepo.branch(head, backportBranchName);
                localRepo.checkout(backportBranch);
                var didApply = localRepo.cherryPick(fetchHead);
                if (!didApply) {
                    var lines = new ArrayList<String>();
                    lines.add("Could **not** automatically backport `" + hash.abbreviate() + "` to " +
                              "[" + targetRepoName + "](" + targetRepo.webUrl() + ") due to conflicts in the following files:");
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
                    lines.add("Please fetch the appropriate branch/commit and manually resolve these conflicts "
                            + "by using the following commands in your personal fork of [" + targetRepoName + "](" + targetRepo.webUrl()
                            + "). Note: these commands are just some suggestions and you can use other equivalent commands you know.");
                    lines.add("");
                    lines.add("```");
                    lines.add("# Fetch the up-to-date version of the target branch");
                    lines.add("$ git fetch --no-tags " + targetRepo.url() + " " + targetBranch.name() + ":" + targetBranch.name());
                    lines.add("");
                    lines.add("# Check out the target branch and create your own branch to backport");
                    lines.add("$ git checkout " + targetBranch.name());
                    lines.add("$ git checkout -b " + backportBranchName);
                    lines.add("");
                    lines.add("# Fetch the commit you want to backport");
                    lines.add("$ git fetch --no-tags " + bot.repo().url() + " " + hash.hex());
                    lines.add("");
                    lines.add("# Backport the commit");
                    lines.add("$ git cherry-pick --no-commit " + hash.hex());
                    lines.add("# Resolve conflicts now");
                    lines.add("");
                    lines.add("# Commit the files you have modified");
                    lines.add("$ git add files/with/resolved/conflicts");
                    lines.add("$ git commit -m 'Backport " + hash.hex() + "'");
                    lines.add("```");
                    lines.add("");
                    lines.add("Once you have resolved the conflicts as explained above continue with creating a pull request towards the [" + targetRepoName + "](" + targetRepo.webUrl() + ") with the title `Backport " + hash.hex() + "`.");
                    lines.add("");
                    lines.add("Below you can find a suggestion for the pull request body:");
                    lines.addAll(body);
                    reply.println(String.join("\n", lines));
                    localRepo.reset(head, true);
                    return;
                }
                // Check that applying the change actually created a diff
                if (localRepo.diff(head).patches().isEmpty()) {
                    reply.println("Could **not** apply backport `" + hash.abbreviate() + "` to " +
                            "[" + targetRepoName + "](" + targetRepo.webUrl() + ") because the change is already present in the target.");
                    localRepo.reset(head, true);
                    return;
                }

                backportHash = localRepo.commit("Backport " + hash.hex(), "duke", "duke@openjdk.org");
                localRepo.push(backportHash, fork.authenticatedUrl(), backportBranchName, false);
            } else {
                backportHash = backportBranchHash.get();
            }

            var invitationReminder = "";
            if (!fork.canPush(realUser)) {
                fork.addCollaborator(realUser, true);
                if (bot.repo().forge().name().equals("GitHub")) {
                    invitationReminder = "\n\n⚠️ @" + realUser.username() +
                            " You are not yet a collaborator in my fork [" + fork.name() + "](" + fork.url() + ")." +
                            " An invite will be sent out and you need to accept it before you can proceed.";
                }
            } else {
                // It's not possible to add branch level push protection unless the user
                // already has push permissions in the repository. If we try to restrict
                // it anyway, nobody can push to the branch. At least this way, if the
                // user already has push permissions, the branch will be protected,
                // otherwise anyone with push permissions in the repository will be able
                // to push to the branch.
                fork.restrictPushAccess(new Branch(backportBranchName), realUser);
            }

            var createPrUrl = fork.createPullRequestUrl(targetRepo, targetBranch.name(), backportBranchName);
            var targetBranchWebUrl = targetRepo.webUrl(targetBranch);
            var backportBranchWebUrl = fork.webUrl(new Branch(backportBranchName));
            var backportWebUrl = fork.webUrl(backportHash);
            reply.println("the [backport](" + backportWebUrl + ")" +
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
                          "$ git fetch " + fork.url() + " " + backportBranchName + ":" + backportBranchName + "\n" +
                          "$ git checkout " + backportBranchName + "\n" +
                          "# make changes\n" +
                          "$ git add paths/to/changed/files\n" +
                          "$ git commit --message 'Describe additional changes made'\n" +
                          "$ git push " + fork.url() + " " + backportBranchName + "\n" +
                          "```" +
                          invitationReminder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
