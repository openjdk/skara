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
import org.openjdk.skara.forge.HostedRepository;

import java.time.*;
import java.util.*;
import java.util.logging.Logger;

public class TestInfoBot implements Bot {
    private final HostedRepository repo;
    private final Map<String, Instant> expirations = new HashMap<>();

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    TestInfoBot(HostedRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var prs = repo.pullRequests(ZonedDateTime.now().minus(Duration.ofDays(1)));
        var ret = new ArrayList<WorkItem>();
        for (var pr : prs) {
            if (pr.sourceRepository().isEmpty()) {
                continue;
            }
            if (expirations.containsKey(pr.id())) {
                var expiresAt = expirations.get(pr.id());
                if (expiresAt.isAfter(Instant.now())) {
                    continue;
                }
            }

            var sourceRepo = pr.sourceRepository().get();
            var checks = sourceRepo.allChecks(pr.headHash());
            var summarizedChecks = TestResults.summarize(checks);

            if (summarizedChecks.isEmpty()) {
                // No test related checks found, they may not have started yet, so we'll keep looking
                expirations.put(pr.id(), Instant.now().plus(Duration.ofMinutes(5)));
                continue;
            } else {
                expirations.put(pr.id(), Instant.now().plus(TestResults.expiresIn(checks).orElse(Duration.ofDays(1000))));
            }

            // Time to refresh test info
            ret.add(new TestInfoBotWorkItem(pr, summarizedChecks));
        }
        return ret;
    }
}
