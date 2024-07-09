/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.synclabel;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.jbs.Backports;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class SyncLabelBotFindMainIssueWorkItem implements WorkItem {
    private final String issueId;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");
    private final SyncLabelBot bot;

    SyncLabelBotFindMainIssueWorkItem(SyncLabelBot bot, String issueId) {
        this.bot = bot;
        this.issueId = issueId;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof SyncLabelBotFindMainIssueWorkItem o)) {
            return true;
        }
        return !o.issueId.equals(issueId);
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var issue = bot.issueProject().issue(issueId);
        if (issue.isEmpty()) {
            log.severe("Issue " + issueId + " is no longer present!");
            return List.of();
        }

        var primary = Backports.findMainIssue(issue.get());
        if (primary.isEmpty()) {
            log.info("No main issue found for " + issue.get().id());
            return List.of();
        }

        return List.of(new SyncLabelBotUpdateLabelWorkItem(bot, primary.get().id()));
    }

    @Override
    public String toString() {
        return "SyncLabelBotFindMainIssueWorkItem@" + issueId;
    }

    @Override
    public String botName() {
        return SyncLabelBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "find-main-issue";
    }
}
