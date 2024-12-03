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
package org.openjdk.skara.bots.notify;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.bots.notify.prbranch.PullRequestBranchNotifier;
import org.openjdk.skara.forge.PreIntegrations;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.json.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.stream.*;

import static org.openjdk.skara.bots.common.PullRequestConstants.*;

public class PullRequestWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");
    private final PullRequest pr;
    private final StorageBuilder<PullRequestState> prStateStorageBuilder;
    private final List<PullRequestListener> listeners;
    private final Consumer<RuntimeException> errorHandler;
    private final String integratorId;
    private final Map<String, Pattern> readyComments;

    PullRequestWorkItem(PullRequest pr, StorageBuilder<PullRequestState> prStateStorageBuilder,
            List<PullRequestListener> listeners, Consumer<RuntimeException> errorHandler,
            String integratorId, Map<String, Pattern> readyComments) {
        this.pr = pr;
        this.prStateStorageBuilder = prStateStorageBuilder;
        this.listeners = listeners;
        this.errorHandler = errorHandler;
        this.integratorId = integratorId;
        this.readyComments = readyComments;
    }

    private Hash resultingCommitHash() {
        return integratorId != null ? pr.findIntegratedCommitHash(List.of(integratorId)).orElse(null) : null;
    }

    private Set<PullRequestState> deserializePrState(String current) {
        if (current.isBlank()) {
            return Set.of();
        }
        var data = JSON.parse(current);
        return data.stream()
                   .map(JSONValue::asObject)
                   .map(obj -> {
                       var id = obj.get("pr").asString();
                       var issues = obj.get("issues").stream()
                                                     .map(JSONValue::asString)
                                                     .collect(Collectors.toSet());

                       // Storage might be missing commit information
                       if (!obj.contains("commit")) {
                           obj.put("commit", Hash.zero().hex());
                       }
                       if (!obj.contains("head")) {
                           obj.put("head", Hash.zero().hex());
                       }
                       if (!obj.contains("state")) {
                           obj.put("state", JSON.of());
                       }

                       var commit = obj.get("commit").isNull() ?
                               null : new Hash(obj.get("commit").asString());
                       var state = obj.get("state").isNull() ?
                               null : org.openjdk.skara.issuetracker.Issue.State.valueOf(obj.get("state").asString());
                       var targetBranch = obj.get("targetBranch") == null ?
                               null : obj.get("targetBranch").asString();

                       return new PullRequestState(id, issues, commit, new Hash(obj.get("head").asString()), state, targetBranch);
                   })
                .collect(Collectors.toSet());
    }

    private String serializePrState(Collection<PullRequestState> added, Set<PullRequestState> existing) {
        var addedPrs = added.stream()
                            .map(PullRequestState::prId)
                            .collect(Collectors.toSet());
        var nonReplaced = existing.stream()
                                  .filter(item -> !addedPrs.contains(item.prId()))
                                  .collect(Collectors.toSet());

        var entries = Stream.concat(nonReplaced.stream(),
                                    added.stream())
                            .sorted(Comparator.comparing(PullRequestState::prId))
                            .map(pr -> {
                                var issues = new JSONArray(pr.issueIds()
                                                             .stream()
                                                             .map(JSON::of)
                                                             .collect(Collectors.toList()));
                                var ret = JSON.object().put("pr", pr.prId())
                                              .put("issues",issues);
                                if (pr.commitId().isPresent()) {
                                    if (!pr.commitId().get().equals(Hash.zero())) {
                                        ret.put("commit", JSON.of(pr.commitId().get().hex()));
                                    }
                                } else {
                                    ret.putNull("commit");
                                }
                                ret.put("head", JSON.of(pr.head().hex()));
                                if (pr.state() != null) {
                                    ret.put("state", JSON.of(pr.state().toString()));
                                } else {
                                    ret.putNull("state");
                                }
                                if (pr.targetBranch() != null) {
                                    ret.put("targetBranch", JSON.of(pr.targetBranch()));
                                }
                                return ret;
                            })
                            .map(JSONObject::toString)
                            .collect(Collectors.toList());
        return "[\n" + String.join(",\n", entries) + "\n]";
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem otherItem)) {
            return true;
        }
        if (!pr.isSame(otherItem.pr)) {
            return true;
        }
        return false;
    }

    private void notifyNewIssue(String issueId, Path scratchPath) {
        listeners.forEach(c -> c.onNewIssue(pr, scratchPath.resolve(c.name()), new Issue(issueId, "")));
    }

    private void notifyRemovedIssue(String issueId, Path scratchPath) {
        listeners.forEach(c -> c.onRemovedIssue(pr, scratchPath.resolve(c.name()), new Issue(issueId, "")));
    }

    private void notifyNewPr(PullRequest pr, Path scratchPath) {
        listeners.forEach(c -> c.onNewPullRequest(pr, scratchPath.resolve(c.name())));
    }

    private void notifyIntegratedPr(PullRequest pr, Hash hash, Path scratchPath) {
        listeners.forEach(c -> c.onIntegratedPullRequest(pr, scratchPath.resolve(c.name()), hash));
    }

    private void notifyHeadChange(PullRequest pr, Hash oldHead, Path scratchPath) {
        listeners.forEach(c -> c.onHeadChange(pr, scratchPath.resolve(c.name()), oldHead));
    }

    private void notifyStateChange(org.openjdk.skara.issuetracker.Issue.State oldState, Path scratchPath) {
        listeners.forEach(c -> c.onStateChange(pr, scratchPath.resolve(c.name()), oldState));
    }

    private void notifyTargetBranchChange(String issueId, Path scratchPath) {
        listeners.forEach(c -> c.onTargetBranchChange(pr, scratchPath.resolve(c.name()), new Issue(issueId, "")));
    }

    private boolean isOfInterest(PullRequest pr) {
        var labels = new HashSet<>(pr.labelNames());
        if (!(labels.contains("rfr") || labels.contains("integrated"))) {
            // If the PullRequestBranchNotifier is configured, check for the existence of
            // a pre-integration branch as that may need to be removed by the listener
            // even if none of the labels match.
            var prBranchListenerExists = listeners.stream()
                    .anyMatch(l -> l instanceof PullRequestBranchNotifier);
            var branchExists = prBranchListenerExists && pr.repository().branchHash(PreIntegrations.preIntegrateBranch(pr)).isPresent();
            if (!branchExists) {
                log.fine("PR is not yet ready - needs either 'rfr' or 'integrated' label, or a pre-integration branch present");
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

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        if (!isOfInterest(pr)) {
            return List.of();
        }
        if (pr.isOpen() && pr.body().contains(TEMPORARY_ISSUE_FAILURE_MARKER)) {
            log.warning("Found temporary issue failure, the notifiers will be stopped until the temporary issue failure resolved.");
            return List.of();
        }
        var historyPath = scratchPath.resolve("notify").resolve("history").resolve("pr");
        var listenerScratchPath = scratchPath.resolve("notify").resolve("listener");
        var storage = prStateStorageBuilder
                .serializer(this::serializePrState)
                .deserializer(this::deserializePrState)
                .materialize(historyPath);

        var issues = BotUtils.parseIssues(pr.body());
        var commit = resultingCommitHash();
        var state = new PullRequestState(pr, issues, commit, pr.headHash(), pr.state());
        var stored = storage.current();
        if (stored.contains(state)) {
            // Already up to date
            return List.of();
        }

        // Search for an existing
        var storedState = stored.stream()
                .filter(ss -> ss.prId().equals(state.prId()))
                .findAny();
        // The stored entry could be old and be missing commit information - if so, upgrade it
        if (storedState.isPresent()) {
            if (storedState.get().commitId().equals(Optional.of(Hash.zero()))) {
                var hash = resultingCommitHash();
                storedState = Optional.of(new PullRequestState(pr, storedState.get().issueIds(), hash, pr.headHash(), pr.state()));
                storage.put(storedState.get());
            }
            if (storedState.get().head().equals(Hash.zero())) {
                storedState = Optional.of(new PullRequestState(pr, storedState.get().issueIds(), storedState.get().commitId().orElse(null), pr.headHash(), pr.state()));
                storage.put(storedState.get());
            }
            if (storedState.get().state() == null) {
                storedState = Optional.of(new PullRequestState(pr, storedState.get().issueIds(), storedState.get().commitId().orElse(null), pr.headHash(), pr.state()));
                storage.put(storedState.get());
            }
        }

        if (storedState.isPresent()) {
            var storedIssues = storedState.get().issueIds();
            storedIssues.stream()
                        .filter(issue -> !issues.contains(issue))
                        .forEach(issue -> notifyRemovedIssue(issue, listenerScratchPath));
            issues.stream()
                  .filter(issue -> !storedIssues.contains(issue))
                  .forEach(issue -> notifyNewIssue(issue, listenerScratchPath));

            if (!storedState.get().head().equals(state.head())) {
                notifyHeadChange(pr, storedState.get().head(), listenerScratchPath);
            }
            var storedCommit = storedState.get().commitId();
            if (storedCommit.isEmpty() && state.commitId().isPresent()) {
                notifyIntegratedPr(pr, state.commitId().get(), listenerScratchPath);
            }
            if (!storedState.get().state().equals(state.state())) {
                notifyStateChange(storedState.get().state(), scratchPath);
            }
            var storedTargetBranch = storedState.get().targetBranch();
            if (state.targetBranch() != null && !state.targetBranch().equals(storedTargetBranch)) {
                storedIssues.stream()
                        .filter(issues::contains)
                        .forEach(issue -> notifyTargetBranchChange(issue, listenerScratchPath));
            }
        } else {
            notifyNewPr(pr, listenerScratchPath);
            issues.forEach(issue -> notifyNewIssue(issue, listenerScratchPath));
            if (state.commitId().isPresent()) {
                notifyIntegratedPr(pr, state.commitId().get(), listenerScratchPath);
            }
        }

        storage.put(state);
        // This is mixing timestamps from the forge and the local host, which may not produce
        // very accurate latencies, but it's the best we can do for this bot.
        var latency = Duration.between(pr.updatedAt(), ZonedDateTime.now());
        log.log(Level.INFO, "Time from PR updated to notifications done " + latency, latency);
        return List.of();
    }

    @Override
    public String toString() {
        return "Notify.PR@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }

    @Override
    public String botName() {
        return NotifyBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "pr";
    }
}
