/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jbs.*;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class SyncLabelBotCheckWorkItem implements WorkItem {
    private final IssueProject issueProject;
    private final String issueId;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    SyncLabelBotCheckWorkItem(IssueProject issueProject, String issueId) {
        this.issueProject = issueProject;
        this.issueId = issueId;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof SyncLabelBotCheckWorkItem)) {
            return true;
        }
        var o = (SyncLabelBotCheckWorkItem) other;
        return !o.issueId.equals(issueId);
    }

    @Override
    public String toString() {
        return "SyncLabelBotCheckWorkItem@" + issueId;
    }

    @Override
    public Collection<WorkItem> run(Path scratch) {
        var ret = new ArrayList<WorkItem>();
        var issue = issueProject.issue(issueId);
        if (issue.isEmpty()) {
            log.severe("Issue " + issueId + " is no longer present!");
            return List.of();
        }

        var related = Backports.findBackports(issue.get(), true);
        var allIssues = new ArrayList<Issue>();
        allIssues.add(issue.get());
        allIssues.addAll(related);

        var needsLabel = Backports.releaseStreamDuplicates(allIssues);
        for (var i : allIssues) {
            var version = Backports.mainFixVersion(i);
            var versionString = version.map(JdkVersion::raw).orElse("no fix version");
            if (needsLabel.contains(i)) {
                if (i.labels().contains("hgupdate-sync")) {
                    log.finer(i.id() + " (" + versionString + ") - already labeled");
                } else {
                    ret.add(new SyncLabelBotLabelWorkItem(i, SyncLabelBotLabelWorkItem.LabelAction.ADD));
                    log.info(i.id() + " (" + versionString + ") - needs to be labeled");
                }
            } else {
                if (i.labels().contains("hgupdate-sync")) {
                    ret.add(new SyncLabelBotLabelWorkItem(i, SyncLabelBotLabelWorkItem.LabelAction.REMOVE));
                    log.info(i.id() + " (" + versionString + ") - labeled incorrectly!");
                } else {
                    log.finer(i.id() + " (" + versionString + ") - not labeled");
                }
            }
        }

        return ret;
    }
}
