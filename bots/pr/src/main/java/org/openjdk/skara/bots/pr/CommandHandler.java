/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.file.Path;
import java.util.List;

interface CommandHandler {
    String description();

    default void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply)
    {
    }

    /**
     * Overload the method with a parameter `changeLabelsAfterComment`.
     * If the command need to change the labels after commenting to avoid a race condition,
     * please use this method with the argument `changeLabelsAfterComment` as true.
     * If you don't meet a race condition, please use another method without parameter `changeLabelsAfterComment`,
     * and never use this method with the argument `changeLabelsAfterComment` as false.
     *
     * @param changeLabelsAfterComment just a tag to distinguish another method
     * @return the labels to change
     */
    default LabelsToChange handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath,
                        CommandInvocation command, List<Comment> allComments, PrintWriter reply, boolean changeLabelsAfterComment) {
        return new LabelsToChange(null, null);
    }

    default void handle(PullRequestBot bot, HostedCommit commit, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
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

    default boolean changeLabelsAfterComment() {
        return false;
    }
}
