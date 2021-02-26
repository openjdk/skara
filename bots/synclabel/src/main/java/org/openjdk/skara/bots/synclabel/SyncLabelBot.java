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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.issuetracker.IssueProject;

import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class SyncLabelBot implements Bot {
    private final IssueProject issueProject;
    private final Pattern inspect;
    private final Pattern ignore;
    private final Map<String, ZonedDateTime> issueUpdatedAt = new HashMap<>();

    private ZonedDateTime lastUpdate;

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    SyncLabelBot(IssueProject issueProject, Pattern inspect, Pattern ignore) {
        this.issueProject = issueProject;
        this.inspect = inspect;
        this.ignore = ignore;
    }

    IssueProject issueProject() {
        return issueProject;
    }

    Pattern inspect() {
        return inspect;
    }

    Pattern ignore() {
        return ignore;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        ZonedDateTime updatedAfter;
        if (lastUpdate == null) {
            updatedAfter = ZonedDateTime.now().minus(Duration.ofHours(2));
        } else {
            updatedAfter = lastUpdate.minus(Duration.ofMinutes(30));
            lastUpdate = ZonedDateTime.now();
        }

        var issues = issueProject.issues(updatedAfter);
        var ret = new ArrayList<WorkItem>();
        for (var issue : issues) {
            var lastUpdate = issueUpdatedAt.get(issue.id());
            if (lastUpdate != null) {
                if (!issue.updatedAt().isAfter(lastUpdate)) {
                    continue;
                }
            }
            issueUpdatedAt.put(issue.id(), issue.updatedAt());
            ret.add(new SyncLabelBotFindMainIssueWorkItem(this, issue.id()));
        }
        return ret;
    }
}
