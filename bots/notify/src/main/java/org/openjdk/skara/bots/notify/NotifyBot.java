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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.storage.StorageBuilder;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class NotifyBot implements Bot, Emitter {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");
    private final HostedRepository repository;
    private final Path storagePath;
    private final Pattern branches;
    private final StorageBuilder<UpdatedTag> tagStorageBuilder;
    private final StorageBuilder<UpdatedBranch> branchStorageBuilder;
    private final StorageBuilder<PullRequestState> prStateStorageBuilder;
    private final List<RepositoryListener> repoListeners = new ArrayList<>();
    private final List<PullRequestListener> prListeners = new ArrayList<>();
    private final Map<String, Pattern> readyComments;
    private final String integratorId;
    private final PullRequestPoller poller;
    private Boolean firstTimeCall = true;

    NotifyBot(HostedRepository repository, Path storagePath, Pattern branches, StorageBuilder<UpdatedTag> tagStorageBuilder,
              StorageBuilder<UpdatedBranch> branchStorageBuilder, StorageBuilder<PullRequestState> prStateStorageBuilder,
              Map<String, Pattern> readyComments, String integratorId) {
        this.repository = repository;
        this.storagePath = storagePath;
        this.branches = branches;
        this.tagStorageBuilder = tagStorageBuilder;
        this.branchStorageBuilder = branchStorageBuilder;
        this.prStateStorageBuilder = prStateStorageBuilder;
        this.readyComments = readyComments;
        this.integratorId = integratorId;
        this.poller = new PullRequestPoller(repository, true);
    }

    public static NotifyBotBuilder newBuilder() {
        return new NotifyBotBuilder();
    }

    @Override
    public void registerPullRequestListener(PullRequestListener listener) {
        prListeners.add(listener);
    }

    @Override
    public void registerRepositoryListener(RepositoryListener listener) {
        repoListeners.add(listener);
    }

    @Override
    public String toString() {
        return "NotifyBot@" + repository.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var ret = new ArrayList<WorkItem>();

        if (firstTimeCall) {
            prListeners.forEach(listener -> listener.initialize(repository));
            firstTimeCall = false;
        }

        if (!prListeners.isEmpty()) {
            // Pull request events
            List<PullRequest> prs = poller.updatedPullRequests();
            for (var pr : prs) {
                ret.add(new PullRequestWorkItem(pr,
                        prStateStorageBuilder,
                        prListeners,
                        e -> poller.retryPullRequest(pr),
                        integratorId,
                        readyComments));
            }
            poller.lastBatchHandled();
        }

        // Repository events
        if (!repoListeners.isEmpty()) {
            ret.add(new RepositoryWorkItem(repository, storagePath, branches, tagStorageBuilder, branchStorageBuilder, repoListeners));
        }

        return ret;
    }

    @Override
    public String name() {
        return NotifyBotFactory.NAME;
    }

    public Pattern getBranches() {
        return branches;
    }

    public Map<String, Pattern> getReadyComments() {
        return readyComments;
    }
}
