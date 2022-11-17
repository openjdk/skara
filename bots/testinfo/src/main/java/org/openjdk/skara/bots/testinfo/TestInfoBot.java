/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.bots.testinfo;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The TestInfoBot copies 'checks' from the source repository of a PR to the
 * PR itself. In GitHub, these checks are usually workflow/action runs which
 * users may have activated on their personal forks. By copying them to a PR,
 * reviewers can easily see the status of the last workflow runs directly in
 * the PR.
 * <p>
 * The bot polls for work using the standard PullRequestPoller, so will
 * process any updated PR. Depending on the outcome of this processing, the
 * TestInfoBotWorkItem calls back to the bot with a re-check request which
 * causes the bot to submit that PR again after the specified amount of time,
 * or earlier if another change the PR has been detected.
 * <p>
 * Note that if there is a check update, this will cause an update to the PR
 * that the next call to updatedPullRequests, and subsequently getPeriodicItems
 * will also include. So as long as there are check updates, there will
 * essentially be a series of rechecks with only the getPeriodicItem call delay
 * until no update was found, at which point the bot would fall back to adding
 * the retry interval again.
 */
public class TestInfoBot implements Bot {
    private final HostedRepository repo;
    private final PullRequestPoller poller;

    TestInfoBot(HostedRepository repo) {
        this.repo = repo;
        this.poller = new PullRequestPoller(repo, true);
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var prs = poller.updatedPullRequests();
        var workItems = prs.stream()
                .filter(pr -> pr.sourceRepository().isPresent())
                .map(pr -> (WorkItem) new TestInfoBotWorkItem(pr,
                        delay -> poller.retryPullRequest(pr, Instant.now().plus(delay))))
                .toList();
        poller.lastBatchHandled();
        return workItems;
    }

    @Override
    public String name() {
        return TestInfoBotFactory.NAME;
    }

    @Override
    public String toString() {
        return "TestInfoBot@" + repo.name();
    }
}
