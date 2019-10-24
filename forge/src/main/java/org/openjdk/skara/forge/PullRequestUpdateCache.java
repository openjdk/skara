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
package org.openjdk.skara.forge;

import org.openjdk.skara.forge.gitlab.GitLabMergeRequest;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;

public class PullRequestUpdateCache {
    private final Map<HostedRepository, String> repositoryIds = new HashMap<>();
    private final Map<String, ZonedDateTime> lastUpdates = new HashMap<>();

    private final Logger log = Logger.getLogger("org.openjdk.skara.host");

    private String getUniqueId(PullRequest pr) {
        var repo = pr.repository();
        if (!repositoryIds.containsKey(repo)) {
            repositoryIds.put(repo, Integer.toString(repositoryIds.size()));
        }
        return repositoryIds.get(repo) + ";" + pr.id();
    }

    public synchronized boolean needsUpdate(PullRequest pr) {
        // GitLab CE does not update this field on events such as adding an award
        if (pr instanceof GitLabMergeRequest) {
            return true;
        }

        var uniqueId = getUniqueId(pr);
        var update = pr.updatedAt();

        if (!lastUpdates.containsKey(uniqueId)) {
            lastUpdates.put(uniqueId, update);
            return true;
        }
        var lastUpdate = lastUpdates.get(uniqueId);
        if (lastUpdate.isBefore(update)) {
            lastUpdates.put(uniqueId, update);
            return true;
        }
        log.info("Skipping update for " + pr.repository().name() + "#" + pr.id());
        return false;
    }

    public synchronized void invalidate(PullRequest pr) {
        var uniqueId = getUniqueId(pr);
        lastUpdates.remove(uniqueId);
    }
}
