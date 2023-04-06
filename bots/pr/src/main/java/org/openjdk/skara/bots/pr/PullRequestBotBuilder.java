/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.vcs.*;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class PullRequestBotBuilder {
    private HostedRepository repo;
    private HostedRepository censusRepo;
    private String censusRef = Branch.defaultFor(VCS.GIT).name();
    private LabelConfiguration labelConfiguration = LabelConfigurationJson.builder().build();
    private Map<String, String> externalPullRequestCommands = Map.of();
    private Map<String, String> externalCommitCommands = Map.of();
    private Map<String, String> blockingCheckLabels = Map.of();
    private Set<String> readyLabels = Set.of();
    private Set<String> twoReviewersLabels = Set.of();
    private Set<String> twentyFourHoursLabels = Set.of();
    private Map<String, Pattern> readyComments = Map.of();
    private IssueProject issueProject = null;
    private boolean ignoreStaleReviews = false;
    private Pattern allowedTargetBranches = Pattern.compile(".*");
    private Path seedStorage = null;
    private HostedRepository confOverrideRepo = null;
    private String confOverrideName = ".conf/jcheck";
    private String confOverrideRef = Branch.defaultFor(VCS.GIT).name();
    private String censusLink = null;
    private boolean enableCsr = false;
    private boolean enableJep = false;
    private Map<String, HostedRepository> forks = Map.of();
    private Set<String> integrators = Set.of();
    private Set<Integer> excludeCommitCommentsFrom = Set.of();
    private boolean reviewCleanBackport = false;
    private String mlbridgeBotName;
    private boolean reviewMerge = false;
    private boolean processPR = true;
    private boolean processCommit = true;

    PullRequestBotBuilder() {
    }

    public PullRequestBotBuilder repo(HostedRepository repo) {
        this.repo = repo;
        return this;
    }

    public PullRequestBotBuilder censusRepo(HostedRepository censusRepo) {
        this.censusRepo = censusRepo;
        return this;
    }

    public PullRequestBotBuilder censusRef(String censusRef) {
        this.censusRef = censusRef;
        return this;
    }

    public PullRequestBotBuilder labelConfiguration(LabelConfiguration labelConfiguration) {
        this.labelConfiguration = labelConfiguration;
        return this;
    }

    public PullRequestBotBuilder externalPullRequestCommands(Map<String, String> externalPullRequestCommands) {
        this.externalPullRequestCommands = externalPullRequestCommands;
        return this;
    }

    public PullRequestBotBuilder externalCommitCommands(Map<String, String> externalCommitCommands) {
        this.externalCommitCommands = externalCommitCommands;
        return this;
    }

    public PullRequestBotBuilder blockingCheckLabels(Map<String, String> blockingCheckLabels) {
        this.blockingCheckLabels = blockingCheckLabels;
        return this;
    }

    public PullRequestBotBuilder readyLabels(Set<String> readyLabels) {
        this.readyLabels = readyLabels;
        return this;
    }

    public PullRequestBotBuilder twoReviewersLabels(Set<String> twoReviewersLabels) {
        this.twoReviewersLabels = twoReviewersLabels;
        return this;
    }

    public PullRequestBotBuilder twentyFourHoursLabels(Set<String> twentyFourHoursLabels) {
        this.twentyFourHoursLabels = twentyFourHoursLabels;
        return this;
    }

    public PullRequestBotBuilder readyComments(Map<String, Pattern> readyComments) {
        this.readyComments = readyComments;
        return this;
    }

    public PullRequestBotBuilder issueProject(IssueProject issueProject) {
        this.issueProject = issueProject;
        return this;
    }

    public PullRequestBotBuilder ignoreStaleReviews(boolean ignoreStaleReviews) {
        this.ignoreStaleReviews = ignoreStaleReviews;
        return this;
    }

    public PullRequestBotBuilder allowedTargetBranches(String allowedTargetBranches) {
        this.allowedTargetBranches = Pattern.compile(allowedTargetBranches);
        return this;
    }

    public PullRequestBotBuilder seedStorage(Path seedStorage) {
        this.seedStorage = seedStorage;
        return this;
    }

    public PullRequestBotBuilder confOverrideRepo(HostedRepository confOverrideRepo) {
        this.confOverrideRepo = confOverrideRepo;
        return this;
    }

    public PullRequestBotBuilder confOverrideName(String confOverrideName) {
        this.confOverrideName = confOverrideName;
        return this;
    }

    public PullRequestBotBuilder confOverrideRef(String confOverrideRef) {
        this.confOverrideRef = confOverrideRef;
        return this;
    }

    public PullRequestBotBuilder censusLink(String censusLink) {
        this.censusLink = censusLink;
        return this;
    }

    public PullRequestBotBuilder enableCsr(boolean enableCsr) {
        this.enableCsr = enableCsr;
        return this;
    }

    public PullRequestBotBuilder enableJep(boolean enableJep) {
        this.enableJep = enableJep;
        return this;
    }

    public PullRequestBotBuilder forks(Map<String, HostedRepository> forks) {
        this.forks = forks;
        return this;
    }

    public PullRequestBotBuilder integrators(Set<String> integrators) {
        this.integrators = new HashSet<>(integrators);
        return this;
    }

    public PullRequestBotBuilder excludeCommitCommentsFrom(Set<Integer> excludeCommitCommentsFrom) {
        this.excludeCommitCommentsFrom = excludeCommitCommentsFrom;
        return this;
    }

    public PullRequestBotBuilder reviewCleanBackport(boolean reviewCleanBackport) {
        this.reviewCleanBackport = reviewCleanBackport;
        return this;
    }

    public PullRequestBotBuilder mlbridgeBotName(String mlbridgeBotName) {
        this.mlbridgeBotName = mlbridgeBotName;
        return this;
    }

    public PullRequestBotBuilder reviewMerge(boolean reviewMerge) {
        this.reviewMerge = reviewMerge;
        return this;
    }

    public PullRequestBotBuilder processPR(boolean processPR) {
        this.processPR = processPR;
        return this;
    }

    public PullRequestBotBuilder processCommit(boolean processCommit) {
        this.processCommit = processCommit;
        return this;
    }

    public PullRequestBot build() {
        return new PullRequestBot(repo, censusRepo, censusRef, labelConfiguration,
                                  externalPullRequestCommands, externalCommitCommands,
                                  blockingCheckLabels, readyLabels, twoReviewersLabels, twentyFourHoursLabels,
                                  readyComments, issueProject, ignoreStaleReviews,
                                  allowedTargetBranches, seedStorage, confOverrideRepo, confOverrideName,
                                  confOverrideRef, censusLink, forks, integrators, excludeCommitCommentsFrom,
                                  enableCsr, enableJep, reviewCleanBackport, mlbridgeBotName, reviewMerge,
                                  processPR, processCommit);
    }
}
