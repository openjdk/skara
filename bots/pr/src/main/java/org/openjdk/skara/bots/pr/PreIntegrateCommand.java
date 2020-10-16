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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class PreIntegrateCommand implements CommandHandler {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().userName() + ") is allowed to issue the `preintegrate` command.");
            return;
        }

        // Check if the author is allowed to perform pre-integrations
        if (!censusInstance.isCommitter(pr.author())) {
            reply.println("Only [Committers](https://openjdk.java.net/bylaws#committer) (@" + pr.author().userName() + ") are allowed to issue the `preintegrate` command.");
            return;
        }

        try {
            var path = scratchPath.resolve("integrate").resolve(pr.repository().name());
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);

            var preIntegrateBranch = PreIntegrations.preIntegrateBranch(pr);
            localRepo.push(pr.headHash(), pr.repository().url(), preIntegrateBranch);

            reply.println("The current content of this pull request has been pre-integrated into the branch `" + preIntegrateBranch + "`. ");
            reply.println("This branch can now be targeted by additional pull requests that contain dependent work.");

        } catch (IOException e) {
            log.severe("An error occurred during pre-integration (" + pr.webUrl() + "): " + e.getMessage());
            log.throwing("PreIntegrateCommand", "handle", e);
            reply.println("An unexpected error occurred during pre-integration. No push attempt will be made. " +
                                  "The error has been logged and will be investigated. It is possible that this error " +
                                  "is caused by a transient issue; feel free to retry the operation.");
        }
    }

    @Override
    public String description() {
        return null;
    }
}
