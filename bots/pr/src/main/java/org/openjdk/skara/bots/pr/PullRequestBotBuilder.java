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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.IssueProject;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class PullRequestBotBuilder {
    private HostedRepository repo;
    private HostedRepository censusRepo;
    private String censusRef = "master";
    private LabelConfiguration labelConfiguration = LabelConfiguration.newBuilder().build();
    private Map<String, String> externalCommands = Map.of();
    private Map<String, String> blockingCheckLabels = Map.of();
    private Set<String> readyLabels = Set.of();
    private Map<String, Pattern> readyComments = Map.of();
    private IssueProject issueProject = null;
    private boolean ignoreStaleReviews = false;
    private Set<String> allowedIssueTypes = null;
    private Pattern allowedTargetBranches = Pattern.compile(".*");
    private Path seedStorage = null;

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

    public PullRequestBotBuilder externalCommands(Map<String, String> externalCommands) {
        this.externalCommands = externalCommands;
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

    public PullRequestBotBuilder allowedIssueTypes(Set<String> allowedIssueTypes) {
        this.allowedIssueTypes = allowedIssueTypes;
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

    public PullRequestBot build() {
        return new PullRequestBot(repo, censusRepo, censusRef, labelConfiguration, externalCommands,
                                  blockingCheckLabels, readyLabels, readyComments, issueProject,
                                  ignoreStaleReviews, allowedIssueTypes, allowedTargetBranches,
                                  seedStorage);
    }
}
