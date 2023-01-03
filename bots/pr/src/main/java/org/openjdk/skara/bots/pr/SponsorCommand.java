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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SponsorCommand implements CommandHandler {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (censusInstance.isCommitter(pr.author())) {
            reply.println("This change does not need sponsoring - the author is allowed to integrate it.");
            return;
        }
        if (!censusInstance.isCommitter(command.user())) {
            reply.println("Only [Committers](https://openjdk.org/bylaws#committer) are allowed to sponsor changes.");
            return;
        }

        Optional<Hash> prePushHash = IntegrateCommand.checkForPrePushHash(bot, pr, scratchPath, allComments, "sponsor");
        if (prePushHash.isPresent()) {
            IntegrateCommand.markIntegratedAndMerged(bot, scratchPath, pr, prePushHash.get(), reply);
            return;
        }

        var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().forge().currentUser(), allComments);
        if (readyHash.isEmpty()) {
            if (!pr.labelNames().contains("auto")) {
                reply.println("The change author (@" + pr.author().username() + ") must issue an `integrate` command before the integration can be sponsored.");
            } else {
                reply.println("The PR is not yet marked as ready to be sponsored. Please try again when it is.");
            }
            return;
        }

        var acceptedHash = readyHash.get();
        if (!pr.headHash().equals(acceptedHash)) {
            if (!pr.labelNames().contains("auto")) {
                reply.print("The PR has been updated since the change author (@" + pr.author().username() + ") ");
                reply.println("issued the `integrate` command - the author must perform this command again.");
            } else {
                reply.print("The PR is not yet marked as ready to be sponsored. Please try again when it is.");
            }
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

            Repository localRepo = new HostedRepositoryPool(bot.seedStorage().orElse(scratchPath.resolve("seeds")))
                    .materialize(pr.repository(), scratchPath.resolve(pr.headHash().hex()));
            localRepo.fetch(pr.repository().url(), pr.targetRef(), true);
            localRepo.checkout(new Branch(pr.targetRef()), false);

            // See markIntegratedAndMerged for the logic for rogue mark as merged handling
            final List<Commit> commits = localRepo.commits(2).asList();
            commits.forEach(commit -> log.fine(commit.toString()));
            final Commit currentMarkAsMergedCommit =
                    IntegrateCommand.isMarkAsMergeCommit(commits.get(0)) ? commits.get(0) : null;
            final Commit lastMarkAsMergedCommit =
                    (commits.size() == 2 ? (IntegrateCommand.isMarkAsMergeCommit(commits.get(1)) ? commits.get(1) : null) : null);

            if (lastMarkAsMergedCommit != null) {
                localRepo.reset(lastMarkAsMergedCommit.parents().get(0), true);
                localRepo.push(lastMarkAsMergedCommit.parents().get(0), pr.repository().url(), pr.targetRef(), true);
            } else if (currentMarkAsMergedCommit != null) {
                localRepo.reset(currentMarkAsMergedCommit.parents().get(0), true);
                localRepo.push(currentMarkAsMergedCommit.parents().get(0), pr.repository().url(), pr.targetRef(), true);
            }

            localRepo = IntegrateCommand.materializeLocalRepo(bot, pr, scratchPath, "sponsor");
            var checkablePr = new CheckablePullRequest(pr, localRepo, bot.ignoreStaleReviews(),
                                                       bot.confOverrideRepository().orElse(null),
                                                       bot.confOverrideName(),
                                                       bot.confOverrideRef());

            // Validate the target hash if requested
            if (!command.args().isBlank()) {
                var wantedHash = new Hash(command.args());
                if (!checkablePr.targetHash().equals(wantedHash)) {
                    reply.print("The head of the target branch is no longer at the requested hash " + wantedHash);
                    reply.println(" - it has moved to " + checkablePr.targetHash() + ". Aborting integration.");
                    return;
                }
            }

            // Now rebase onto the target hash
            var rebaseMessage = new StringWriter();
            var rebaseWriter = new PrintWriter(rebaseMessage);
            var rebasedHash = checkablePr.mergeTarget(rebaseWriter);
            if (rebasedHash.isEmpty()) {
                reply.println(rebaseMessage);
                return;
            }

            var original = checkablePr.findOriginalBackportHash();
            var localHash = checkablePr.commit(rebasedHash.get(), censusInstance.namespace(), censusInstance.configuration().census().domain(),
                    command.user().id(), original);

            if (IntegrateCommand.runJcheck(pr, censusInstance, allComments, reply, localRepo, checkablePr, localHash)) {
                return;
            }

            if (!localHash.equals(checkablePr.targetHash())) {
                var amendedHash = checkablePr.amendManualReviewers(localHash, censusInstance.namespace(), original);
                IntegrateCommand.addPrePushComment(pr, amendedHash, rebaseMessage.toString());
                localRepo.push(amendedHash, pr.repository().url(), pr.targetRef());
                IntegrateCommand.markIntegratedAndMerged(bot, scratchPath, pr, amendedHash, reply);
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
