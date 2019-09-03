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
import java.util.List;
import java.util.logging.Logger;

public class SponsorCommand implements CommandHandler {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    @Override
    public void handle(PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (ProjectPermissions.mayCommit(censusInstance, pr.getAuthor())) {
            reply.println("This change does not need sponsoring - the author is allowed to integrate it.");
            return;
        }
        if (!ProjectPermissions.mayCommit(censusInstance, comment.author())) {
            reply.println("Only [Committers](http://openjdk.java.net/bylaws#committer) are allowed to sponsor changes.");
            return;
        }

        var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().host().getCurrentUserDetails(), allComments);
        if (readyHash.isEmpty()) {
            reply.println("The change author (@" + pr.getAuthor().userName() + ") must issue an `integrate` command before the integration can be sponsored.");
            return;
        }

        var acceptedHash = readyHash.get();
        if (!pr.getHeadHash().equals(acceptedHash)) {
            reply.print("The PR has been updated since the change author (@" + pr.getAuthor().userName() + ") ");
            reply.println("issued the `integrate` command - the author must perform this command again.");
            return;
        }

        if (pr.getLabels().contains("rejected")) {
            reply.println("The change is currently blocked from integration by a rejection.");
            return;
        }

        // Notify the author as well
        reply.print("@" + pr.getAuthor().userName() + " ");

        // Execute merge
        try {
            var sanitizedUrl = URLEncoder.encode(pr.repository().getWebUrl().toString(), StandardCharsets.UTF_8);
            var path = scratchPath.resolve("pr.sponsor").resolve(sanitizedUrl);

            var prInstance = new PullRequestInstance(path, pr);
            var hash = prInstance.commit(censusInstance.namespace(), censusInstance.configuration().census().domain(),
                                         comment.author().id());
            var rebasedHash = prInstance.rebase(hash, reply);
            if (rebasedHash.isPresent()) {
                reply.println("Pushed as commit " + rebasedHash.get().hex() + ".");
                prInstance.localRepo().push(rebasedHash.get(), pr.repository().getUrl(), pr.getTargetRef());
                pr.setState(PullRequest.State.CLOSED);
                pr.addLabel("integrated");
                pr.removeLabel("sponsor");
                pr.removeLabel("ready");
            }
        } catch (IOException e) {
            log.throwing("SponsorCommand", "handle", e);
            reply.println("An error occurred during sponsored integration");
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String description() {
        return "performs integration of a PR that is authored by a non-committer";
    }
}
