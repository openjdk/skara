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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

public class RejectCommand implements CommandHandler {
    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (pr.author().equals(comment.author())) {
            reply.println("You can't reject your own changes.");
            return;
        }
        if (!censusInstance.isReviewer(comment.author())) {
            reply.println("Only [Reviewers](https://openjdk.java.net/bylaws#reviewer) are allowed to reject changes.");
            return;
        }

        var botUser = pr.repository().forge().currentUser();
        var vetoers = Veto.vetoers(botUser, allComments);

        if (vetoers.contains(comment.author().id())) {
            reply.println("You have already rejected this change.");
            return;
        }

        // Notify the author as well
        reply.print("@" + pr.author().userName() + " ");

        reply.println("This change cannot be integrated while the rejection is in place. To lift the rejection, ");
        reply.println("issue an allow command: `/allow`");

        reply.println(Veto.addVeto(comment.author()));

        if (vetoers.isEmpty()) {
            pr.addLabel("rejected");
        }
    }

    @Override
    public String description() {
        return "flag the PR as not allowed to be integrated";
    }
}
