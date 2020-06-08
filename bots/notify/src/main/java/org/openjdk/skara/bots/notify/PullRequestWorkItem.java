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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.json.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.*;

public class PullRequestWorkItem implements WorkItem {
    private final PullRequest pr;
    private final StorageBuilder<PullRequestState> prStateStorageBuilder;
    private final List<PullRequestUpdateConsumer> pullRequestUpdateConsumers;
    private final Consumer<RuntimeException> errorHandler;
    private final String integratorId;

    PullRequestWorkItem(PullRequest pr, StorageBuilder<PullRequestState> prStateStorageBuilder, List<PullRequestUpdateConsumer> pullRequestUpdateConsumers, Consumer<RuntimeException> errorHandler, String integratorId) {
        this.pr = pr;
        this.prStateStorageBuilder = prStateStorageBuilder;
        this.pullRequestUpdateConsumers = pullRequestUpdateConsumers;
        this.errorHandler = errorHandler;
        this.integratorId = integratorId;
    }

    private Hash resultingCommitHashFor(PullRequest pr) {
       if (pr.labels().contains("integrated")) {
           for (var comment : pr.comments()) {
               if (comment.author().id().equals(integratorId)) {
                   for (var line : comment.body().split("\n")) {
                       if (line.startsWith("Pushed as commit")) {
                           var parts = line.split(" ");
                           var hash = parts[parts.length - 1].replace(".", "");
                           return new Hash(hash);
                       }
                   }
               }
           }
       }
       return null;
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
                           var prId = id.split(":")[1];
                           var currentPR = pr.repository().pullRequest(prId);
                           var hash = resultingCommitHashFor(currentPR);
                           if (hash == null) {
                               obj.putNull("commit");
                           } else {
                               obj.put("commit", hash.hex());
                           }
                       }

                       var commit = obj.get("commit").isNull() ?
                           null : new Hash(obj.get("commit").asString());

                       return new PullRequestState(id, issues, commit);
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
                                var commit = pr.commitId().isPresent()?
                                    JSON.of(pr.commitId().get().hex()) : JSON.of();
                                return JSON.object().put("pr", pr.prId())
                                                    .put("issues",issues)
                                                    .put("commit", commit);
                            })
                            .map(JSONObject::toString)
                            .collect(Collectors.toList());
        return "[\n" + String.join(",\n", entries) + "\n]";
    }

    private final Pattern issuesBlockPattern = Pattern.compile("\\n\\n###? Issues?((?:\\n(?: \\* )?\\[.*)+)", Pattern.MULTILINE);
    private final Pattern issuePattern = Pattern.compile("^(?: \\* )?\\[(\\S+)]\\(.*\\): .*$", Pattern.MULTILINE);

    private Set<String> parseIssues() {
        var issuesBlockMatcher = issuesBlockPattern.matcher(pr.body());
        if (!issuesBlockMatcher.find()) {
            return Set.of();
        }
        var issueMatcher = issuePattern.matcher(issuesBlockMatcher.group(1));
        return issueMatcher.results()
                           .map(mo -> mo.group(1))
                           .collect(Collectors.toSet());
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem)) {
            return true;
        }
        PullRequestWorkItem otherItem = (PullRequestWorkItem)other;
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!pr.repository().name().equals(otherItem.pr.repository().name())) {
            return true;
        }
        return false;
    }

    private void notifyListenersAdded(String issueId) {
        pullRequestUpdateConsumers.forEach(c -> c.handleNewIssue(pr, new Issue(issueId, "")));
    }

    private void notifyListenersRemoved(String issueId) {
        pullRequestUpdateConsumers.forEach(c -> c.handleRemovedIssue(pr, new Issue(issueId, "")));
    }

    private void notifyNewPr(PullRequest pr) {
        pullRequestUpdateConsumers.forEach(c -> c.handleNewPullRequest(pr));
    }

    private void notifyIntegratedPr(PullRequest pr, Hash hash) {
        pullRequestUpdateConsumers.forEach(c -> c.handleIntegratedPullRequest(pr, hash));
    }

    @Override
    public void run(Path scratchPath) {
        var historyPath = scratchPath.resolve("notify").resolve("history");
        var storage = prStateStorageBuilder
                .serializer(this::serializePrState)
                .deserializer(this::deserializePrState)
                .materialize(historyPath);

        var issues = parseIssues();
        var commit = resultingCommitHashFor(pr);
        var state = new PullRequestState(pr, issues, commit);
        var stored = storage.current();
        if (stored.contains(state)) {
            // Already up to date
            return;
        }

        // Search for an existing
        var storedState = stored.stream()
                .filter(ss -> ss.prId().equals(state.prId()))
                .findAny();
        if (storedState.isPresent()) {
            var storedIssues = storedState.get().issueIds();
            storedIssues.stream()
                        .filter(issue -> !issues.contains(issue))
                        .forEach(this::notifyListenersRemoved);
            issues.stream()
                  .filter(issue -> !storedIssues.contains(issue))
                  .forEach(this::notifyListenersAdded);

            var storedCommit = storedState.get().commitId();
            if (!storedCommit.isPresent() && state.commitId().isPresent()) {
                notifyIntegratedPr(pr, state.commitId().get());
            }
        } else {
            notifyNewPr(pr);
            issues.forEach(this::notifyListenersAdded);
            if (state.commitId().isPresent()) {
                notifyIntegratedPr(pr, state.commitId().get());
            }
        }

        storage.put(state);
    }

    @Override
    public String toString() {
        return "Notify.PR@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }
}
