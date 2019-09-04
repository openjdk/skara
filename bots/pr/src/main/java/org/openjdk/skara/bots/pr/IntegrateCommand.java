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

import org.openjdk.skara.host.*;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class IntegrateCommand implements CommandHandler {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

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
        return Optional.of(String.format(outdated, pr.getHeadHash()));
    }

    @Override
    public void handle(PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (!comment.author().equals(pr.getAuthor())) {
            reply.println("Only the author (@" + pr.getAuthor().userName() + ") is allowed to issue the `integrate` command.");
            return;
        }

        var problem = checkProblem(pr.getChecks(pr.getHeadHash()), "jcheck", pr);
        if (problem.isPresent()) {
            reply.print("Your merge request cannot be fulfilled at this time, as ");
            reply.println(problem.get());
            return;
        }

        if (pr.getLabels().contains("rejected")) {
            reply.println("The change is currently blocked from integration by a rejection.");
            return;
        }

        // Run a final jcheck to ensure the change has been properly reviewed
        try {
            var sanitizedUrl = URLEncoder.encode(pr.repository().getWebUrl().toString(), StandardCharsets.UTF_8);
            var path = scratchPath.resolve("pr.integrate").resolve(sanitizedUrl);

            var prInstance = new PullRequestInstance(path, pr);
            var hash = prInstance.commit(censusInstance.namespace(), censusInstance.configuration().census().domain(), null);
            var issues = prInstance.executeChecks(hash, censusInstance);
            if (!issues.getMessages().isEmpty()) {
                reply.print("Your merge request cannot be fulfilled at this time, as ");
                reply.println("your changes failed the final jcheck:");
                issues.getMessages().stream()
                      .map(line -> " * " + line)
                      .forEach(reply::println);
                return;
            }

            // Finally check if the author is allowed to perform the actual push
            if (!pr.getTitle().startsWith("Merge")) {
                if (!ProjectPermissions.mayCommit(censusInstance, pr.getAuthor())) {
                    reply.println(ReadyForSponsorTracker.addIntegrationMarker(pr.getHeadHash()));
                    reply.println("Your change (at version " + pr.getHeadHash() + ") is now ready to be sponsored by a Committer.");
                    pr.addLabel("sponsor");
                    return;
                }
            } else {
                if (!ProjectPermissions.mayCommit(censusInstance, pr.getAuthor())) {
                    reply.println("Merges require Committer status.");
                    return;
                }
            }

            // Rebase and push it!
            var rebasedHash = prInstance.rebase(hash, reply);
            if (rebasedHash.isPresent()) {
                reply.println("Pushed as commit " + rebasedHash.get().hex() + ".");
                prInstance.localRepo().push(rebasedHash.get(), pr.repository().getUrl(), pr.getTargetRef());
                pr.setState(PullRequest.State.CLOSED);
                pr.addLabel("integrated");
                pr.removeLabel("ready");
            }

        } catch (Exception e) {
            log.throwing("IntegrateCommand", "handle", e);
            reply.println("An error occurred during final integration jcheck");
            throw new RuntimeException(e);
        }
    }

    @Override
    public String description() {
        return "performs integration of the changes in the PR";
    }
}
