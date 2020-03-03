/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.census.Census;
import org.openjdk.skara.census.Project;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.logging.Logger;

public class CommitterCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.committer");
    private final Census census;

    CommitterCheck(Census census) {
        this.census = census;
    }

    private boolean hasRole(Project project, String role, String username, int version) {
        switch (role) {
            case "lead":
                return project.isLead(username, version);
            case "reviewer":
                return project.isReviewer(username, version);
            case "committer":
                return project.isCommitter(username, version);
            case "author":
                return project.isAuthor(username, version);
            case "contributor":
                return census.isContributor(username);
            default:
                throw new IllegalStateException("Unsupported role: " + role);
        }
    }


    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf) {
        var issues = new ArrayList<Issue>();
        var project = census.project(conf.general().project());
        var version = conf.census().version();
        var domain = conf.census().domain();
        var role = conf.checks().committer().role();
        var metadata = CommitIssue.metadata(commit, message, conf, this);

        var committer = commit.committer();
        if (committer.name() == null || committer.name().isEmpty()) {
            log.finer("issue: committer.name is null or empty");
            issues.add(new CommitterNameIssue(metadata));
        }
        if (committer.email() == null || !committer.email().endsWith("@" + domain)) {
            log.finer("issue: committer.email is null or does not end with @" + domain);
            issues.add(new CommitterEmailIssue(domain, metadata));
        }

        if (committer.name() != null || committer.email() != null) {
            var username = committer.email() == null ?
                committer.name() : committer.email().split("@")[0];
            var allowedToMerge = conf.checks().committer().allowedToMerge();
            if (!commit.isMerge() || !allowedToMerge.contains(username)) {
                if (!hasRole(project, role, username, version)) {
                    log.finer("issue: committer does not have role " + role);
                    issues.add(new CommitterIssue(project, metadata));
                }
            }
        }

        return issues.iterator();
    }

    @Override
    public String name() {
        return "committer";
    }

    @Override
    public String description() {
        return "Change must contain a proper committer";
    }
}
