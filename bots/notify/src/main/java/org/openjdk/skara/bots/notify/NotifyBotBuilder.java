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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.storage.StorageBuilder;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class NotifyBotBuilder {
    private HostedRepository repository;
    private Path storagePath;
    private Pattern branches;
    private StorageBuilder<UpdatedTag> tagStorageBuilder;
    private StorageBuilder<UpdatedBranch> branchStorageBuilder;
    private StorageBuilder<PullRequestState> prStateStorageBuilder;
    private List<RepositoryUpdateConsumer> updaters = List.of();
    private List<PullRequestUpdateConsumer> prUpdaters = List.of();
    private Set<String> readyLabels = Set.of();
    private Map<String, Pattern> readyComments = Map.of();

    public NotifyBotBuilder repository(HostedRepository repository) {
        this.repository = repository;
        return this;
    }

    public NotifyBotBuilder storagePath(Path storagePath) {
        this.storagePath = storagePath;
        return this;
    }

    public NotifyBotBuilder branches(Pattern branches) {
        this.branches = branches;
        return this;
    }

    public NotifyBotBuilder tagStorageBuilder(StorageBuilder<UpdatedTag> tagStorageBuilder) {
        this.tagStorageBuilder = tagStorageBuilder;
        return this;
    }

    public NotifyBotBuilder branchStorageBuilder(StorageBuilder<UpdatedBranch> branchStorageBuilder) {
        this.branchStorageBuilder = branchStorageBuilder;
        return this;
    }

    public NotifyBotBuilder prStateStorageBuilder(StorageBuilder<PullRequestState> prStateStorageBuilder) {
        this.prStateStorageBuilder = prStateStorageBuilder;
        return this;
    }

    public NotifyBotBuilder updaters(List<RepositoryUpdateConsumer> updaters) {
        this.updaters = updaters;
        return this;
    }

    public NotifyBotBuilder prUpdaters(List<PullRequestUpdateConsumer> prUpdaters) {
        this.prUpdaters = prUpdaters;
        return this;
    }

    public NotifyBotBuilder readyLabels(Set<String> readyLabels) {
        this.readyLabels = readyLabels;
        return this;
    }

    public NotifyBotBuilder readyComments(Map<String, Pattern> readyComments) {
        this.readyComments = readyComments;
        return this;
    }

    public NotifyBot build() {
        return new NotifyBot(repository, storagePath, branches, tagStorageBuilder, branchStorageBuilder, prStateStorageBuilder, updaters, prUpdaters, readyLabels, readyComments);
    }
}
