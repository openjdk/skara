/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.util.List;

import static org.openjdk.skara.bots.common.CommandNameEnum.clean;

public class CleanCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/clean`");
    }

    @Override
    public String description() {
        return "Mark the backport pull request as a clean backport";
    }

    @Override
    public String name() {
        return clean.name();
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply)
    {
        if (!bot.cleanCommandEnabled()) {
            reply.println("The `/clean` pull request command is not enabled for this repository");
            return;
        }

        if (!censusInstance.isCommitter(command.user())) {
            reply.println("Only OpenJDK [Committers](https://openjdk.org/bylaws#committer) can use the `/clean` command");
            return;
        }

        if (!pr.labelNames().contains("backport") || CheckablePullRequest.findOriginalBackportHash(pr, allComments) == null) {
            reply.println("Can only mark [backport pull requests]" +
                    "(https://wiki.openjdk.org/display/SKARA/Backports#Backports-BackportPullRequests)," +
                    " with an original hash, as clean");
            return;
        }

        if (pr.labelNames().contains("clean")) {
            reply.println("This backport pull request is already marked as clean");
            return;
        }

        pr.addLabel("clean");
        reply.println("This backport pull request is now marked as clean");
    }
}
