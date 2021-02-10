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
import org.openjdk.skara.issuetracker.Issue;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class SyncLabelBotLabelWorkItem implements WorkItem {
    private final Issue issue;
    private final LabelAction labelAction;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    enum LabelAction {
        ADD,
        REMOVE
    }

    SyncLabelBotLabelWorkItem(Issue issue, LabelAction labelAction) {
        this.issue = issue;
        this.labelAction = labelAction;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof SyncLabelBotLabelWorkItem)) {
            return true;
        }
        var o = (SyncLabelBotLabelWorkItem) other;
        return !o.issue.webUrl().equals(issue.webUrl());
    }

    @Override
    public String toString() {
        return "SyncLabelBotLabelWorkItem@" + issue.project().name() + "#" + issue.id();
    }

    @Override
    public Collection<WorkItem> run(Path scratch) {
        switch (labelAction) {
            case ADD -> log.severe("Adding hgupdate-sync label to " + issue.id());
            case REMOVE -> log.severe("Removing hgupdate-sync label from " + issue.id());
        }
        return List.of();
    }
}
