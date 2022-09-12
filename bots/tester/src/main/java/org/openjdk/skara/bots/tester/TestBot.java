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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.census.Census;
import org.openjdk.skara.ci.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class TestBot implements Bot {
    static class Observation {
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
    private final ConcurrentHashMap<String, Observation> states;
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
        this.states = new ConcurrentHashMap<>();
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
        for (var pr : repo.openPullRequests()) {
            var workItem = new TestWorkItem(ci,
                                            approversGroupId,
                                            allowlist,
                                            availableJobs,
                                            defaultJobs,
                                            name,
                                            storage,
                                            pr,
                                            isCommitter);
            if (cache.needsUpdate(pr)) {
                ret.add(workItem);
            } else {
                ret.add(new TestUpdateNeededWorkItem(pr, ci, states, workItem));
            }
        }

        return ret;
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String toString() {
        return "TestBot@" + repo.name();
    }
}
