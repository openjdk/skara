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
import java.util.stream.*;

public class SyncLabelBotUpdateLabelWorkItem implements WorkItem {
    private final IssueProject issueProject;
    private final String mainIssueId;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    SyncLabelBotUpdateLabelWorkItem(IssueProject issueProject, String mainIssueId) {
        this.issueProject = issueProject;
        this.mainIssueId = mainIssueId;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof SyncLabelBotUpdateLabelWorkItem)) {
            return true;
        }
        var o = (SyncLabelBotUpdateLabelWorkItem) other;
        return !o.mainIssueId.equals(mainIssueId);
    }

    @Override
    public String toString() {
        return "SyncLabelBotUpdateLabelWorkItem@" + mainIssueId;
    }

    @Override
    public Collection<WorkItem> run(Path scratch) {
        var issue = issueProject.issue(mainIssueId);
        if (issue.isEmpty()) {
            log.severe("Issue " + mainIssueId + " is no longer present!");
            return List.of();
        }

        var allIssues = Stream.concat(Stream.of(issue.get()), Backports.findBackports(issue.get(), true).stream())
                              .filter(i -> !i.labels().contains("hgupdate-sync-ignore"))
                              .collect(Collectors.toList());

        var needsLabel = Backports.releaseStreamDuplicates(allIssues);
        for (var i : allIssues) {
            var version = Backports.mainFixVersion(i);
            var versionString = version.map(JdkVersion::raw).orElse("no fix version");
            if (needsLabel.contains(i)) {
                if (i.labels().contains("hgupdate-sync")) {
                    log.finer(i.id() + " (" + versionString + ") - already labeled");
                } else {
                    log.info(i.id() + " (" + versionString + ") - needs to be labeled");
                    i.addLabel("hgupdate-sync");
                }
            } else {
                if (i.labels().contains("hgupdate-sync")) {
                    log.info(i.id() + " (" + versionString + ") - labeled incorrectly!");
                    i.removeLabel("hgupdate-sync");
                } else {
                    log.finer(i.id() + " (" + versionString + ") - not labeled");
                }
            }
        }

        return List.of();
    }
}
