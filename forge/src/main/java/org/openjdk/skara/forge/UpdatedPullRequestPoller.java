/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A poller to get the updated pull requests. The user can provide the corresponding functions
 * to get the open updated pull requests or all the updated pull requests.
 */
public class UpdatedPullRequestPoller {
    private final HostedRepository repo;
    // Keeps track of updatedAt timestamps from the previous call to getUpdatedPullRequests,
    // so we can avoid re-evaluating PRs that are returned again without any actual
    // update. This is needed because timestamp based searches aren't exact enough
    // to avoid sometimes receiving the same items multiple times.
    private Map<String, ZonedDateTime> prsUpdatedAt = new HashMap<>();
    // The last found updateAt in any returned PR. Used for limiting results on the
    // next call to the hosted repo. Should only contain timestamps originating
    // from the remote repo to avoid problems with mismatched clocks.
    private ZonedDateTime lastUpdatedAt;

    public UpdatedPullRequestPoller(HostedRepository repo) {
        this.repo = repo;
    }

    /**
     * A method to get the updated pull request. The concrete operation is provided by the user.
     *
     * If you want to get the updated open pull request, you can use
     * `getUpdatedPullRequests(HostedRepository::openPullRequestsAfter, HostedRepository::openPullRequests)`.
     * Because the method `HostedRepository::openPullRequests` has not been implemented now,
     * you can use the method `HostedRepository::pullRequests` instead.
     * The class `ApprovalPullRequestBot` has such usage.
     *
     * If you want to get all the updated pull request (included open and closed), you can use
     * `getUpdatedPullRequests(HostedRepository::pullRequestsAfter, HostedRepository::pullRequests)`.
     * The method `HostedRepository::pullRequestsAfter` now is named as `HostedRepository::pullRequests`.
     * and the `HostedRepository::pullRequests` now only get all the open pull requests.
     * TODO want the class `HostedRepository` and its sub-classes to be adjusted.
     */
    public List<PullRequest> getUpdatedPullRequests(BiFunction<HostedRepository, ZonedDateTime, List<PullRequest>> updatedPrGetter,
                                                    Function<HostedRepository, List<PullRequest>> prGetter) {
        var prList = new ArrayList<PullRequest>();
        Map<String, ZonedDateTime> newPrsUpdatedAt = new HashMap<>();
        // On the first run we have to re-evaluate all the PRs, after that, only
        // looking at PRs that have been updated should be enough.
        var prs = lastUpdatedAt != null ? updatedPrGetter.apply(repo, lastUpdatedAt) : prGetter.apply(repo);
        for (PullRequest pr : prs) {
            newPrsUpdatedAt.put(pr.id(), pr.updatedAt());
            // Update lastUpdatedAt with the last found updatedAt for the next call
            if (lastUpdatedAt == null || pr.updatedAt().isAfter(lastUpdatedAt)) {
                lastUpdatedAt = pr.updatedAt();
            }
            var lastUpdate = prsUpdatedAt.get(pr.id());
            if (lastUpdate != null) {
                if (!pr.updatedAt().isAfter(lastUpdate)) {
                    continue;
                }
            }
            prList.add(pr);
        }
        prsUpdatedAt = newPrsUpdatedAt;
        return prList;
    }
}
