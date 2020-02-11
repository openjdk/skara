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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class SponsorCommand implements CommandHandler {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (ProjectPermissions.mayCommit(censusInstance, pr.author())) {
            reply.println("This change does not need sponsoring - the author is allowed to integrate it.");
            return;
        }
        if (!ProjectPermissions.mayCommit(censusInstance, comment.author())) {
            reply.println("Only [Committers](https://openjdk.java.net/bylaws#committer) are allowed to sponsor changes.");
            return;
        }

        var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().forge().currentUser(), allComments);
        if (readyHash.isEmpty()) {
            reply.println("The change author (@" + pr.author().userName() + ") must issue an `integrate` command before the integration can be sponsored.");
            return;
        }

        var acceptedHash = readyHash.get();
        if (!pr.headHash().equals(acceptedHash)) {
            reply.print("The PR has been updated since the change author (@" + pr.author().userName() + ") ");
            reply.println("issued the `integrate` command - the author must perform this command again.");
            return;
        }

        if (pr.labels().contains("rejected")) {
            reply.println("The change is currently blocked from integration by a rejection.");
            return;
        }

        // Notify the author as well
        reply.print("@" + pr.author().userName() + " ");

        // Execute merge
        try {
            var sanitizedUrl = URLEncoder.encode(pr.repository().webUrl().toString(), StandardCharsets.UTF_8);
            var path = scratchPath.resolve("sponsor").resolve(sanitizedUrl);

            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var prInstance = new PullRequestInstance(path,
                                                     new HostedRepositoryPool(seedPath),
                                                     pr,
                                                     bot.ignoreStaleReviews());
            var localHash = prInstance.commit(censusInstance.namespace(), censusInstance.configuration().census().domain(),
                                         comment.author().id());

            // Validate the target hash if requested
            var rebaseMessage = new StringWriter();
            if (!args.isBlank()) {
                var wantedHash = new Hash(args);
                if (!prInstance.targetHash().equals(wantedHash)) {
                    reply.print("The head of the target branch is no longer at the requested hash " + wantedHash);
                    reply.println(" - it has moved to " + prInstance.targetHash() + ". Aborting integration.");
                    return;
                }
            }

            // Now rebase onto the target hash
            var rebaseWriter = new PrintWriter(rebaseMessage);
            var rebasedHash = prInstance.rebase(localHash, rebaseWriter);
            if (rebasedHash.isEmpty()) {
                reply.println(rebaseMessage.toString());
                return;
            } else {
                if (!rebasedHash.get().equals(localHash)) {
                    localHash = rebasedHash.get();
                }
            }

            var issues = prInstance.createVisitor(localHash, censusInstance);
            var additionalConfiguration = AdditionalConfiguration.get(prInstance.localRepo(), localHash, pr.repository().forge().currentUser(), allComments);
            prInstance.executeChecks(localHash, censusInstance, issues, additionalConfiguration);
            if (!issues.getMessages().isEmpty()) {
                reply.print("Your merge request cannot be fulfilled at this time, as ");
                reply.println("your changes failed the final jcheck:");
                issues.getMessages().stream()
                      .map(line -> " * " + line)
                      .forEach(reply::println);
                return;
            }

            if (!localHash.equals(pr.targetHash())) {
                reply.println(rebaseMessage.toString());
                reply.println("Pushed as commit " + localHash.hex() + ".");
                prInstance.localRepo().push(localHash, pr.repository().url(), pr.targetRef());
                pr.setState(PullRequest.State.CLOSED);
                pr.addLabel("integrated");
                pr.removeLabel("sponsor");
                pr.removeLabel("ready");
                pr.removeLabel("rfr");
            } else {
                reply.print("Warning! This commit did not result in any changes! ");
                reply.println("No push attempt will be made.");
            }
        } catch (Exception e) {
            log.throwing("SponsorCommand", "handle", e);
            reply.println("An error occurred during sponsored integration");
            throw new RuntimeException(e);
        }
    }

    @Override
    public String description() {
        return "performs integration of a PR that is authored by a non-committer";
    }
}
