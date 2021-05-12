/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.bots.testinfo;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class TestInfoBot implements Bot {
    private final HostedRepository repo;
    private final Map<String, Instant> expirations = new ConcurrentHashMap<>();

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    TestInfoBot(HostedRepository repo) {
        this.repo = repo;
    }

    private String pullRequestToKey(PullRequest pr) {
        return pr.id() + "#" + pr.headHash().hex();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var prs = repo.pullRequests(ZonedDateTime.now().minus(Duration.ofDays(1)));
        var ret = new ArrayList<WorkItem>();
        for (var pr : prs) {
            if (pr.sourceRepository().isEmpty()) {
                continue;
            }

            var expirationKey = pullRequestToKey(pr);
            if (expirations.containsKey(expirationKey)) {
                var expiresAt = expirations.get(expirationKey);
                if (expiresAt.isAfter(Instant.now())) {
                    continue;
                }
            }

            ret.add(new TestInfoBotWorkItem(pr, expiresIn -> expirations.put(expirationKey, Instant.now().plus(expiresIn))));
        }
        return ret;
    }

    @Override
    public String name() {
        return TestInfoBotFactory.NAME;
    }
}
