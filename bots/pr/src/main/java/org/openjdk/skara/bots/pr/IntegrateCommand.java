/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import static org.openjdk.skara.bots.common.CommandNameEnum.integrate;

public class IntegrateCommand implements CommandHandler {
    private final static Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private static final String PRE_PUSH_MARKER = "<!-- prepush %s -->";
    private static final Pattern PRE_PUSH_PATTERN = Pattern.compile("<!-- prepush ([0-9a-z]{40}) -->");
    private static final Pattern BACKPORT_LABEL_PATTERN = Pattern.compile("backport=(.+):(.+)");

    private enum Command {
        auto,
        manual,
        defer,
        undefer,
        delegate,
        undelegate
    }

    private void showHelp(PrintWriter reply) {
        reply.println("usage: `/integrate [auto|manual|delegate|undelegate|<hash>]`");
    }

    private Optional<String> checkProblem(Map<String, Check> performedChecks, String checkName, PullRequest pr) {
        final var failure = "the status check `" + checkName + "` did not complete successfully";
        final var inProgress = "the status check `" + checkName + "` is still in progress";
        final var outdated = "the status check `" + checkName + "` has not been performed on commit %s yet";

        if (performedChecks.containsKey(checkName)) {
            var check = performedChecks.get(checkName);
            if (check.status() == CheckStatus.SUCCESS) {
                return Optional.empty();
            } else if (check.status() == CheckStatus.IN_PROGRESS) {
                return Optional.of(inProgress);
            } else {
                return Optional.of(failure);
            }
        }
        return Optional.of(String.format(outdated, pr.headHash()));
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        // Parse any argument given
        Hash targetHash = null;
        Command commandArg = null;
        if (!command.args().isEmpty()) {
            var args = command.args().split(" ");
            if (args.length != 1) {
                showHelp(reply);
                return;
            }

            var arg = args[0].trim();
            for (Command value : Command.values()) {
                if (value.name().equals(arg)) {
                    commandArg = value;
                }
            }
            if (commandArg == null) {
                targetHash = new Hash(arg);
                if (!targetHash.isValid()) {
                    reply.println("The given argument, `" + arg + "`, is not a valid hash.");
                    return;
                }
            }
        }

        if (!command.user().equals(pr.author()) && !command.user().equals(pr.repository().forge().currentUser())) {
            if (pr.labelNames().contains("delegated")) {
                // Check that the command user is a committer
                if (!censusInstance.isCommitter(command.user())) {
                    reply.print("Only project committers are allowed to issue the `integrate` command on a delegated pull request.");
                    return;
                }
                // Check that no extra arguments are added
                if (!command.args().isEmpty()) {
                    reply.print("Only the author (@\" + pr.author().username() + \") is allowed to issue the `integrate` command with arguments.");
                    return;
                }
            } else {
                reply.print("Only the author (@" + pr.author().username() + ") is allowed to issue the `integrate` command.");

                // If the command author is allowed to sponsor this change, suggest that command
                var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().forge().currentUser(), allComments);
                if (readyHash.isPresent()) {
                    if (censusInstance.isCommitter(command.user())) {
                        reply.print(" As this pull request is ready to be sponsored, and you are an eligible sponsor, did you mean to issue the `/sponsor` command?");
                        return;
                    }
                }
                reply.println();
                return;
            }
        }

        if (commandArg == Command.auto) {
            pr.addLabel("auto");
            reply.println("This pull request will be automatically integrated when it is ready");
            return;
        } else if (commandArg == Command.manual) {
            if (pr.labelNames().contains("auto")) {
                pr.removeLabel("auto");
            }
            reply.println("This pull request will have to be integrated manually using the " +
                    "[/integrate](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) pull request command.");
            return;
        } else if (commandArg == Command.defer || commandArg == Command.delegate) {
            pr.addLabel("delegated");
            if (commandArg == Command.defer) {
                reply.println("Warning: `/integrate defer` is deprecated and will be removed in a future version. Use `/integrate delegate` instead.");
            }
            reply.println("Integration of this pull request has been delegated and may be completed by any project committer using the " +
                    "[/integrate](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) pull request command.");
            return;
        } else if (commandArg == Command.undefer || commandArg == Command.undelegate) {
            if (commandArg == Command.undefer) {
                reply.println("Warning: `/integrate undefer` is deprecated and will be removed in a future version. Use `/integrate undelegate` instead.");
            }
            if (pr.labelNames().contains("delegated")) {
                reply.println("Integration of this pull request is no longer delegated and may only be integrated by the author (@" + pr.author().username() + ")using the " +
                        "[/integrate](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) pull request command.");
                pr.removeLabel("delegated");
            }
            reply.println("This pull request may now only be integrated by the author");
            return;
        }

        Optional<Hash> prepushHash = checkForPrePushHash(bot, pr, scratchArea, allComments);
        if (prepushHash.isPresent()) {
            markIntegratedAndClosed(pr, prepushHash.get(), reply, allComments);
            return;
        }

        var problem = checkProblem(pr.checks(pr.headHash()), CheckRun.getJcheckName(pr), pr);
        if (problem.isPresent()) {
            reply.print("Your integration request cannot be fulfilled at this time, as ");
            reply.println(problem.get());
            return;
        }

        var labels = new HashSet<>(pr.labelNames());
        if (!labels.contains("ready")) {
            reply.println("This pull request has not yet been marked as ready for integration.");
            return;
        }

        // Run a final jcheck to ensure the change has been properly reviewed
        try (var integrationLock = IntegrationLock.create(pr, Duration.ofMinutes(10))) {
            if (!integrationLock.isLocked()) {
                log.severe("Unable to acquire the integration lock for " + pr.webUrl());
                reply.print("Unable to acquire the integration lock; aborting integration. The error has been logged and will be investigated.");
                return;
            }

            // Now that we have the integration lock, refresh the PR metadata
            pr = pr.repository().pullRequest(pr.id());

            Repository localRepo = materializeLocalRepo(bot, pr, scratchArea);
            var checkablePr = new CheckablePullRequest(pr, localRepo, bot.useStaleReviews(),
                    bot.confOverrideRepository().orElse(null),
                    bot.confOverrideName(),
                    bot.confOverrideRef(),
                    allComments,
                    bot.reviewMerge(),
                    new ReviewCoverage(bot.useStaleReviews(), bot.acceptSimpleMerges(), localRepo, pr));

            if (targetHash != null && !checkablePr.targetHash().equals(targetHash)) {
                reply.print("The head of the target branch is no longer at the requested hash " + targetHash);
                reply.println(" - it has moved to " + checkablePr.targetHash() + ". Aborting integration.");
                return;
            }

            // Now merge the latest changes from the target
            var rebaseMessage = new StringWriter();
            var rebaseWriter = new PrintWriter(rebaseMessage);
            var rebasedHash = checkablePr.mergeTarget(rebaseWriter);
            if (rebasedHash.isEmpty()) {
                reply.println(rebaseMessage);
                return;
            }

            var original = checkablePr.findOriginalBackportHash();
            // If someone other than the author or the bot issued the /integrate command, then that person
            // should be set as sponsor/integrator. Otherwise pass null to use the default author.
            String committerId = null;
            if (!command.user().equals(pr.author()) && !command.user().equals(pr.repository().forge().currentUser())) {
                committerId = command.user().id();
            }
            var localHash = checkablePr.commit(rebasedHash.get(), censusInstance.namespace(),
                    censusInstance.configuration().census().domain(), committerId, original);
            if (runJcheck(pr, censusInstance, allComments, reply, checkablePr, localHash)) {
                return;
            }

            // Finally check if the author is allowed to perform the actual push
            if (!censusInstance.isCommitter(pr.author())) {
                reply.println(ReadyForSponsorTracker.addIntegrationMarker(pr.headHash()));
                reply.println("Your change (at version " + pr.headHash() + ") is now ready to be sponsored by a Committer.");
                if (!command.args().isBlank()) {
                    reply.println("Note that your sponsor will make the final decision onto which target hash to integrate.");
                }
                pr.addLabel("sponsor");
                return;
            }

            // Rebase and push it!
            if (!localHash.equals(checkablePr.targetHash())) {
                var amendedHash = checkablePr.amendManualReviewersAndStaleReviewers(localHash, censusInstance.namespace(), original);
                addPrePushComment(pr, amendedHash, rebaseMessage.toString());
                localRepo.push(amendedHash, pr.repository().authenticatedUrl(), pr.targetRef());
                markIntegratedAndClosed(pr, amendedHash, reply, allComments);
            } else {
                reply.print("Warning! Your commit did not result in any changes! ");
                reply.println("No push attempt will be made.");
            }
        } catch (IOException | CommitFailure e) {
            log.log(Level.SEVERE, "An error occurred during integration (" + pr.webUrl() + "): " + e.getMessage(), e);
            reply.println("An unexpected error occurred during integration. No push attempt will be made. " +
                                  "The error has been logged and will be investigated. It is possible that this error " +
                                  "is caused by a transient issue; feel free to retry the operation.");
        }
    }

    /**
     * Runs the checks adding to the reply message and returns true if any of them failed
     */
    static boolean runJcheck(PullRequest pr, CensusInstance censusInstance, List<Comment> allComments,
                             PrintWriter reply, CheckablePullRequest checkablePr, Hash localHash) throws IOException {
        var targetHash = checkablePr.targetHash();
        var jcheckConf = checkablePr.parseJCheckConfiguration(targetHash);
        var visitor = checkablePr.createVisitor(jcheckConf);
        checkablePr.executeChecks(localHash, censusInstance, visitor, jcheckConf);
        if (!visitor.errorFailedChecksMessages().isEmpty()) {
            reply.print("Your integration request cannot be fulfilled at this time, as ");
            reply.println("your changes failed the final jcheck:");
            visitor.errorFailedChecksMessages().stream()
                  .map(line -> " * " + line)
                  .forEach(reply::println);
            return true;
        }
        return false;
    }

    static Repository materializeLocalRepo(PullRequestBot bot, PullRequest pr, ScratchArea scratchArea) throws IOException {
        var path = scratchArea.get(pr.repository());
        var seedPath = bot.seedStorage().orElse(scratchArea.getSeeds());
        var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
        return PullRequestUtils.materialize(hostedRepositoryPool, pr, path);
    }

    /**
     * Checks if a prepush comment has been created already. This could happen if
     * the bot got interrupted after pushing, but before finishing closing the PR
     * and adding the final push comment.
     */
    static Optional<Hash> checkForPrePushHash(PullRequestBot bot, PullRequest pr, ScratchArea scratchArea,
                                              List<Comment> allComments) {
        var botUser = pr.repository().forge().currentUser();
        var prePushHashes = allComments.stream()
                .filter(c -> c.author().equals(botUser))
                .map(Comment::body)
                .map(PRE_PUSH_PATTERN::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .collect(Collectors.toList());
        if (!prePushHashes.isEmpty()) {
            try {
                var localRepo = materializeLocalRepo(bot, pr, scratchArea);
                for (String prePushHash : prePushHashes) {
                    Hash hash = new Hash(prePushHash);
                    if (PullRequestUtils.isAncestorOfTarget(localRepo, hash)) {
                        // A previous attempt at pushing this PR was successful, but didn't finish
                        // closing the PR
                        log.info("Found previous successful push in prepush comment: " + hash.hex());
                        return Optional.of(hash);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Optional.empty();
    }

    static void addPrePushComment(PullRequest pr, Hash hash, String extraMessage) {
        var commentBody = new StringWriter();
        var writer = new PrintWriter(commentBody);
        writer.println(PRE_PUSH_MARKER.formatted(hash.hex()));
        writer.println("Going to push as commit " + hash.hex() + ".");
        if (!extraMessage.isBlank()) {
            writer.println(extraMessage);
        }
        pr.addComment(commentBody.toString());
    }

    private static void processBackportLabel(PullRequest pr, List<Comment> allComments) {
        var botUser = pr.repository().forge().currentUser();
        for (String label : pr.labelNames()) {
            var matcher = BACKPORT_LABEL_PATTERN.matcher(label);
            if (matcher.matches()) {
                var repoName = matcher.group(1);
                var branchName = matcher.group(2);
                var text = "Creating backport for repo " + repoName + " on branch " + branchName
                        + "\n\n/backport " + repoName + " " + branchName + "\n"
                        + PullRequestCommandWorkItem.VALID_BOT_COMMAND_MARKER;
                if (allComments.stream()
                        .filter(c -> c.author().equals(botUser))
                        .noneMatch(((c -> c.body().equals(text))))) {
                    pr.addComment(text);
                }
                pr.removeLabel(label);
            }
        }
    }

    static void markIntegratedAndClosed(PullRequest pr, Hash hash, PrintWriter reply, List<Comment> allComments) {
        processBackportLabel(pr, allComments);
        // Note that the order of operations here is tested in IntegrateTests::retryAfterInterrupt
        // so any change here requires careful update of that test
        pr.addLabel("integrated");
        pr.setState(PullRequest.State.CLOSED);
        pr.removeLabel("ready");
        pr.removeLabel("rfr");
        if (pr.labelNames().contains("delegated")) {
            pr.removeLabel("delegated");
        }
        if (pr.labelNames().contains("sponsor")) {
            pr.removeLabel("sponsor");
        }
        reply.println(PullRequest.commitHashMessage(hash));
        reply.println();
        reply.println(":bulb: You may see a message that your pull request was closed with unmerged commits. This can be safely ignored.");
    }

    @Override
    public String description() {
        return "performs integration of the changes in the PR";
    }

    @Override
    public String name() {
        return integrate.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
