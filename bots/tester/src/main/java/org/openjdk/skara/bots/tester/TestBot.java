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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.ci.ContinuousIntegration;
import org.openjdk.skara.ci.Job;
import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.census.Census;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.host.HostUser;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.function.Predicate;

public class TestBot implements Bot {
    private static class Observation {
        Job.State nextToLast;
        Job.State last;

        Observation(Job.State nextToLast, Job.State last) {
            this.nextToLast = nextToLast;
            this.last = last;
        }
    }

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final ContinuousIntegration ci;
    private final String approversGroupId;
    private final Set<String> allowlist;
    private final List<String> availableJobs;
    private final List<String> defaultJobs;
    private final String name;
    private final Path storage;
    private final HostedRepository repo;
    private final PullRequestUpdateCache cache;
    private final Map<String, Observation> states;
    private final HostedRepository censusRemote;
    private final Path censusDir;
    private final boolean checkCommitterStatus;

    TestBot(ContinuousIntegration ci,
            String approversGroupId,
            Set<String> allowlist,
            List<String> availableJobs,
            List<String> defaultJobs,
            String name,
            Path storage,
            HostedRepository repo,
            HostedRepository censusRemote,
            Path censusDir,
            boolean checkCommitterStatus) {
        this.ci = ci;
        this.approversGroupId = approversGroupId;
        this.allowlist = allowlist;
        this.availableJobs = availableJobs;
        this.defaultJobs = defaultJobs;
        this.name = name;
        this.storage = storage;
        this.repo = repo;
        this.cache = new PullRequestUpdateCache();
        this.states = new HashMap<>();
        this.censusRemote = censusRemote;
        this.censusDir = censusDir;
        this.checkCommitterStatus = checkCommitterStatus;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        Predicate<HostUser> isCommitter = null;
        if (checkCommitterStatus) {
            try {
                var censusRepo = Repository.materialize(censusDir, censusRemote.url(), Branch.defaultFor(VCS.GIT).name());
                var census = Census.parse(censusDir);
                var namespace = census.namespace(repo.namespace());
                var jcheckConf = repo.fileContents(".jcheck/conf", Branch.defaultFor(VCS.GIT).name());
                var jcheck = JCheckConfiguration.parse(jcheckConf.lines().collect(Collectors.toList()));
                var project = census.project(jcheck.general().project());
                isCommitter = u -> {
                   var contributor = namespace.get(u.id());
                   if (contributor == null) {
                       return false;
                   }
                   return project.isCommitter(contributor.username(), census.version().format());
                };
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            isCommitter = u -> true;
        }

        var ret = new ArrayList<WorkItem>();

        var host = repo.webUrl().getHost();
        var repoId = Long.toString(repo.id());
        for (var pr : repo.pullRequests()) {
            if (cache.needsUpdate(pr)) {
                ret.add(new TestWorkItem(ci,
                                         approversGroupId,
                                         allowlist,
                                         availableJobs,
                                         defaultJobs,
                                         name,
                                         storage,
                                         pr,
                                         isCommitter));
            } else {
                // is there a job running for this PR?
                var desc = pr.repository().name() + "#" + pr.id();
                List<Job> jobs = List.of();
                try {
                    log.info("Getting test jobs for " + desc);
                    jobs = ci.jobsFor(pr);
                } catch (IOException e) {
                    log.info("Could not retrieve test jobs for PR: " + desc);
                    log.throwing("TestBot", "getPeriodicItems", e);
                }

                if (!jobs.isEmpty()) {
                    var shouldUpdate = false;
                    for (var job : jobs) {
                        if (!states.containsKey(job.id())) {
                            shouldUpdate = true;
                            states.put(job.id(), new Observation(job.state(), job.state()));
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
                        ret.add(new TestWorkItem(ci,
                                                 approversGroupId,
                                                 allowlist,
                                                 availableJobs,
                                                 defaultJobs,
                                                 name,
                                                 storage,
                                                 pr,
                                                 isCommitter));
                    }
                }
            }
        }

        return ret;
    }
}
