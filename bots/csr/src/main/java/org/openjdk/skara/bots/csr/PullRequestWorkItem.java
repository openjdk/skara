/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.bots.common.SolvesTracker;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.jbs.Backports;

import static org.openjdk.skara.bots.common.PullRequestConstants.*;

/**
 * The PullRequestWorkItem is the work horse of the CSRBot. It gets triggered when
 * either the pull request itself, or any CSR issue associated with it have been
 * updated. It operates on one single pull request and re-evaluates the CSR state
 * for it.
 */
class PullRequestWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.csr");
    private final HostedRepository repository;
    private final String prId;
    private final IssueProject project;
    private final Consumer<RuntimeException> errorHandler;
    /**
     * The updatedAt timestamp of the external entity that triggered this WorkItem,
     * which would be either a PR or a CSR Issue. Used for tracking reaction legacy
     * of the bot through logging.
     */
    private final ZonedDateTime triggerUpdatedAt;

    public PullRequestWorkItem(HostedRepository repository, String prId, IssueProject project,
            ZonedDateTime triggerUpdatedAt, Consumer<RuntimeException> errorHandler) {
        this.repository = repository;
        this.prId = prId;
        this.project = project;
        this.triggerUpdatedAt = triggerUpdatedAt;
        this.errorHandler = errorHandler;
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

    private String generateCSRProgressMessage(org.openjdk.skara.issuetracker.Issue issue) {
        return "Change requires CSR request [" + issue.id() + "](" + issue.webUrl() + ") to be approved";
    }

    private boolean hasCsrIssueAndProgress(PullRequest pr, Issue csr) {
        var statusMessage = getStatusMessage(pr);
        return hasCsrIssue(statusMessage, csr) &&
                (statusMessage.contains("- [ ] " + generateCSRProgressMessage(csr)) ||
                        statusMessage.contains("- [x] " + generateCSRProgressMessage(csr)));
    }

    private boolean hasCsrIssueAndProgressChecked(PullRequest pr, Issue csr) {
        var statusMessage = getStatusMessage(pr);
        return hasCsrIssue(statusMessage, csr) && statusMessage.contains("- [x] " + generateCSRProgressMessage(csr));
    }

    private boolean hasCsrIssue(String statusMessage, Issue csr) {
        return statusMessage.contains(csr.id()) &&
                statusMessage.contains(csr.webUrl().toString()) &&
                statusMessage.contains(csr.title() + " (**CSR**)");
    }

    private boolean hasWithdrawnCsrIssue(String statusMessage, Issue csr) {
        return statusMessage.contains(csr.id()) &&
                statusMessage.contains(csr.webUrl().toString()) &&
                statusMessage.contains(csr.title() + " (**CSR**) (Withdrawn)");
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
        if (statusMessage.isEmpty()) {
            log.info("No PROGRESS_MARKER found in PR body, wait for first CheckRun before adding csr update marker.");
        } else if (!statusMessage.contains(CSR_UPDATE_MARKER)) {
            pr.setBody(pr.body() + "\n" + CSR_UPDATE_MARKER + "\n");
        } else {
            log.info("The pull request " + describe(pr) + " has already had a csr update marker. Do not need to add it again.");
        }
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var pr = repository.pullRequest(prId);
        // All the issues this pr solves
        var mainIssue = org.openjdk.skara.vcs.openjdk.Issue.fromStringRelaxed(pr.title());
        var issues = new ArrayList<org.openjdk.skara.vcs.openjdk.Issue>();
        mainIssue.ifPresent(issues::add);
        issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), pr.comments()));

        if (issues.isEmpty()) {
            log.info("No issue found for " + describe(pr));
            return List.of();
        }

        var versionOpt = BotUtils.getVersion(pr);
        if (versionOpt.isEmpty()) {
            log.info("No fix version found in `.jcheck/conf` for " + describe(pr));
            return List.of();
        }

        boolean notExistingUnresolvedCSR = true;
        boolean needToAddUpdateMarker = false;
        boolean existingCSR = false;
        boolean existingApprovedCSR = false;

        for (var issue : issues) {
            var jbsIssueOpt = project.issue(issue.shortId());
            if (jbsIssueOpt.isEmpty()) {
                // An issue could not be found, so the csr label cannot be removed
                notExistingUnresolvedCSR = false;
                var issueId = issue.project().isEmpty() ? (project.name() + "-" + issue.id()) : issue.id();
                log.info(issueId + " for " + describe(pr) + " not found");
                continue;
            }

            var csrOptional = Backports.findCsr(jbsIssueOpt.get(), versionOpt.get());
            if (csrOptional.isEmpty()) {
                log.info("No CSR found for issue " + jbsIssueOpt.get().id() + " for " + describe(pr) + " with fixVersion " + versionOpt.get().raw());
                continue;
            }
            var csr = csrOptional.get();
            existingCSR = true;

            log.info("Found CSR " + csr.id() + " for issue " + jbsIssueOpt.get().id() + " for " + describe(pr));
            if (!hasCsrIssueAndProgress(pr, csr) && !isWithdrawnCSR(csr)) {
                // If the PR body doesn't have the CSR issue or doesn't have the CSR progress,
                // this bot need to add the csr update marker so that the PR bot can update the message of the PR body.
                log.info("The PR body doesn't have the CSR issue or progress, adding the csr update marker for this csr issue"
                        + csr.id() + " for " + describe(pr));
                needToAddUpdateMarker = true;
            }

            var resolution = csr.properties().get("resolution");
            if (resolution == null || resolution.isNull()) {
                notExistingUnresolvedCSR = false;
                if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue resolution is null for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue resolution is null for csr issue " + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            var name = resolution.get("name");
            if (name == null || name.isNull()) {
                notExistingUnresolvedCSR = false;
                if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue resolution name is null for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue resolution name is null for csr issue " + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            if (csr.state() != Issue.State.CLOSED) {
                notExistingUnresolvedCSR = false;
                if (!pr.labelNames().contains(CSR_LABEL)) {
                    log.info("CSR issue state is not closed for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    log.info("CSR issue state is not closed for csr issue" + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            if (!name.asString().equals("Approved")) {
                if (name.asString().equals("Withdrawn")) {
                    // This condition is necessary to prevent the bot from adding the CSR label again.
                    // And the bot can't remove the CSR label automatically here.
                    // Because the PR author with the role of Committer may withdraw a CSR that
                    // a Reviewer had requested and integrate it without satisfying that requirement.
                    if (!hasWithdrawnCsrIssue(getStatusMessage(pr), csr)) {
                        needToAddUpdateMarker = true;
                    }
                    log.info("CSR closed and withdrawn for csr issue " + csr.id() + " for " + describe(pr));
                } else if (!pr.labelNames().contains(CSR_LABEL)) {
                    notExistingUnresolvedCSR = false;
                    log.info("CSR issue resolution is not 'Approved' for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    pr.addLabel(CSR_LABEL);
                } else {
                    notExistingUnresolvedCSR = false;
                    log.info("CSR issue resolution is not 'Approved' for csr issue " + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            } else {
                existingApprovedCSR = true;
            }

            // The CSR issue has been closed and approved
            if (!hasCsrIssueAndProgressChecked(pr, csr)) {
                // If the PR body doesn't have the CSR issue or doesn't have the CSR progress or the CSR progress checkbox is not selected,
                // this bot need to add the csr update marker so that the PR bot can update the message of the PR body.
                log.info("CSR closed and approved for " + describe(pr) + ", adding the csr update marker");
                needToAddUpdateMarker = true;
            }
        }
        if (needToAddUpdateMarker) {
            addUpdateMarker(pr);
        }
        if (notExistingUnresolvedCSR && existingCSR && (!isCSRNeeded(pr.comments()) || existingApprovedCSR) && pr.labelNames().contains(CSR_LABEL)) {
            log.info("All CSR issues closed and approved for " + describe(pr) + ", removing CSR label");
            pr.removeLabel(CSR_LABEL);
        }
        logLatency();
        return List.of();
    }

    /**
     * Determine whether the CSR label is added via '/csr needed' command
     */
    private boolean isCSRNeeded(List<Comment> comments) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            var comment = comments.get(i);
            if (comment.body().contains(CSR_NEEDED_MARKER)) {
                return true;
            }
            if (comment.body().contains(CSR_UNNEEDED_MARKER)) {
                return false;
            }
        }
        return false;
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

    public final void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }

    private boolean isWithdrawnCSR(Issue csr) {
        if (csr.isClosed()) {
            var resolution = csr.properties().get("resolution");
            if (resolution != null && !resolution.isNull()) {
                var name = resolution.get("name");
                if (name != null && !name.isNull() && name.asString().equals("Withdrawn")) {
                    return true;
                }
            }
        }
        return false;
    }
}
