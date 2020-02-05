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
package org.openjdk.skara.bots.csr;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.forge.PullRequestUpdateCache;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.issuetracker.Issue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

class CSRBot implements Bot, WorkItem {
    private final static String CSR_LABEL = "csr";
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repo;
    private final IssueProject project;
    private final PullRequestUpdateCache cache;
    private final Set<String> hasCSRLabel = new HashSet<>();

    CSRBot(HostedRepository repo, IssueProject project) {
        this.repo = repo;
        this.project = project;
        this.cache = new PullRequestUpdateCache();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CSRBot)) {
            return false;
        }

        return !repo.webUrl().equals(((CSRBot) other).repo.webUrl());
    }

    private String describe(PullRequest pr) {
        return repo.name() + "#" + pr.id();
    }

    @Override
    public void run(Path scratchPath) {
        var prs = repo.pullRequests();
        for (var pr : prs) {
            if (!cache.needsUpdate(pr)) {
                continue;
            }

            log.info("Checking CSR label for " + describe(pr) + "...");
            if (pr.labels().contains(CSR_LABEL)) {
                hasCSRLabel.add(pr.id());
            }
        }

        for (var pr : prs) {
            if (!hasCSRLabel.contains(pr.id())) {
                continue;
            }

            var issue = org.openjdk.skara.vcs.openjdk.Issue.fromString(pr.title());
            if (issue.isEmpty()) {
                log.info("No issue found in title for " + describe(pr));
                continue;
            }
            var jbsIssue = project.issue(issue.get().id());
            if (jbsIssue.isEmpty()) {
                log.info("No issue found in JBS for " + describe(pr));
                continue;
            }

            for (var link : jbsIssue.get().links()) {
                var relationship = link.relationship();
                if (relationship.isPresent() && relationship.get().equals("csr for")) {
                    var csr = link.issue().orElseThrow(
                            () -> new IllegalStateException("Link with title 'csr for' does not contain issue")
                    );
                    var resolution = csr.properties().get("resolution").get("name").asString();
                    log.info("Found CSR for " + describe(pr));
                    if (csr.state() == Issue.State.CLOSED && resolution.equals("Approved")) {
                        log.info("CSR closed and approved for " + repo.name() + "#" + pr.id() + ", removing csr label");
                        pr.removeLabel(CSR_LABEL);
                        hasCSRLabel.remove(pr.id());
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "CSRBot@" + repo.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }
}
