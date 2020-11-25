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
import java.util.*;

public class TestInfoBotWorkItem implements WorkItem {
    private final PullRequest pr;
    private final List<Check> summarizedChecks;

    TestInfoBotWorkItem(PullRequest pr, List<Check> summarizedChecks) {
        this.pr = pr;
        this.summarizedChecks = summarizedChecks;
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

    @Override
    public Collection<WorkItem> run(Path scratch) {
        var existing = pr.checks(pr.headHash());
        for (var check : summarizedChecks) {
            if (!existing.containsKey(check.name())) {
                pr.createCheck(check);
            }
            pr.updateCheck(check);
        }

        return List.of();
    }
}
