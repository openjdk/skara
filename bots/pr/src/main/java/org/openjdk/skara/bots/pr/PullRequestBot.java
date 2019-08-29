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
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.Hash;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

class PullRequestBot implements Bot {
    private final HostedRepository remoteRepo;
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final Map<String, List<Pattern>> labelPatterns;
    private final Map<String, String> externalCommands;
    private final Map<String, String> blockingLabels;
    private final Set<String> readyLabels;
    private final Map<String, Pattern> readyComments;
    private final ConcurrentMap<Hash, Boolean> currentLabels = new ConcurrentHashMap<>();
    private final PullRequestUpdateCache updateCache;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    PullRequestBot(HostedRepository repo, HostedRepository censusRepo, String censusRef,
                   Map<String, List<Pattern>> labelPatterns, Map<String, String> externalCommands,
                   Map<String, String> blockingLabels, Set<String> readyLabels,
                   Map<String, Pattern> readyComments) {
        remoteRepo = repo;
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        this.labelPatterns = labelPatterns;
        this.externalCommands = externalCommands;
        this.blockingLabels = blockingLabels;
        this.readyLabels = readyLabels;
        this.readyComments = readyComments;
        this.updateCache = new PullRequestUpdateCache();
    }

    PullRequestBot(HostedRepository repo, HostedRepository censusRepo, String censusRef) {
        this(repo, censusRepo, censusRef, Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
    }

    private boolean isReady(PullRequest pr) {
        var labels = new HashSet<>(pr.getLabels());
        for (var readyLabel : readyLabels) {
            if (!labels.contains(readyLabel)) {
                log.fine("PR is not yet ready - missing label '" + readyLabel + "'");
                return false;
            }
        }

        var comments = pr.getComments();
        for (var readyComment : readyComments.entrySet()) {
            var commentFound = false;
            for (var comment : comments) {
                if (comment.author().userName().equals(readyComment.getKey())) {
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

    private List<WorkItem> getWorkItems(List<PullRequest> pullRequests) {
        var ret = new LinkedList<WorkItem>();

        for (var pr : pullRequests) {
            if (updateCache.needsUpdate(pr)) {
                if (!isReady(pr)) {
                    continue;
                }

                ret.add(new CheckWorkItem(pr, censusRepo, censusRef, blockingLabels, e -> updateCache.invalidate(pr)));
                ret.add(new CommandWorkItem(pr, censusRepo, censusRef, externalCommands, e -> updateCache.invalidate(pr)));
                ret.add(new LabelerWorkItem(pr, labelPatterns, currentLabels, e -> updateCache.invalidate(pr)));
            }
        }

        return ret;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return getWorkItems(remoteRepo.getPullRequests());
    }

    @Override
    public List<WorkItem> processWebHook(JSONValue body) {
        var webHook = remoteRepo.parseWebHook(body);
        if (webHook.isEmpty()) {
            return new ArrayList<>();
        }

        return getWorkItems(webHook.get().updatedPullRequests());
    }
}
