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
            seedRepo.fetch(pr.repository().url(), pr.headHash().hex());
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
        pr.repository().deleteBranch(PreIntegrations.preIntegrateBranch(pr));
    }

    @Override
    public void onNewPullRequest(PullRequest pr, Path scratchPath) {
        if (pr.state() == Issue.State.OPEN) {
            pushBranch(pr);
        }
    }

    @Override
    public void onStateChange(PullRequest pr, Path scratchPath, Issue.State oldState) {
        if (pr.state() == Issue.State.CLOSED) {
            var retargetedDependencies = PreIntegrations.retargetDependencies(pr);
            deleteBranch(pr);
            for (var retargeted : retargetedDependencies) {
                retargeted.addComment("""
                    The dependent pull request has now been integrated, and the target branch of this pull request \
                    has been updated. This means that changes from the dependent pull request can start to show up \
                    as belonging to this pull request, which may be confusing for reviewers. To remedy this situation, \
                    simply merge the latest changes from the new target branch into this pull request by running commands \
                    similar to these in the local repository for your personal fork:

                    ```bash
                    git checkout %s
                    git fetch %s %s
                    git merge FETCH_HEAD
                    # if there are conflicts, follow the instructions given by git merge
                    git commit -m "Merge %s"
                    git push
                    ```
                    """.formatted(pr.sourceRef(), pr.repository().webUrl(), pr.targetRef(), pr.targetRef()));
            }
        } else {
            pushBranch(pr);
        }
    }

    @Override
    public String name() {
        return "pullrequestbranch";
    }

    @Override
    public void onHeadChange(PullRequest pr, Path scratchPath, Hash oldHead) {
        if (pr.state() == Issue.State.OPEN) {
            pushBranch(pr);
        }
    }
}
