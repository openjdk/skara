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
package org.openjdk.skara.bots.csr;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.jbs.Backports;
import org.openjdk.skara.jbs.JdkVersion;
import org.openjdk.skara.jcheck.JCheckConfiguration;

/**
 * The PullRequestWorkItem is the work horse of the CSRBot. It gets triggered when
 * either the pull request itself, or any CSR issue associated with it have been
 * updated. It operates on one single pull request and re-evaluates the CSR state
 * for it.
 */
class PullRequestWorkItem implements WorkItem {
    private final static String CSR_LABEL = "csr";
    private final static String CSR_UPDATE_MARKER = "<!-- csr: 'update' -->";
    private static final String PROGRESS_MARKER = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.csr");
    private final HostedRepository repository;
    private final String prId;
    private final IssueProject project;
    final Consumer<RuntimeException> errorHandler;
    /**
     * The updatedAt timestamp of the external entity that triggered this WorkItem,
     * which would be either a PR or a CSR Issue. Used for tracking reaction legacy
     * of the bot through logging.
     */
    private final ZonedDateTime triggerUpdatedAt;

    public PullRequestWorkItem(HostedRepository repository, String prId, IssueProject project,
            Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt) {
        this.repository = repository;
        this.prId = prId;
        this.project = project;
        this.errorHandler = errorHandler;
        this.triggerUpdatedAt = triggerUpdatedAt;
    }

    @Override
    public String toString() {
        return botName()+ "/PullRequestWorkItem@" + repository.name() + "#" + prId;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem item)) {
            return true;
        }

        return !(repository.isSame(item.repository) && prId.equals(item.prId));
    }

    private String describe(PullRequest pr) {
        return pr.repository().name() + "#" + pr.id();
    }

    /**
     * Get the fix version from the provided PR.
     */
    private static Optional<JdkVersion> getVersion(PullRequest pullRequest) {
        var confFile = pullRequest.repository().fileContents(".jcheck/conf", pullRequest.targetRef());
        var configuration = JCheckConfiguration.parse(confFile.lines().toList());
        var version = configuration.general().version().orElse(null);
        if (version == null || "".equals(version)) {
            return Optional.empty();
        }
        return JdkVersion.parse(version);
    }

    private boolean hasCsrIssueAndProgress(PullRequest pr, Issue csr) {
        var statusMessage = getStatusMessage(pr);
        return hasCsrIssue(statusMessage, csr) &&
                (statusMessage.contains("- [ ] Change requires a CSR request to be approved") ||
                        statusMessage.contains("- [x] Change requires a CSR request to be approved"));
    }

    private boolean hasCsrIssueAndProgressChecked(PullRequest pr, Issue csr) {
        var statusMessage = getStatusMessage(pr);
        return hasCsrIssue(statusMessage, csr) && statusMessage.contains("- [x] Change requires a CSR request to be approved");
    }

    private boolean hasCsrIssue(String statusMessage, Issue csr) {
        return statusMessage.contains(csr.id()) &&
                statusMessage.contains(csr.webUrl().toString()) &&
                statusMessage.contains(csr.title() + " (**CSR**)");
    }

    private String getStatusMessage(PullRequest pr) {
        var lastIndex = pr.body().lastIndexOf(PROGRESS_MARKER);
        if (lastIndex == -1) {
            return "";
        } else {
            return pr.body().substring(lastIndex);
        }
    }

    private void addUpdateMarker(PullRequest pr) {
        var statusMessage = getStatusMessage(pr);
        if (!statusMessage.contains(CSR_UPDATE_MARKER)) {
            pr.setBody(pr.body() + "\n" + CSR_UPDATE_MARKER + "\n");
        } else {
            log.info("The pull request " + describe(pr) + " has already had a csr update marker. Do not need to add it again.");
        }
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var pr = repository.pullRequest(prId);

        var issue = org.openjdk.skara.vcs.openjdk.Issue.fromStringRelaxed(pr.title());
        if (issue.isEmpty()) {
            log.info("No issue found in title for " + describe(pr));
            return List.of();
        }
        var jbsIssueOpt = project.issue(issue.get().shortId());
        if (jbsIssueOpt.isEmpty()) {
            log.info("No issue found in JBS for " + describe(pr));
            return List.of();
        }

        var versionOpt = getVersion(pr);
        if (versionOpt.isEmpty()) {
            log.info("No fix version found in `.jcheck/conf` for " + describe(pr));
            return List.of();
        }

        var csrOptional = Backports.findCsr(jbsIssueOpt.get(), versionOpt.get());
        if (csrOptional.isEmpty()) {
            log.info("No CSR found for " + describe(pr));
            return List.of();
        }
        var csr = csrOptional.get();

        log.info("Found CSR " + csr.id() + " for " + describe(pr));
        if (!hasCsrIssueAndProgress(pr, csr)) {
            // If the PR body doesn't have the CSR issue or doesn't have the CSR progress,
            // this bot need to add the csr update marker so that the PR bot can update the message of the PR body.
            log.info("The PR body doesn't have the CSR issue or progress, adding the csr update marker for " + describe(pr));
            addUpdateMarker(pr);
        }

        var resolution = csr.properties().get("resolution");
        if (resolution == null || resolution.isNull()) {
            if (!pr.labelNames().contains(CSR_LABEL)) {
                log.info("CSR issue resolution is null for " + describe(pr) + ", adding the CSR label");
                pr.addLabel(CSR_LABEL);
            } else {
                log.info("CSR issue resolution is null for " + describe(pr) + ", not removing the CSR label");
            }
            logLatency();
            return List.of();
        }
        var name = resolution.get("name");
        if (name == null || name.isNull()) {
            if (!pr.labelNames().contains(CSR_LABEL)) {
                log.info("CSR issue resolution name is null for " + describe(pr) + ", adding the CSR label");
                pr.addLabel(CSR_LABEL);
            } else {
                log.info("CSR issue resolution name is null for " + describe(pr) + ", not removing the CSR label");
            }
            logLatency();
            return List.of();
        }

        if (csr.state() != Issue.State.CLOSED) {
            if (!pr.labelNames().contains(CSR_LABEL)) {
                log.info("CSR issue state is not closed for " + describe(pr) + ", adding the CSR label");
                pr.addLabel(CSR_LABEL);
            } else {
                log.info("CSR issue state is not closed for " + describe(pr) + ", not removing the CSR label");
            }
            logLatency();
            return List.of();
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
            logLatency();
            return List.of();
        }

        if (pr.labelNames().contains(CSR_LABEL)) {
            log.info("CSR closed and approved for " + describe(pr) + ", removing CSR label");
            pr.removeLabel(CSR_LABEL);
        }
        if (!hasCsrIssueAndProgressChecked(pr, csr)) {
            // If the PR body doesn't have the CSR issue or doesn't have the CSR progress or the CSR progress checkbox is not selected,
            // this bot need to add the csr update marker so that the PR bot can update the message of the PR body.
            log.info("CSR closed and approved for " + describe(pr) + ", adding the csr update marker");
            addUpdateMarker(pr);
        }
        logLatency();
        return List.of();
    }

    private void logLatency() {
        if (log.isLoggable(Level.INFO)) {
            var updatedPr = repository.pullRequest(prId);
            var latency = Duration.between(triggerUpdatedAt, updatedPr.updatedAt());
            log.log(Level.INFO, "Time from trigger to CSR state updated in PR " + latency, latency);
        }
    }

    @Override
    public String botName() {
        return CSRBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "pr";
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }
}
