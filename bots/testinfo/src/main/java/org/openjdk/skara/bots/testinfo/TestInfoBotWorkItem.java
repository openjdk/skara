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

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class TestInfoBotWorkItem implements WorkItem {
    private final PullRequest pr;
    private final Consumer<Duration> expiresIn;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    TestInfoBotWorkItem(PullRequest pr, Consumer<Duration> expiresIn) {
        this.pr = pr;
        this.expiresIn = expiresIn;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof TestInfoBotWorkItem)) {
            return true;
        }
        var o = (TestInfoBotWorkItem) other;
        return !o.pr.webUrl().equals(pr.webUrl());
    }

    @Override
    public String toString() {
        return "TestInfoBotWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    private Check testingNotConfiguredNotice(PullRequest pr) {
        var sourceRepoUrl = pr.sourceRepository().orElseThrow().nonTransformedWebUrl().toString();
        if (pr.sourceRepository().orElseThrow().forge().name().equals("GitHub")) {
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
    public Collection<WorkItem> run(Path scratch) {
        var sourceRepo = pr.sourceRepository().get();
        var sourceChecks = sourceRepo.allChecks(pr.headHash());

        var targetChecks = pr.checks(pr.headHash());
        var noticeCheck = targetChecks.get("Pre-submit test status");

        if (sourceRepo.workflowStatus() == WorkflowStatus.NOT_CONFIGURED) {
            if (noticeCheck == null) {
                pr.createCheck(testingNotConfiguredNotice(pr));
                expiresIn.accept(Duration.ofMinutes(2));
            }
        } else if (sourceRepo.workflowStatus() == WorkflowStatus.DISABLED) {
            // Explicitly disabled - could possibly post a notice
        } else {
            var summarizedChecks = TestResults.summarize(sourceChecks);
            if (summarizedChecks.isEmpty()) {
                // No test related checks found, they may not have started yet, so we'll keep looking
                log.fine("No checks found to summarize - waiting");
                expiresIn.accept(Duration.ofMinutes(2));
            } else {
                expiresIn.accept(TestResults.expiresIn(sourceChecks).orElse(Duration.ofMinutes(30)));
            }

            if (noticeCheck != null && noticeCheck.status() == CheckStatus.SKIPPED) {
                // If a disabled notice has been posted earlier, we can't delete it - just mark it completed
                pr.updateCheck(testingEnabledNotice(pr));
            }

            for (var check : summarizedChecks) {
                if (!targetChecks.containsKey(check.name())) {
                    pr.createCheck(check);
                    targetChecks.put(check.name(), check);
                }
                var current = targetChecks.get(check.name());
                if ((current.status() != check.status()) ||
                        (!current.summary().equals(check.summary())) ||
                        (!current.title().equals(check.summary()))) {
                    pr.updateCheck(check);
                } else {
                    log.fine("Not updating unchanged check: " + check.name());
                }
            }
        }

        return List.of();
    }
}
