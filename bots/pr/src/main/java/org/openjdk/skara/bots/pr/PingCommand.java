/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

import static org.openjdk.skara.bots.common.CommandNameEnum.ping;
import static org.openjdk.skara.bots.common.PullRequestConstants.PING_COMMAND_RESPONSE_MARKER;

public class PingCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/ping`");
    }

    @Override
    public String description() {
        return "Re-evaluates the pull request and resets the inactivity timeout.";
    }

    @Override
    public String name() {
        return ping.name();
    }

    @Override
    public boolean allowedInBody() {
        return false;
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!pr.author().equals(command.user()) && !censusInstance.isReviewer(command.user())) {
            reply.println("Only the author of the pull request or [Reviewers](https://openjdk.org/bylaws#reviewer) are allowed to change the number of required reviewers.");
            return;
        }

        if (pr.isClosed()) {
            reply.println("This command can only be used in open pull requests.");
        }

        reply.println("The timeouts for this pull request has been reset and this pull request will be re-evaluated soon." + PING_COMMAND_RESPONSE_MARKER);
    }
}
