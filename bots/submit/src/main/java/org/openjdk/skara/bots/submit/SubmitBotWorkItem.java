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
package org.openjdk.skara.bots.submit;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.logging.Logger;

public class SubmitBotWorkItem implements WorkItem {
    private final SubmitBot bot;
    private final SubmitExecutor executor;
    private final PullRequest pr;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.submit");

    SubmitBotWorkItem(SubmitBot bot, SubmitExecutor executor, PullRequest pr) {
        this.bot = bot;
        this.executor = executor;
        this.pr = pr;
    }

    @Override
    public String toString() {
        return "SubmitWorkItem@" + bot.repository().name() + "#" + pr.id() + ":" + executor.checkName();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof SubmitBotWorkItem)) {
            return true;
        }
        SubmitBotWorkItem otherItem = (SubmitBotWorkItem)other;
        if (!executor.checkName().equals(otherItem.executor.checkName())) {
            return true;
        }
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!bot.repository().name().equals(otherItem.bot.repository().name())) {
            return true;
        }
        return false;
    }

    @Override
    public void run(Path scratchPath) {
        // Is the check already up to date?
        var checks = pr.checks(pr.headHash());
        if (checks.containsKey(executor.checkName())) {
            var check = checks.get(executor.checkName());
            if (check.startedAt().isBefore(ZonedDateTime.now().minus(executor.timeout())) && check.status() == CheckStatus.IN_PROGRESS) {
                log.info("Check for hash " + pr.headHash() + " is too old - running again");
            } else {
                log.fine("Hash " + pr.headHash() + " already has a check - skipping");
                return;
            }
        }

        var prFolder = scratchPath.resolve("submit").resolve(pr.repository().name());

        // Materialize the PR's target ref
        try {
            var localRepo = Repository.materialize(prFolder, pr.repository().url(),
                                                   "+" + pr.targetRef() + ":submit_" + pr.repository().name());
            var headHash = localRepo.fetch(pr.repository().url(), pr.headHash().hex(), false);

            var checkBuilder = CheckBuilder.create(executor.checkName(), headHash);
            pr.createCheck(checkBuilder.build());

            var checkUpdater = new CheckUpdater(pr, checkBuilder);
            executor.run(prFolder, checkBuilder, checkUpdater);
            pr.updateCheck(checkBuilder.build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
