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
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.forge.PullRequest;

import java.io.*;
import java.nio.file.*;
import java.util.*;

class InMemoryContinuousIntegration implements ContinuousIntegration {
    static class Submission {
        Path source;
        List<String> jobs;
        String id;

        Submission(Path source, List<String> jobs, String id) {
            this.source = source;
            this.jobs = jobs;
            this.id = id;
        }
    }

    List<Submission> submissions = new ArrayList<Submission>();
    List<String> cancelled = new ArrayList<String>();
    Map<String, InMemoryJob> jobs = new HashMap<>();
    boolean throwOnSubmit = false;
    boolean isValid = true;
    Map<String, HostUser> users = new HashMap<>();
    HostUser currentUser = null;
    Map<String, Set<HostUser>> groups = new HashMap<>();

    @Override
    public boolean isValid() {
        return isValid;
    }

    @Override
    public Optional<HostUser> user(String username) {
        return Optional.ofNullable(users.get(username));
    }

    @Override
    public HostUser currentUser() {
        return currentUser;
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        var group = groups.get(groupId);
        return group != null && group.contains(user);
    }

    @Override
    public Job submit(Path source, List<String> jobs, String id) throws IOException {
        if (throwOnSubmit) {
            throw new IOException("Something went wrong");
        }
        submissions.add(new Submission(source, jobs, id));
        return job(id);
    }

    @Override
    public Job job(String id) throws IOException {
        return jobs.get(id);
    }

    @Override
    public void cancel(String id) throws IOException {
        cancelled.add(id);
    }

    @Override
    public List<Job> jobsFor(PullRequest pr) throws IOException {
        return List.of();
    }
}
