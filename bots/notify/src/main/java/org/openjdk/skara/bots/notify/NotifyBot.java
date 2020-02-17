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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.storage.*;
import org.openjdk.skara.vcs.Tag;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

class NotifyBot implements Bot {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<Tag> tagStorageBuilder;
    private final StorageBuilder<ResolvedBranch> branchStorageBuilder;
    private final StorageBuilder<PullRequestIssues> prIssuesStorageBuilder;
    private final List<RepositoryUpdateConsumer> updaters;
    private final List<PullRequestUpdateConsumer> prUpdaters;
    private final PullRequestUpdateCache updateCache;
    private final Set<String> readyLabels;
    private final Map<String, Pattern> readyComments;

    NotifyBot(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<Tag> tagStorageBuilder,
              StorageBuilder<ResolvedBranch> branchStorageBuilder, StorageBuilder<PullRequestIssues> prIssuesStorageBuilder,
              List<RepositoryUpdateConsumer> updaters, List<PullRequestUpdateConsumer> prUpdaters,
              Set<String> readyLabels, Map<String, Pattern> readyComments) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.prIssuesStorageBuilder = prIssuesStorageBuilder;
        this.updaters = updaters;
        this.prUpdaters = prUpdaters;
        this.updateCache = new PullRequestUpdateCache();
        this.readyLabels = readyLabels;
        this.readyComments = readyComments;
    }

    static NotifyBotBuilder newBuilder() {
        return new NotifyBotBuilder();
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

    @Override
    public String toString() {
        return "JNotifyBot@" + repository.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var ret = new LinkedList<WorkItem>();

        // Pull request events
        var prs = repository.pullRequests();
        for (var pr : prs) {
            if (updateCache.needsUpdate(pr)) {
                if (!isReady(pr)) {
                    continue;
                }
                ret.add(new PullRequestWorkItem(pr, prIssuesStorageBuilder, prUpdaters, e -> updateCache.invalidate(pr)));
            }
        }

        // Repository events
        ret.add(new RepositoryWorkItem(repository, storagePath, branches, tagStorageBuilder, branchStorageBuilder, updaters));

        return ret;
    }
}
