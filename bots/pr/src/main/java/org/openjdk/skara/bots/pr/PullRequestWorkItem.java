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
package org.openjdk.skara.bots.pr;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import org.openjdk.skara.bot.ApprovalInfo;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.forge.PullRequest;

import java.util.function.Consumer;
import org.openjdk.skara.jbs.Backports;
import org.openjdk.skara.jbs.JdkVersion;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.openjdk.Issue;

abstract class PullRequestWorkItem implements WorkItem {
    final Consumer<RuntimeException> errorHandler;
    final PullRequestBot bot;
    final String prId;
    /**
     * The updatedAt timestamp of the PR that triggered this WorkItem at the
     * time it was triggered. Used for tracking reaction latency of the bot
     * through logging. This is the best estimated value, which is the last
     * updatedAt value when the bot finds the PR. This value is propagated
     * through chains of WorkItems, as the complete chain is considered to have
     * been triggered by the same PR update.
     */
    final ZonedDateTime prUpdatedAt;
    PullRequest pr;
    private String requestLabelName = null;
    private String approvalLabelName = null;
    private String disapprovalLabelName = null;

    PullRequestWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler,
            ZonedDateTime prUpdatedAt) {
        this.bot = bot;
        this.prId = prId;
        this.errorHandler = errorHandler;
        this.prUpdatedAt = prUpdatedAt;
    }

    @Override
    public final boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem otherItem)) {
            return true;
        }
        if (!(prId.equals(otherItem.prId) && bot.repo().isSame(otherItem.bot.repo()))) {
            return true;
        }
        return false;
    }

    /**
     * Loads the PR from the remote repo at the start of run to guarantee that all
     * PullRequestWorkItems have a coherent and current view of the PR to avoid
     * races. When the run method is called, we are guaranteed to be the only
     * WorkItem executing on this specific PR through the concurrentWith method.
     * <p>
     * Subclasses should override prRun instead of this method.
     */
    @Override
    public final Collection<WorkItem> run(Path scratchPath) {
        pr = bot.repo().pullRequest(prId);
        return prRun(scratchPath);
    }

    abstract Collection<WorkItem> prRun(Path scratchPath);

    @Override
    public final void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }

    @Override
    public String botName() {
        return bot.name();
    }

    /**
     * Logs a latency message. Meant to be used right before returning from prRun(),
     * if it makes sense to log a message at that point.
     * @param message Message to be logged, will get latency string added to it.
     * @param endTime The end time to use to calculate latency
     * @param log The logger to log to
     */
    protected void logLatency(String message, ZonedDateTime endTime, Logger log) {
        var latency = Duration.between(prUpdatedAt, endTime);
        log.log(Level.INFO, message + latency, latency);
    }

    /**
     * Get the request label name from the configuration.
     */
    String requestLabelName() {
        if (requestLabelName == null) {
            for (var approvalInfo : bot.approvalInfos()) {
                if (approvalInfo.branchPattern().matcher(pr.targetRef()).matches()) {
                    requestLabelName = approvalInfo.requestLabel();
                    return requestLabelName;
                }
            }
            requestLabelName = "";
            return requestLabelName;
        }
        return requestLabelName;
    }

    /**
     * Get the approval label name from the configuration.
     */
    String approvalLabelName() {
        if (approvalLabelName == null) {
            for (var approvalInfo : bot.approvalInfos()) {
                if (approvalInfo.branchPattern().matcher(pr.targetRef()).matches()) {
                    approvalLabelName = approvalInfo.approvalLabel();
                    return approvalLabelName;
                }
            }
            approvalLabelName = "";
            return approvalLabelName;
        }
        return approvalLabelName;
    }

    /**
     * Get the disapproval label name from the configuration.
     */
    String disapprovalLabelName() {
        if (disapprovalLabelName == null) {
            for (var approvalInfo : bot.approvalInfos()) {
                if (approvalInfo.branchPattern().matcher(pr.targetRef()).matches()) {
                    disapprovalLabelName = approvalInfo.disapprovalLabel();
                    return disapprovalLabelName;
                }
            }
            disapprovalLabelName = "";
            return disapprovalLabelName;
        }
        return disapprovalLabelName;
    }

    /**
     * Judge whether the change of this PR needs the maintainer's approval.
     */
    boolean requiresApproval() {
        return bot.approvalInfos().stream()
                        .anyMatch(this::approvalInfoMatch);
    }

    /**
     * Return the first approval info from the configuration.
     */
    Optional<ApprovalInfo> getApprovalInfo() {
        return bot.approvalInfos().stream()
                .filter(this::approvalInfoMatch)
                .findFirst();
    }

    private boolean approvalInfoMatch(ApprovalInfo info) {
        return info.repo().isSame(pr.repository())
                && info.branchPattern().matcher(pr.targetRef()).matches();
    }

    /**
     * Judge whether a contributor is the maintainer of the repository
     */
    boolean isMaintainer(Contributor author) {
        var approvalInfo = getApprovalInfo();
        return approvalInfo.get().maintainers().stream()
                .anyMatch(name -> (name.equals(author.fullName().orElse(null)) || name.equals(author.username())));
    }

    List<Issue> issues(boolean withCsr, boolean withJep) {
        var issue = Issue.fromStringRelaxed(pr.title());
        if (issue.isPresent()) {
            var issues = new ArrayList<Issue>();
            issues.add(issue.get());
            issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), pr.comments()));
            if (withCsr) {
                getCsrIssue(issue.get()).ifPresent(issues::add);
            }
            if (withJep) {
                getJepIssue().ifPresent(issues::add);
            }
            return issues;
        }
        return List.of();
    }

    /**
     * Get the csr issue. Note: this `Issue` is not the issue in module `issuetracker`.
     */
    Optional<Issue> getCsrIssue(Issue issue) {
        var issueProject = bot.issueProject();
        if (issueProject == null) {
            return Optional.empty();
        }
        var jbsIssueOpt = issueProject.issue(issue.shortId());
        if (jbsIssueOpt.isEmpty()) {
            return Optional.empty();
        }

        var versionOpt = getVersion();
        if (versionOpt.isEmpty()) {
            return Optional.empty();
        }

        return Backports.findCsr(jbsIssueOpt.get(), versionOpt.get())
                .flatMap(perIssue -> Issue.fromStringRelaxed(perIssue.id() + ": " + perIssue.title()));
    }

    Optional<Issue> getJepIssue() {
        var comment = getJepComment();
        if (comment.isPresent()) {
            return Issue.fromStringRelaxed(comment.get().group(2) + ": " + comment.get().group(3));
        }
        return Optional.empty();
    }

    /**
     * Get the fix version from the PR.
     */
    Optional<JdkVersion> getVersion() {
        var confFile = pr.repository().fileContents(".jcheck/conf", pr.targetRef());
        var configuration = JCheckConfiguration.parse(confFile.lines().toList());
        var version = configuration.general().version().orElse(null);
        if (version == null || "".equals(version)) {
            return Optional.empty();
        }
        return JdkVersion.parse(version);
    }

    Optional<Matcher> getJepComment() {
        var jepComment = pr.comments().stream()
                .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
                .flatMap(comment -> comment.body().lines())
                .map(JEPCommand.jepMarkerPattern::matcher)
                .filter(Matcher::find)
                .reduce((first, second) -> second)
                .orElse(null);
        if (jepComment == null) {
            return Optional.empty();
        }

        var issueId = jepComment.group(2);
        if ("unneeded".equals(issueId)) {
            return  Optional.empty();
        }

        return Optional.of(jepComment);
    }
}
