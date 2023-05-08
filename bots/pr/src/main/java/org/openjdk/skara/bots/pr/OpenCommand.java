/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Issue;

import java.io.PrintWriter;
import java.util.List;

import static org.openjdk.skara.bots.common.CommandNameEnum.open;

public class OpenCommand implements CommandHandler {
    private void showHelp(PrintWriter reply) {
        reply.println("Usage: `/open`");
    }

    @Override
    public String description() {
        return "Set the pull request state to \"open\"";
    }

    @Override
    public String name() {
        return open.name();
    }

    @Override
    public boolean allowedInBody() {
        return false;
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command, List<Comment> allComments, PrintWriter reply)
    {
        if (!command.user().equals(pr.author())) {
            reply.println("Only the pull request author can set the pull request state to \"open\"");
            return;
        }

        if (pr.isOpen()) {
            reply.println("This pull request is already open");
            return;

        }

        pr.setState(Issue.State.OPEN);
        reply.println("This pull request is now open");
    }
}
