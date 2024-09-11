/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.io.PrintWriter;
import java.util.List;

interface CommandHandler {
    String description();

    String name();

    default void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea,
                        CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
    }

    default void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, ScratchArea scratchArea, CommandInvocation command,
                        List<Comment> allComments, PrintWriter reply, List<String> labelsToAdd, List<String> labelsToRemove) {
        handle(bot, pr, censusInstance, scratchArea, command, allComments, reply);
    }

    default void handle(PullRequestBot bot, HostedCommit commit, LimitedCensusInstance censusInstance, ScratchArea scratchArea,
            CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
    }

    default boolean multiLine() {
        return false;
    }
    default boolean allowedInBody() {
        return false;
    }
    default boolean allowedInCommit() {
        return false;
    }
    default boolean allowedInPullRequest() {
        return true;
    }

    default void printInvalidUserWarning(PullRequestBot bot, PrintWriter reply) {
        if (bot.repo().forge().name().equals("GitHub")) {
            reply.println(String.format("To use the `/%s` command, you need to be in the OpenJDK [census](https://openjdk.org/census)"
                    + " and your GitHub account needs to be linked with your OpenJDK username"
                    + " ([how to associate your GitHub account with your OpenJDK username]"
                    + "(https://wiki.openjdk.org/display/skara#Skara-AssociatingyourGitHubaccountandyourOpenJDKusername)).", name()));
        } else {
            reply.println(String.format("To use the `/%s` command, you need to be listed as a contributor in this [census](%s)", name(), bot.censusRepo().authenticatedUrl()));
        }
    }
}
