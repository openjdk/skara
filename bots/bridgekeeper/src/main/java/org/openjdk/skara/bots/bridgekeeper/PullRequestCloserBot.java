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
package org.openjdk.skara.bots.bridgekeeper;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

class PullRequestCloserBotWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository repository;
    private final PullRequest pr;
    private final Consumer<RuntimeException> errorHandler;

    PullRequestCloserBotWorkItem(HostedRepository repository, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        this.pr = pr;
        this.repository = repository;
        this.errorHandler = errorHandler;
    }

    private final String welcomeMarker = "<!-- PullrequestCloserBot welcome message -->";

    private void checkWelcomeMessage() {
        log.info("Checking welcome message of " + pr);

        var comments = pr.comments();
        var welcomePosted = comments.stream()
                                    .anyMatch(comment -> comment.body().contains(welcomeMarker));

        if (!welcomePosted) {
            var message = "Welcome to the OpenJDK organization on GitHub!\n\n" +
                    "This repository is currently a read-only git mirror of the official Mercurial " +
                    "repository (located at https://hg.openjdk.java.net/). As such, we are not " +
                    "currently accepting pull requests here. If you would like to contribute to " +
                    "the OpenJDK project, please see https://openjdk.java.net/contribute/ on how " +
                    "to proceed.\n\n" +
                    "This pull request will be automatically closed.";

            log.fine("Posting welcome message");
            pr.addComment(welcomeMarker + "\n\n" + message);
        }
        pr.setState(PullRequest.State.CLOSED);
    }


    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestCloserBotWorkItem)) {
            return true;
        }
        PullRequestCloserBotWorkItem otherItem = (PullRequestCloserBotWorkItem)other;
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!repository.name().equals(otherItem.repository.name())) {
            return true;
        }
        return false;
    }

    @Override
    public void run(Path scratchPath) {
        checkWelcomeMessage();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }

    @Override
    public String toString() {
        return "PullRequestCloserBotWorkItem@" + repository.name() + "#" + pr.id();
    }
}

public class PullRequestCloserBot implements Bot {
    private final HostedRepository remoteRepo;
    private final PullRequestUpdateCache updateCache;

    PullRequestCloserBot(HostedRepository repo) {
        this.remoteRepo = repo;
        this.updateCache = new PullRequestUpdateCache();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        List<WorkItem> ret = new LinkedList<>();

        for (var pr : remoteRepo.pullRequests()) {
            if (updateCache.needsUpdate(pr)) {
                var item = new PullRequestCloserBotWorkItem(remoteRepo, pr, e -> updateCache.invalidate(pr));
                ret.add(item);
            }
        }

        return ret;
    }
}
