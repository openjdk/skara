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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class PullRequestBot implements Bot {
    private final HostedRepository remoteRepo;
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final LabelConfiguration labelConfiguration;
    private final Map<String, String> externalCommands;
    private final Map<String, String> blockingCheckLabels;
    private final Set<String> readyLabels;
    private final Set<String> twoReviewersLabels;
    private final Set<String> twentyFourHoursLabels;
    private final Map<String, Pattern> readyComments;
    private final IssueProject issueProject;
    private final boolean ignoreStaleReviews;
    private final Pattern allowedTargetBranches;
    private final Path seedStorage;
    private final HostedRepository confOverrideRepo;
    private final String confOverrideName;
    private final String confOverrideRef;
    private final String censusLink;
    private final Set<String> commitCommandUsers;
    private final Set<String> autoLabelled;
    private final ConcurrentHashMap<String, Instant> scheduledRechecks;
    private final PullRequestUpdateCache updateCache;
    private final Map<String, HostedRepository> forks;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    private Instant lastFullUpdate;
    private final Set<String> processedCommitComments;

    PullRequestBot(HostedRepository repo, HostedRepository censusRepo, String censusRef,
                   LabelConfiguration labelConfiguration, Map<String, String> externalCommands,
                   Map<String, String> blockingCheckLabels, Set<String> readyLabels,
                   Set<String> twoReviewersLabels, Set<String> twentyFourHoursLabels,
                   Map<String, Pattern> readyComments, IssueProject issueProject,
                   boolean ignoreStaleReviews, Pattern allowedTargetBranches,
                   Path seedStorage, HostedRepository confOverrideRepo, String confOverrideName,
                   String confOverrideRef, String censusLink, List<HostUser> commitCommandUsers,
                   Map<String, HostedRepository> forks) {
        remoteRepo = repo;
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        this.labelConfiguration = labelConfiguration;
        this.externalCommands = externalCommands;
        this.blockingCheckLabels = blockingCheckLabels;
        this.readyLabels = readyLabels;
        this.twoReviewersLabels = twoReviewersLabels;
        this.twentyFourHoursLabels = twentyFourHoursLabels;
        this.issueProject = issueProject;
        this.readyComments = readyComments;
        this.ignoreStaleReviews = ignoreStaleReviews;
        this.allowedTargetBranches = allowedTargetBranches;
        this.seedStorage = seedStorage;
        this.confOverrideRepo = confOverrideRepo;
        this.confOverrideName = confOverrideName;
        this.confOverrideRef = confOverrideRef;
        this.censusLink = censusLink;

        this.commitCommandUsers = commitCommandUsers.stream()
                                                    .map(HostUser::id)
                                                    .collect(Collectors.toSet());
        this.forks = forks;

        autoLabelled = new HashSet<>();
        scheduledRechecks = new ConcurrentHashMap<>();
        updateCache = new PullRequestUpdateCache();

        // Only check recently updated when starting up to avoid congestion
        lastFullUpdate = Instant.now();
        processedCommitComments = new HashSet<>();
    }

    static PullRequestBotBuilder newBuilder() {
        return new PullRequestBotBuilder();
    }

    private boolean isReady(PullRequest pr) {
        var labels = new HashSet<>(pr.labels());
        for (var readyLabel : readyLabels) {
            if (!labels.contains(readyLabel)) {
                log.fine("PR is not yet ready - missing label '" + readyLabel + "'");
                return false;
            }
        }

        var comments = pr.comments();
        for (var readyComment : readyComments.entrySet()) {
            var commentFound = false;
            for (var comment : comments) {
                if (comment.author().username().equals(readyComment.getKey())) {
                    var matcher = readyComment.getValue().matcher(comment.body());
                    if (matcher.find()) {
                        commentFound = true;
                        break;
                    }
                }
            }
            if (!commentFound) {
                log.fine("PR is not yet ready - missing ready comment from '" + readyComment.getKey() +
                                 "containing '" + readyComment.getValue().pattern() + "'");
                return false;
            }
        }
        return true;
    }

    private boolean checkHasExpired(PullRequest pr) {
        var expiresAt = scheduledRechecks.get(pr.webUrl().toString());
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            log.info("Check metadata has expired (expired at: " + expiresAt + ") for PR #" + pr.id());
            scheduledRechecks.remove(pr.webUrl().toString());
            return true;
        }
        return false;
    }

    void scheduleRecheckAt(PullRequest pr, Instant expiresAt) {
        log.info("Setting check metadata expiration to: " + expiresAt + " for PR #" + pr.id());
        scheduledRechecks.put(pr.webUrl().toString(), expiresAt);
    }

    private List<WorkItem> getWorkItems(List<PullRequest> pullRequests, List<CommitComment> commitComments) {
        var ret = new LinkedList<WorkItem>();

        for (var pr : pullRequests) {
            if (updateCache.needsUpdate(pr) || checkHasExpired(pr)) {
                if (!isReady(pr)) {
                    continue;
                }
                if (pr.state() == Issue.State.OPEN) {
                    ret.add(new CheckWorkItem(this, pr, e -> updateCache.invalidate(pr)));
                } else {
                    // Closed PR's do not need to be checked
                    ret.add(new CommandWorkItem(this, pr, e -> updateCache.invalidate(pr)));
                }
            }
        }

        for (var commitComment : commitComments) {
            ret.add(new CommitCommandWorkItem(this, commitComment));
        }

        return ret;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        List<PullRequest> prs;
        List<CommitComment> commitComments = List.of();

        if (lastFullUpdate == null || lastFullUpdate.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) {
            lastFullUpdate = Instant.now();
            log.info("Fetching all open pull requests");
            prs = remoteRepo.pullRequests();
        } else {
            log.info("Fetching recently updated pull requests (open and closed)");
            prs = remoteRepo.pullRequests(ZonedDateTime.now().minus(Duration.ofDays(1)));
        }

        if (!commitCommandUsers.isEmpty()) {
            commitComments = remoteRepo.recentCommitComments().stream()
                                       .filter(cc -> !processedCommitComments.contains(cc.id()))
                                       .filter(cc -> commitCommandUsers.contains(cc.author().id()))
                                       .collect(Collectors.toList());
            if (!commitComments.isEmpty()) {
                processedCommitComments.addAll(commitComments.stream()
                                                             .map(Comment::id)
                                                             .collect(Collectors.toList()));
            }
        }

        return getWorkItems(prs, commitComments);
    }

    @Override
    public List<WorkItem> processWebHook(JSONValue body) {
        var webHook = remoteRepo.parseWebHook(body);
        if (webHook.isEmpty()) {
            return new ArrayList<>();
        }

        return getWorkItems(webHook.get().updatedPullRequests(), List.of());
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

    Map<String, String> externalCommands() {
        return externalCommands;
    }

    Map<String, String> blockingCheckLabels() {
        return blockingCheckLabels;
    }

    Set<String> twoReviewersLabels() {
        return twoReviewersLabels;
    }

    Set<String> twentyFourHoursLabels() {
        return twentyFourHoursLabels;
    }

    IssueProject issueProject() {
        return issueProject;
    }

    boolean ignoreStaleReviews() {
        return ignoreStaleReviews;
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

    HostedRepository writeableForkOf(HostedRepository upstream) {
        var fork = forks.get(upstream.name());
        if (fork == null) {
            throw new IllegalArgumentException("No writeable fork for " + upstream.name());
        }
        return fork;
    }

    public boolean isAutoLabelled(PullRequest pr) {
        synchronized (autoLabelled) {
            return autoLabelled.contains(pr.id());
        }
    }

    public void setAutoLabelled(PullRequest pr) {
        synchronized (autoLabelled) {
            autoLabelled.add(pr.id());
        }
    }
}
