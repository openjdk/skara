/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.nio.file.Path;
import java.util.List;

public interface RepositoryListener {
    default void onNewCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, Branch branch) throws NonRetriableException {
    }
    default void onNewOpenJDKTagCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) throws NonRetriableException {
    }
    default void onNewTagCommit(HostedRepository repository, Repository localRepository, Path scratchPath, Commit commit, Tag tag, Tag.Annotated annotation) throws NonRetriableException {
    }
    default void onNewBranch(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, Branch parent, Branch branch) throws NonRetriableException {
    }
    String name();

    /**
     * Returns true if this listener can handle being called with the same
     * data multiple times without generating multiple notifications
     */
    boolean idempotent();
}
