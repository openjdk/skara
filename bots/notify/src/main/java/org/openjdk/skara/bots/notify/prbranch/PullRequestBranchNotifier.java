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
package org.openjdk.skara.bots.notify.prbranch;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.Path;
import java.util.logging.Logger;

public class PullRequestBranchNotifier implements Notifier, PullRequestListener {
    private final Path seedFolder;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    public PullRequestBranchNotifier(Path seedFolder) {
        this.seedFolder = seedFolder;
    }

    @Override
    public void attachTo(Emitter e) {
        e.registerPullRequestListener(this);
    }

    private void pushBranch(PullRequest pr) {
        var hostedRepositoryPool = new HostedRepositoryPool(seedFolder);
        try {
            var seedRepo = hostedRepositoryPool.seedRepository(pr.repository(), false);
            seedRepo.push(pr.headHash(), pr.repository().url(), PreIntegrations.preIntegrateBranch(pr), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteBranch(PullRequest pr) {
        var branchExists = pr.repository().branches().stream()
                         .map(HostedBranch::name)
                         .anyMatch(name -> name.equals(PreIntegrations.preIntegrateBranch(pr)));
        if (!branchExists) {
            log.info("Pull request pre-integration branch " + PreIntegrations.preIntegrateBranch(pr) + " doesn't exist on remote - ignoring");
            return;
        }

        var hostedRepositoryPool = new HostedRepositoryPool(seedFolder);
        try {
            var seedRepo = hostedRepositoryPool.seedRepository(pr.repository(), false);
            seedRepo.prune(new Branch(PreIntegrations.preIntegrateBranch(pr)), pr.repository().url().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onNewPullRequest(PullRequest pr) {
        if (pr.state() == Issue.State.OPEN) {
            pushBranch(pr);
        }
    }

    @Override
    public void onStateChange(PullRequest pr, Issue.State oldState) {
        if (pr.state() == Issue.State.CLOSED) {
            deleteBranch(pr);
        } else {
            pushBranch(pr);
        }
    }

    @Override
    public void onHeadChange(PullRequest pr, Hash oldHead) {
        if (pr.state() == Issue.State.OPEN) {
            pushBranch(pr);
        }
    }
}
