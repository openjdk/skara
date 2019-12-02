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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class TestBot implements Bot {
    private final ContinuousIntegration ci;
    private final String approversGroupId;
    private final List<String> availableJobs;
    private final List<String> defaultJobs;
    private final String name;
    private final Path storage;
    private final HostedRepository repo;
    private final PullRequestUpdateCache cache;
    private final Set<String> seen;

    TestBot(ContinuousIntegration ci,
            String approversGroupId,
            List<String> availableJobs,
            List<String> defaultJobs,
            String name,
            Path storage,
            HostedRepository repo) {
        this.ci = ci;
        this.approversGroupId = approversGroupId;
        this.availableJobs = availableJobs;
        this.defaultJobs = defaultJobs;
        this.name = name;
        this.storage = storage;
        this.repo = repo;
        this.cache = new PullRequestUpdateCache();
        this.seen = new HashSet<>();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var ret = new ArrayList<WorkItem>();

        var host = repo.webUrl().getHost();
        var repoId = Long.toString(repo.id());
        for (var pr : repo.pullRequests()) {
            if (cache.needsUpdate(pr)) {
                ret.add(new TestWorkItem(ci,
                                         approversGroupId,
                                         availableJobs,
                                         defaultJobs,
                                         name,
                                         storage,
                                         pr));
            } else {
                // is there a job running for this PR?
                var colon = "%3A";
                var asterisk = "%2A";
                var id = host + "-" + repoId  + "-"+ pr.id() + "-" + asterisk;
                try {
                    var jobs = ci.query("id" + colon + id);
                    if (!jobs.isEmpty()) {
                        if (jobs.stream().anyMatch(j -> j.isRunning() || !seen.contains(j.id()))) {
                            ret.add(new TestWorkItem(ci,
                                                     approversGroupId,
                                                     availableJobs,
                                                     defaultJobs,
                                                     name,
                                                     storage,
                                                     pr));
                        }
                        seen.addAll(jobs.stream().map(Job::id).collect(Collectors.toList()));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        return ret;
    }
}
