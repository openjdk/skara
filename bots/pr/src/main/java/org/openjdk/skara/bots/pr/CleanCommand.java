/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.PullRequest;
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

public class CleanCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/clean`");
    }

    @Override
    public String description() {
        return "Mark the backport pull request as a clean backport";
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply)
    {
        var username = command.user().username();
        if (!censusInstance.isCommitter(command.user())) {
            reply.println("@" + username + " only OpenJDK [Committers](https://openjdk.java.net/bylaws#committer) can use the `/clean` command");
            return;
        }

        if (!pr.labelNames().contains("backport")) {
            reply.println("@" + username + " can only mark [backport pull requests](https://wiki.openjdk.java.net/display/SKARA/Backports#Backports-BackportPullRequests) as clean");
            return;
        }

        if (pr.labelNames().contains("clean")) {
            reply.println("@" + username + " this backport pull request is already marked as clean");
            return;
        }

        pr.addLabel("clean");
        reply.println("@" + username + " this backport pull request is now marked as clean");
    }
}
