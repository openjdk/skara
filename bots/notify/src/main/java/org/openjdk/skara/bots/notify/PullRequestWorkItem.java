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
import org.openjdk.skara.json.*;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.*;

public class PullRequestWorkItem implements WorkItem {
    private final PullRequest pr;
    private final StorageBuilder<PullRequestIssues> prIssuesStorageBuilder;
    private final List<PullRequestUpdateConsumer> pullRequestUpdateConsumers;
    private final Consumer<RuntimeException> errorHandler;

    PullRequestWorkItem(PullRequest pr, StorageBuilder<PullRequestIssues> prIssuesStorageBuilder, List<PullRequestUpdateConsumer> pullRequestUpdateConsumers, Consumer<RuntimeException> errorHandler) {
        this.pr = pr;
        this.prIssuesStorageBuilder = prIssuesStorageBuilder;
        this.pullRequestUpdateConsumers = pullRequestUpdateConsumers;
        this.errorHandler = errorHandler;
    }

    private Set<PullRequestIssues> loadPrIssues(String current) {
        if (current.isBlank()) {
            return Set.of();
        }
        var data = JSON.parse(current);
        return data.stream()
                   .map(JSONValue::asObject)
                   .map(obj -> new PullRequestIssues(obj.get("pr").asString(), obj.get("issues").stream()
                                                                                  .map(JSONValue::asString)
                                                                                  .collect(Collectors.toSet())))
                   .collect(Collectors.toSet());
    }

    private String serializePrIssues(Collection<PullRequestIssues> added, Set<PullRequestIssues> existing) {
        var addedPrs = added.stream()
                            .map(PullRequestIssues::prId)
                            .collect(Collectors.toSet());
        var nonReplaced = existing.stream()
                                  .filter(item -> !addedPrs.contains(item.prId()))
                                  .collect(Collectors.toSet());

        var entries = Stream.concat(nonReplaced.stream(),
                                    added.stream())
                            .sorted(Comparator.comparing(PullRequestIssues::prId))
                            .map(pr -> JSON.object().put("pr", pr.prId()).put("issues", new JSONArray(
                                    pr.issueIds().stream()
                                      .map(JSON::of)
                                      .collect(Collectors.toList()))))
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

    @Override
    public void run(Path scratchPath) {
        var historyPath = scratchPath.resolve("notify").resolve("history");
        var storage = prIssuesStorageBuilder
                .serializer(this::serializePrIssues)
                .deserializer(this::loadPrIssues)
                .materialize(historyPath);

        var issues = parseIssues();
        var prIssues = new PullRequestIssues(pr, issues);
        var current = storage.current();
        if (current.contains(prIssues)) {
            // Already up to date
            return;
        }

        // Search for an existing
        var oldPrIssues = current.stream()
                .filter(p -> p.prId().equals(prIssues.prId()))
                .findAny();
        if (oldPrIssues.isPresent()) {
            var oldIssues = oldPrIssues.get().issueIds();
            oldIssues.stream()
                     .filter(issue -> !issues.contains(issue))
                     .forEach(this::notifyListenersRemoved);
            issues.stream()
                  .filter(issue -> !oldIssues.contains(issue))
                  .forEach(this::notifyListenersAdded);
        } else {
            issues.forEach(this::notifyListenersAdded);
        }

        storage.put(prIssues);
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
