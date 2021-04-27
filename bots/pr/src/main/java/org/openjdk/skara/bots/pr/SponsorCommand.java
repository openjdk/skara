/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class SponsorCommand implements CommandHandler {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private static final Pattern BACKPORT_PATTERN = Pattern.compile("<!-- backport ([0-9a-z]{40}) -->");

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (censusInstance.isCommitter(pr.author())) {
            reply.println("This change does not need sponsoring - the author is allowed to integrate it.");
            return;
        }
        if (!censusInstance.isCommitter(command.user())) {
            reply.println("Only [Committers](https://openjdk.java.net/bylaws#committer) are allowed to sponsor changes.");
            return;
        }

        var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().forge().currentUser(), allComments);
        if (!pr.labelNames().contains("auto") && readyHash.isEmpty()) {
            reply.println("The change author (@" + pr.author().username() + ") must issue an `integrate` command before the integration can be sponsored.");
            return;
        }

        var acceptedHash = readyHash.get();
        if (!pr.labelNames().contains("auto") && !pr.headHash().equals(acceptedHash)) {
            reply.print("The PR has been updated since the change author (@" + pr.author().username() + ") ");
            reply.println("issued the `integrate` command - the author must perform this command again.");
            return;
        }

        var labels = new HashSet<>(pr.labelNames());
        if (!labels.contains("ready")) {
            reply.println("This PR has not yet been marked as ready for integration.");
            return;
        }

        // Notify the author as well
        reply.print("@" + pr.author().username() + " ");

        // Execute merge
        try (var integrationLock = IntegrationLock.create(pr, Duration.ofMinutes(10))) {
            if (!integrationLock.isLocked()) {
                log.severe("Unable to acquire the integration lock during sponsoring for " + pr.webUrl());
                reply.print("Unable to acquire the integration lock; aborting sponsored integration. The error has been logged and will be investigated.");
                return;
            }

            // Now that we have the integration lock, refresh the PR metadata
            pr = pr.repository().pullRequest(pr.id());

            var path = scratchPath.resolve("sponsor").resolve(pr.repository().name());
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);
            var checkablePr = new CheckablePullRequest(pr, localRepo, bot.ignoreStaleReviews(),
                                                       bot.confOverrideRepository().orElse(null),
                                                       bot.confOverrideName(),
                                                       bot.confOverrideRef());

            // Validate the target hash if requested
            var rebaseMessage = new StringWriter();
            if (!command.args().isBlank()) {
                var wantedHash = new Hash(command.args());
                if (!PullRequestUtils.targetHash(pr, localRepo).equals(wantedHash)) {
                    reply.print("The head of the target branch is no longer at the requested hash " + wantedHash);
                    reply.println(" - it has moved to " + PullRequestUtils.targetHash(pr, localRepo) + ". Aborting integration.");
                    return;
                }
            }

            // Now rebase onto the target hash
            var rebaseWriter = new PrintWriter(rebaseMessage);
            var rebasedHash = checkablePr.mergeTarget(rebaseWriter);
            if (rebasedHash.isEmpty()) {
                reply.println(rebaseMessage.toString());
                return;
            }

            var botUser = pr.repository().forge().currentUser();
            var backportLines = pr.comments()
                                  .stream()
                                  .filter(c -> c.author().equals(botUser))
                                  .flatMap(c -> Stream.of(c.body().split("\n")))
                                  .map(l -> BACKPORT_PATTERN.matcher(l))
                                  .filter(Matcher::find)
                                  .collect(Collectors.toList());
            var original = backportLines.isEmpty() ? null : new Hash(backportLines.get(0).group(1));
            var localHash = checkablePr.commit(rebasedHash.get(), censusInstance.namespace(), censusInstance.configuration().census().domain(),
                    command.user().id(), original);

            var issues = checkablePr.createVisitor(localHash);
            var additionalConfiguration = AdditionalConfiguration.get(localRepo, localHash, pr.repository().forge().currentUser(), allComments);
            checkablePr.executeChecks(localHash, censusInstance, issues, additionalConfiguration);
            if (!issues.messages().isEmpty()) {
                reply.print("Your integration request cannot be fulfilled at this time, as ");
                reply.println("your changes failed the final jcheck:");
                issues.messages().stream()
                      .map(line -> " * " + line)
                      .forEach(reply::println);
                return;
            }

            if (!localHash.equals(PullRequestUtils.targetHash(pr, localRepo))) {
                var amendedHash = checkablePr.amendManualReviewers(localHash, censusInstance.namespace(), original);
                localRepo.push(amendedHash, pr.repository().url(), pr.targetRef());
                var finalRebaseMessage = rebaseMessage.toString();
                if (!finalRebaseMessage.isBlank()) {
                    reply.println(rebaseMessage.toString());
                }
                reply.println("Pushed as commit " + amendedHash.hex() + ".");
                reply.println();
                reply.println(":bulb: You may see a message that your pull request was closed with unmerged commits. This can be safely ignored.");
                pr.setState(PullRequest.State.CLOSED);
                pr.addLabel("integrated");
                pr.removeLabel("sponsor");
                pr.removeLabel("ready");
                pr.removeLabel("rfr");
            } else {
                reply.print("Warning! This commit did not result in any changes! ");
                reply.println("No push attempt will be made.");
            }
        } catch (IOException | CommitFailure e) {
            log.log(Level.SEVERE, "An error occurred during sponsored integration (" + pr.webUrl() + "): " + e.getMessage(), e);
            reply.println("An unexpected error occurred during sponsored integration. No push attempt will be made. " +
                                  "The error has been logged and will be investigated. It is possible that this error " +
                                  "is caused by a transient issue; feel free to retry the operation.");
        }
    }

    @Override
    public String description() {
        return "performs integration of a PR that is authored by a non-committer";
    }
}
