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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.file.Path;

public interface PullRequestListener {
    default void onNewIssue(PullRequest pr, Path scratchPath, Issue issue) {
    }
    default void onRemovedIssue(PullRequest pr, Path scratchPath, Issue issue) {
    }
    default void onNewPullRequest(PullRequest pr, Path scratchPath) {
    }
    default void onIntegratedPullRequest(PullRequest pr, Path scratchPath, Hash hash) {
    }
    default void onHeadChange(PullRequest pr, Path scratchPath, Hash oldHead) {
    }
    default void onStateChange(PullRequest pr, Path scratchPath, org.openjdk.skara.issuetracker.Issue.State oldState) {
    }
    default void onTargetBranchChange(PullRequest pr, Path scratchPath, Issue issue) {
    }
    String name();

    default void initialize(HostedRepository repo) {
    }
}
