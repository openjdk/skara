/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.ci.*;
import org.openjdk.skara.forge.PullRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestUpdateNeededWorkItem implements WorkItem {
    private final PullRequest pr;
    private final ContinuousIntegration ci;
    private final ConcurrentHashMap<String, TestBot.Observation> states;
    private final TestWorkItem actualWorkItem;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    TestUpdateNeededWorkItem(PullRequest pr,  ContinuousIntegration ci, ConcurrentHashMap<String, TestBot.Observation> states,
                             TestWorkItem actualWorkItem) {
        this.pr = pr;
        this.ci = ci;
        this.states = states;
        this.actualWorkItem = actualWorkItem;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof TestUpdateNeededWorkItem o)) {
            return true;
        }
        if (!pr.isSame(o.pr)) {
            return true;
        }
        return false;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        // is there a job running for this PR?
        var desc = pr.repository().name() + "#" + pr.id();
        List<Job> jobs = List.of();
        try {
            log.info("Getting test jobs for " + desc);
            jobs = ci.jobsFor(pr);
        } catch (IOException e) {
            log.log(Level.INFO, "Could not retrieve test jobs for PR: " + desc, e);
        }

        if (!jobs.isEmpty()) {
            var shouldUpdate = false;
            for (var job : jobs) {
                if (!states.containsKey(job.id())) {
                    shouldUpdate = true;
                    states.put(job.id(), new TestBot.Observation(job.state(), job.state()));
                } else {
                    var observed = states.get(job.id());

                    if (!observed.last.equals(Job.State.COMPLETED) ||
                            !observed.nextToLast.equals(Job.State.COMPLETED)) {
                        shouldUpdate = true;
                    }

                    observed.nextToLast = observed.last;
                    observed.last = job.state();
                }
            }
            if (shouldUpdate) {
                return List.of(actualWorkItem);
            }
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "TestUpdateNeededWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public String botName() {
        return TestBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "updater";
    }
}
