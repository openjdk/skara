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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.host.*;

import java.util.List;
import java.util.stream.Collectors;

public class SubmitBot implements Bot {
    private final HostedRepository repository;
    private final List<SubmitExecutor> executors;
    private final PullRequestUpdateCache updateCache;

    SubmitBot(HostedRepository repository, List<SubmitExecutor> executors) {
        this.repository = repository;
        this.executors = executors;
        this.updateCache = new PullRequestUpdateCache();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return repository.getPullRequests().stream()
                         .filter(updateCache::needsUpdate)
                         .flatMap(pr -> executors.stream()
                                                 .map(executor -> new SubmitBotWorkItem(this, executor, pr)))
                         .collect(Collectors.toList());
    }

    HostedRepository repository() {
        return repository;
    }
}
