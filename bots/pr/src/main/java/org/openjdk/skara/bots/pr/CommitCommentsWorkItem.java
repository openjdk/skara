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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class CommitCommentsWorkItem implements WorkItem {
    private final PullRequestBot bot;
    private final HostedRepository repo;

    private static final ConcurrentHashMap<String, Boolean> processed = new ConcurrentHashMap<>();
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    CommitCommentsWorkItem(PullRequestBot bot, HostedRepository repo) {
        this.bot = bot;
        this.repo = repo;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        return true;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        log.info("Looking for recent commit comments for repository " + repo.name());

        return repo.recentCommitComments()
                   .stream()
                   .filter(cc -> !processed.containsKey(cc.id()))
                   .map(cc -> {
                       processed.put(cc.id(), true);
                       return new CommitCommandWorkItem(bot, cc, e -> processed.remove(cc.id()));
                   })
                   .collect(Collectors.toList());

    }
}
