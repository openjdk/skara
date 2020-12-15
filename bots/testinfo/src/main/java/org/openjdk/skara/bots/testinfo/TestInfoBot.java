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
import java.util.logging.Logger;

public class TestInfoBot implements Bot {
    private final HostedRepository repo;
    private final Map<String, Instant> expirations = new HashMap<>();

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    TestInfoBot(HostedRepository repo) {
        this.repo = repo;
    }

    private String pullRequestToKey(PullRequest pr) {
        return pr.id() + "#" + pr.headHash().hex();
    }

    private Check testingNotConfiguredNotice(PullRequest pr) {
        var sourceRepoUrl = pr.sourceRepository().orElseThrow().nonTransformedWebUrl().toString();
        if (sourceRepoUrl.toLowerCase().contains("github.com")) {
            sourceRepoUrl += "/actions";
        }

        return CheckBuilder.create("Pre-submit test status", pr.headHash())
                           .skipped()
                           .title("Testing is not configured")
                           .summary("In order to run pre-submit tests, the [source repository](" +
                                            sourceRepoUrl + ")" +
                                            " must be properly configured to allow test execution. " +
                                            "See https://wiki.openjdk.java.net/display/SKARA/Testing for more information on how to configure this.")
                           .build();
    }

    private Check testingEnabledNotice(PullRequest pr) {
        return CheckBuilder.create("Pre-submit test status", pr.headHash())
                           .complete(true)
                           .title("Tests are now enabled")
                           .summary("Pre-submit tests have been now been enabled for the source repository")
                           .build();
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

            var sourceRepo = pr.sourceRepository().get();
            var checks = sourceRepo.allChecks(pr.headHash());
            var noticeCheck = checks.stream()
                                    .filter(check -> check.name().equals("Pre-submit test status"))
                                    .findAny();

            if (sourceRepo.workflowStatus() == WorkflowStatus.NOT_CONFIGURED) {
                if (noticeCheck.isEmpty()) {
                    ret.add(new TestInfoBotWorkItem(pr, List.of(testingNotConfiguredNotice(pr))));
                }
            } else if (sourceRepo.workflowStatus() == WorkflowStatus.DISABLED) {
                // Explicitly disabled - could possibly post a notice
            } else {
                var summarizedChecks = TestResults.summarize(checks);
                if (summarizedChecks.isEmpty()) {
                    // No test related checks found, they may not have started yet, so we'll keep looking
                    expirations.put(expirationKey, Instant.now().plus(Duration.ofMinutes(2)));
                    continue;
                } else {
                    expirations.put(expirationKey, Instant.now().plus(TestResults.expiresIn(checks).orElse(Duration.ofMinutes(30))));
                }

                if (noticeCheck.isPresent()) {
                    // If a disabled notice has been posted earlier, we can't delete it - just mark it completed
                    summarizedChecks = new ArrayList<>(summarizedChecks);
                    summarizedChecks.add(testingEnabledNotice(pr));
                }

                // Time to refresh test info
                ret.add(new TestInfoBotWorkItem(pr, summarizedChecks));
            }
        }
        return ret;
    }
}
