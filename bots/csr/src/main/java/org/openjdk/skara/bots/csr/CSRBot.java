/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Link;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

class CSRBot implements Bot, WorkItem {
    private final static String CSR_LABEL = "csr";
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repo;
    private final IssueProject project;
    private final PullRequestUpdateCache cache;

    CSRBot(HostedRepository repo, IssueProject project) {
        this.repo = repo;
        this.project = project;
        this.cache = new PullRequestUpdateCache();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CSRBot)) {
            return true;
        }

        return !repo.isSame(((CSRBot) other).repo);
    }

    private String describe(PullRequest pr) {
        return repo.name() + "#" + pr.id();
    }

    private static Optional<Link> csrLink(Issue issue) {
        return issue == null ? Optional.empty() : issue.links().stream()
                .filter(link -> link.relationship().isPresent() && "csr for".equals(link.relationship().get())).findAny();
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var prs = repo.pullRequests();

        for (var pr : prs) {
            var issue = org.openjdk.skara.vcs.openjdk.Issue.fromStringRelaxed(pr.title());
            if (issue.isEmpty()) {
                log.info("No issue found in title for " + describe(pr));
                continue;
            }
            var jbsIssue = project.issue(issue.get().shortId());
            if (jbsIssue.isEmpty()) {
                log.info("No issue found in JBS for " + describe(pr));
                continue;
            }

            var csrOptional = csrLink(jbsIssue.get()).flatMap(Link::issue);
            if (csrOptional.isEmpty()) {
                log.info("Not found CSR for " + describe(pr));
                continue;
            }

            var csr = csrOptional.get();
            log.info("Found CSR for " + describe(pr) + ". It has id " + csr.id());
            var resolution = csr.properties().get("resolution");
            if (resolution == null || resolution.isNull()) {
                if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue resolution is null for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue resolution is null for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }
            var name = resolution.get("name");
            if (name == null || name.isNull()) {
                if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue resolution name is null for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue resolution name is null for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            if (csr.state() != Issue.State.CLOSED) {
                if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue state is not closed for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue state is not closed for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            if (!name.asString().equals("Approved")) {
                if (name.asString().equals("Withdrawn")) {
                    // This condition is necessary to prevent the bot from adding the CSR label again.
                    // And the bot can't remove the CSR label automatically here.
                    // Because the PR author with the role of Committer may withdraw a CSR that
                    // a Reviewer had requested and integrate it without satisfying that requirement.
                    log.info("CSR closed and withdrawn for " + describe(pr) + ", not revising (not adding and not removing) CSR label");
                } else if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue resolution is not 'Approved' for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue resolution is not 'Approved' for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            if (pr.labelNames().contains(CSR_LABEL)) {
                log.info("CSR closed and approved for " + describe(pr) + ", removing CSR label");
                pr.removeLabel(CSR_LABEL);
            }
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "CSRBot@" + repo.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    @Override
    public String workItemName() {
        return botName();
    }

    @Override
    public String botName() {
        return name();
    }

    @Override
    public String name() {
        return CSRBotFactory.NAME;
    }
}
