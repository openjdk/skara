/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.network.UncheckedRestException;

import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;
import java.util.regex.Pattern;

class PullRequestBot implements Bot {
    private final HostedRepository remoteRepo;
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final LabelConfiguration labelConfiguration;
    private final Map<String, String> externalPullRequestCommands;
    private final Map<String, String> externalCommitCommands;
    private final Map<String, String> blockingCheckLabels;
    private final Set<String> readyLabels;
    private final Set<String> twoReviewersLabels;
    private final Set<String> twentyFourHoursLabels;
    private final Map<String, Pattern> readyComments;
    private final IssueProject issueProject;
    private final boolean useStaleReviews;
    private final boolean acceptSimpleMerges;
    private final Pattern allowedTargetBranches;
    private final Path seedStorage;
    private final HostedRepository confOverrideRepo;
    private final String confOverrideName;
    private final String confOverrideRef;
    private final String censusLink;
    private final Map<String, HostedRepository> forks;
    private final Set<String> integrators;
    private final Set<Integer> excludeCommitCommentsFrom;
    private final boolean enableCsr;
    private final boolean enableJep;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private final PullRequestPoller poller;
    private final boolean reviewCleanBackport;
    private final String mlbridgeBotName;
    private final MergePullRequestReviewConfiguration reviewMerge;
    private final boolean processPR;
    private final boolean processCommit;
    private final boolean enableMerge;
    private final boolean jcheckMerge;
    private final Set<String> mergeSources;
    private final boolean enableBackport;
    private final Map<String, List<PRRecord>> issuePRMap;
    private final Map<String, Boolean> initializedPRs = new ConcurrentHashMap<>();
    private final Map<String, String> jCheckConfMap = new HashMap<>();
    private final Map<String, Set<String>> targetRefPRMap = new HashMap<>();
    private final Approval approval;
    private boolean initialRun = true;
    private final boolean versionMismatchWarning;
    private final boolean cleanCommandEnabled;
    private final boolean checkContributorStatusForBackportCommand;

    private Instant lastFullUpdate;

    PullRequestBot(HostedRepository repo, HostedRepository censusRepo, String censusRef, LabelConfiguration labelConfiguration,
                   Map<String, String> externalPullRequestCommands, Map<String, String> externalCommitCommands,
                   Map<String, String> blockingCheckLabels, Set<String> readyLabels,
                   Set<String> twoReviewersLabels, Set<String> twentyFourHoursLabels,
                   Map<String, Pattern> readyComments, IssueProject issueProject,
                   boolean useStaleReviews, boolean acceptSimpleMerges, Pattern allowedTargetBranches,
                   Path seedStorage, HostedRepository confOverrideRepo, String confOverrideName,
                   String confOverrideRef, String censusLink, Map<String, HostedRepository> forks,
                   Set<String> integrators, Set<Integer> excludeCommitCommentsFrom, boolean enableCsr, boolean enableJep,
                   boolean reviewCleanBackport, String mlbridgeBotName, MergePullRequestReviewConfiguration reviewMerge, boolean processPR, boolean processCommit,
                   boolean enableMerge, Set<String> mergeSources, boolean jcheckMerge, boolean enableBackport,
                   Map<String, List<PRRecord>> issuePRMap, Approval approval, boolean versionMismatchWarning, boolean cleanCommandEnabled,
                   boolean checkContributorStatusForBackportCommand) {
        remoteRepo = repo;
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        this.labelConfiguration = labelConfiguration;
        this.externalPullRequestCommands = externalPullRequestCommands;
        this.externalCommitCommands = externalCommitCommands;
        this.blockingCheckLabels = blockingCheckLabels;
        this.readyLabels = readyLabels;
        this.twoReviewersLabels = twoReviewersLabels;
        this.twentyFourHoursLabels = twentyFourHoursLabels;
        this.issueProject = issueProject;
        this.readyComments = readyComments;
        this.useStaleReviews = useStaleReviews;
        this.acceptSimpleMerges = acceptSimpleMerges;
        this.allowedTargetBranches = allowedTargetBranches;
        this.seedStorage = seedStorage;
        this.confOverrideRepo = confOverrideRepo;
        this.confOverrideName = confOverrideName;
        this.confOverrideRef = confOverrideRef;
        this.censusLink = censusLink;
        this.forks = forks;
        this.integrators = integrators;
        this.excludeCommitCommentsFrom = excludeCommitCommentsFrom;
        this.enableCsr = enableCsr;
        this.enableJep = enableJep;
        this.reviewCleanBackport = reviewCleanBackport;
        this.mlbridgeBotName = mlbridgeBotName;
        this.reviewMerge = reviewMerge;
        this.processPR = processPR;
        this.processCommit = processCommit;
        this.enableMerge = enableMerge;
        this.mergeSources = mergeSources;
        this.jcheckMerge = jcheckMerge;
        this.enableBackport = enableBackport;
        this.issuePRMap = issuePRMap;
        this.approval = approval;
        this.versionMismatchWarning = versionMismatchWarning;
        this.cleanCommandEnabled = cleanCommandEnabled;
        this.checkContributorStatusForBackportCommand = checkContributorStatusForBackportCommand;

        poller = new PullRequestPoller(repo, true);

        // Only check recently updated when starting up to avoid congestion
        lastFullUpdate = Instant.now();
    }

    static PullRequestBotBuilder newBuilder() {
        return new PullRequestBotBuilder();
    }

    void scheduleRecheckAt(PullRequest pr, Instant expiresAt) {
        log.info("Setting check metadata expiration to: " + expiresAt + " for PR #" + pr.id());
        poller.retryPullRequest(pr, expiresAt);
    }

    private List<WorkItem> getPullRequestWorkItems(List<PullRequest> pullRequests) {
        var ret = new ArrayList<WorkItem>();

        for (var pr : pullRequests) {
            if (pr.state() == Issue.State.OPEN) {
                if (initialRun) {
                    ret.add(CheckWorkItem.fromInitialRunOfPRBot(this, pr.id(), e -> poller.retryPullRequest(pr), pr.updatedAt()));
                } else {
                    ret.add(CheckWorkItem.fromPRBot(this, pr.id(), e -> poller.retryPullRequest(pr), pr.updatedAt()));
                }
            } else {
                // Closed PR's do not need to be checked
                ret.add(new PullRequestCommandWorkItem(this, pr.id(), e -> poller.retryPullRequest(pr), pr.updatedAt(), true));
            }
        }

        initialRun = false;

        return ret;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var workItems = new ArrayList<WorkItem>();
        if (processCommit) {
            workItems.add(new CommitCommentsWorkItem(this, remoteRepo, excludeCommitCommentsFrom));
        }
        if (processPR) {
            List<PullRequest> prs = poller.updatedPullRequests();
            workItems.addAll(getPullRequestWorkItems(prs));

            // Update targetRefPRMap
            for (var pr : prs) {
                var targetRef = pr.targetRef();
                var prId = pr.id();
                targetRefPRMap.values().forEach(s -> s.remove(prId));
                if (pr.isOpen()) {
                    targetRefPRMap.computeIfAbsent(targetRef, key -> new HashSet<>()).add(prId);
                }
            }

            var activeBranches = remoteRepo.branches().stream()
                    .map(HostedBranch::name)
                    .toList();

            var keysToRemove = targetRefPRMap.keySet().stream()
                    .filter(key -> targetRefPRMap.get(key).isEmpty() || !activeBranches.contains(key))
                    .toList();
            keysToRemove.forEach(targetRefPRMap::remove);

            var jCheckConfUpdateRelatedPRs = getJCheckConfUpdateRelatedPRs();
            // Filter out duplicate prs
            var filteredPrs = jCheckConfUpdateRelatedPRs.stream()
                    .filter(pullRequest -> prs.stream()
                            .noneMatch(pr -> pr.isSame(pullRequest)))
                    .toList();
            workItems.addAll(getPullRequestWorkItems(filteredPrs));
            poller.lastBatchHandled();
        }
        return workItems;
    }

    private List<PullRequest> getJCheckConfUpdateRelatedPRs() {
        var ret = new ArrayList<PullRequest>();
        // If there is any pr targets on the ref, then the bot needs to check whether the .jcheck/conf updated in this ref
        var allTargetRefs = targetRefPRMap.keySet().stream()
                .filter(key -> !targetRefPRMap.get(key).isEmpty())
                .toList();
        for (var targetRef : allTargetRefs) {
            try {
                var currConfOpt = remoteRepo.fileContents(".jcheck/conf", targetRef);
                if (currConfOpt.isEmpty()) {
                    continue;
                }
                var currConf = currConfOpt.get();
                if (!jCheckConfMap.containsKey(targetRef)) {
                    jCheckConfMap.put(targetRef, currConf);
                } else if (!jCheckConfMap.get(targetRef).equals(currConf)) {
                    ret.addAll(remoteRepo.openPullRequestsWithTargetRef(targetRef));
                    jCheckConfMap.put(targetRef, currConf);
                }
            } catch (UncheckedRestException e) {
                // If the targetRef is invalid, fileContents() will throw a 404 instead of returning
                // empty. In this case we should ignore this and continue processing other PRs.
                // Any invalid refs will get removed from targetRefMap in the next round.
                if (e.getStatusCode() != 404) {
                    throw e;
                }
            }
        }
        return ret;
    }

    @Override
    public List<WorkItem> processWebHook(JSONValue body) {
        var webHook = remoteRepo.parseWebHook(body);
        if (webHook.isEmpty()) {
            return new ArrayList<>();
        }
        var workItems = new ArrayList<WorkItem>();
        if (processCommit) {
            workItems.add(new CommitCommentsWorkItem(this, remoteRepo, excludeCommitCommentsFrom));
        }
        if (processPR) {
            workItems.addAll(getPullRequestWorkItems(webHook.get().updatedPullRequests()));
        }
        return workItems;
    }

    HostedRepository repo() {
        return remoteRepo;
    }

    HostedRepository censusRepo() {
        return censusRepo;
    }

    String censusRef() {
        return censusRef;
    }

    LabelConfiguration labelConfiguration() {
        return labelConfiguration;
    }

    Map<String, String> externalPullRequestCommands() {
        return externalPullRequestCommands;
    }

    Map<String, String> externalCommitCommands() {
        return externalCommitCommands;
    }

    Map<String, String> blockingCheckLabels() {
        return blockingCheckLabels;
    }

    public Set<String> readyLabels() {
        return readyLabels;
    }

    Set<String> twoReviewersLabels() {
        return twoReviewersLabels;
    }

    Set<String> twentyFourHoursLabels() {
        return twentyFourHoursLabels;
    }

    public Map<String, Pattern> readyComments() {
        return readyComments;
    }

    IssueProject issueProject() {
        return issueProject;
    }

    boolean useStaleReviews() {
        return useStaleReviews;
    }

    boolean acceptSimpleMerges() {
        return acceptSimpleMerges;
    }

    Pattern allowedTargetBranches() {
        return allowedTargetBranches;
    }

    Optional<Path> seedStorage() {
        return Optional.ofNullable(seedStorage);
    }

    Optional<HostedRepositoryPool> hostedRepositoryPool() {
        return seedStorage().map(path -> new HostedRepositoryPool(path));
    }

    Optional<HostedRepository> confOverrideRepository() {
        return Optional.ofNullable(confOverrideRepo);
    }

    String confOverrideName() {
        return confOverrideName;
    }

    String confOverrideRef() {
        return confOverrideRef;
    }

    Optional<URI> censusLink(Contributor contributor) {
        if (censusLink == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(censusLink.replace("{{contributor}}", contributor.username())));
    }

    public boolean enableCsr() {
        return enableCsr;
    }

    public boolean enableJep() {
        return enableJep;
    }

    Optional<HostedRepository> writeableForkOf(HostedRepository upstream) {
        return Optional.ofNullable(forks.get(upstream.name()));
    }

    public Map<String, HostedRepository> forks() {
        return forks;
    }

    public boolean reviewCleanBackport() {
        return reviewCleanBackport;
    }

    public String mlbridgeBotName() {
        return mlbridgeBotName;
    }

    public MergePullRequestReviewConfiguration reviewMerge() {
        return reviewMerge;
    }

    public boolean enableMerge() {
        return enableMerge;
    }

    public boolean jcheckMerge() {
        return jcheckMerge;
    }

    public Set<String> mergeSources() {
        return mergeSources;
    }

    public boolean enableBackport() {
        return enableBackport;
    }

    public Set<String> integrators() {
        return integrators;
    }

    public Map<String, List<PRRecord>> issuePRMap() {
        return issuePRMap;
    }

    public Approval approval() {
        return approval;
    }

    public boolean versionMismatchWarning() {
        return versionMismatchWarning;
    }

    public boolean cleanCommandEnabled() {
        return cleanCommandEnabled;
    }

    public boolean checkContributorStatusForBackportCommand() {
        return checkContributorStatusForBackportCommand;
    }

    public void addIssuePRMapping(String issueId, PRRecord prRecord) {
        issuePRMap.putIfAbsent(issueId, new LinkedList<>());
        List<PRRecord> prRecords = issuePRMap.get(issueId);
        synchronized (prRecords) {
            if (!prRecords.contains(prRecord)) {
                prRecords.add(prRecord);
            }
        }
    }

    public void removeIssuePRMapping(String issueId, PRRecord prRecord) {
        List<PRRecord> prRecords = issuePRMap.get(issueId);
        if (prRecords != null) {
            synchronized (prRecords) {
                prRecords.remove(prRecord);
            }
        }
    }

    public Map<String, Boolean> initializedPRs() {
        return initializedPRs;
    }

    @Override
    public String name() {
        return PullRequestBotFactory.NAME;
    }

    @Override
    public String toString() {
        return "PullRequestBot@" + remoteRepo.name();
    }
}
