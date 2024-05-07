/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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
    // This is a callback to the bot telling it that this PR needs a recheck after the
    // specified duration. If this isn't called, then the PR will only be rechecked if
    // it is updated by someone else.
    private final Consumer<Duration> retry;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    TestInfoBotWorkItem(PullRequest pr, Consumer<Duration> retry) {
        this.pr = pr;
        this.retry = retry;
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
                                            "See https://wiki.openjdk.org/display/SKARA/Testing for more information on how to configure this.")
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
        Optional<HostedRepository> optionalSourceRepository = pr.sourceRepository();
        if (optionalSourceRepository.isEmpty()) {
            return List.of();
        }
        var sourceRepo = optionalSourceRepository.get();
        var sourceChecks = sourceRepo.allChecks(pr.headHash());

        var targetChecks = pr.checks(pr.headHash());
        var noticeCheck = targetChecks.get("Pre-submit test status");

        if (sourceRepo.workflowStatus() == WorkflowStatus.NOT_CONFIGURED) {
            if (noticeCheck == null) {
                pr.createCheck(testingNotConfiguredNotice(pr));
            }
            // It's pretty unlikely that a user suddenly enables workflows. We can
            // be pretty lax with automatically discovering this. Touching the PR
            // will always trigger an immediate recheck anyway.
            if (pr.isOpen()) {
                retry.accept(Duration.ofMinutes(30));
            }
        } else if (sourceRepo.workflowStatus() == WorkflowStatus.DISABLED) {
            // Explicitly disabled - could possibly post a notice
        } else {
            var summarizedChecks = TestResults.summarize(sourceChecks);
            if (summarizedChecks.isEmpty()) {
                // No test related checks found, they may not have started yet, so we'll keep
                // looking as long as the PR is open.
                log.fine("No checks found to summarize - waiting");
                if (pr.isOpen()) {
                    retry.accept(Duration.ofMinutes(2));
                }
            } else {
                Optional<Duration> expiresIn = TestResults.expiresIn(sourceChecks);
                if (expiresIn.isPresent()) {
                    // Workflow is currently running, recheck often to update, but revert
                    // to longer recheck intervals if the PR hasn't been updated in the
                    // last 24h and is still open.
                    if (pr.updatedAt().isAfter(ZonedDateTime.now().minus(Duration.ofDays(1)))) {
                        retry.accept(expiresIn.get());
                    } else if (pr.isOpen()) {
                        retry.accept(Duration.ofMinutes(30));
                    }
                } else if (pr.isOpen()) {
                    // All current checks are finished, as long as PR is open, keep rechecking
                    // at regular, but much longer intervals.
                    retry.accept(Duration.ofMinutes(30));
                }
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
                        (!current.title().equals(check.title()))) {
                    pr.updateCheck(check);
                } else {
                    log.fine("Not updating unchanged check: " + check.name());
                }
            }
        }

        return List.of();
    }

    @Override
    public String botName() {
        return TestInfoBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return botName();
    }

    @Override
    public void handleRuntimeException(RuntimeException e) {
        retry.accept(Duration.ofMinutes(2));
    }
}
