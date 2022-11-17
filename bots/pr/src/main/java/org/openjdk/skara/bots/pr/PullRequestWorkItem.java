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
package org.openjdk.skara.bots.pr;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequest;

import java.util.function.Consumer;

abstract class PullRequestWorkItem implements WorkItem {
    final Consumer<RuntimeException> errorHandler;
    final PullRequestBot bot;
    final String prId;
    /**
     * The updatedAt timestamp of the PR that triggered this WorkItem at the
     * time it was triggered. Used for tracking reaction latency of the bot
     * through logging. This is the best estimated value, which is the last
     * updatedAt value when the bot finds the PR. This value is propagated
     * through chains of WorkItems, as the complete chain is considered to have
     * been triggered by the same PR update.
     */
    final ZonedDateTime prUpdatedAt;
    PullRequest pr;

    PullRequestWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler,
            ZonedDateTime prUpdatedAt) {
        this.bot = bot;
        this.prId = prId;
        this.errorHandler = errorHandler;
        this.prUpdatedAt = prUpdatedAt;
    }

    @Override
    public final boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem otherItem)) {
            return true;
        }
        if (!(prId.equals(otherItem.prId) && bot.repo().isSame(otherItem.bot.repo()))) {
            return true;
        }
        return false;
    }

    /**
     * Loads the PR from the remote repo at the start of run to guarantee that all
     * PullRequestWorkItems have a coherent and current view of the PR to avoid
     * races. When the run method is called, we are guaranteed to be the only
     * WorkItem executing on this specific PR through the concurrentWith method.
     * <p>
     * Subclasses should override prRun instead of this method.
     */
    @Override
    public final Collection<WorkItem> run(Path scratchPath) {
        pr = bot.repo().pullRequest(prId);
        // Check if PR is ready to be evaluated at all. This check is too expensive to run
        // in getPeriodicItems, so call the bot from here.
        if (!bot.isReady(pr)) {
            return List.of();
        }
        return prRun(scratchPath);
    }

    abstract Collection<WorkItem> prRun(Path scratchPath);

    @Override
    public final void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }

    @Override
    public String botName() {
        return bot.name();
    }

    /**
     * Logs a latency message. Meant to be used right before returning from prRun(),
     * if it makes sense to log a message at that point.
     * @param message Message to be logged, will get latency string added to it.
     * @param endTime The end time to use to calculate latency
     * @param log The logger to log to
     */
    protected void logLatency(String message, ZonedDateTime endTime, Logger log) {
        var latency = Duration.between(prUpdatedAt, endTime);
        log.log(Level.INFO, message + latency, latency);
    }
}
