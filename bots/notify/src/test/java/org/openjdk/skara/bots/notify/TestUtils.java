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

public class TestUtils {
    public static StorageBuilder<UpdatedTag> createTagStorage(HostedRepository repository) {
        return new StorageBuilder<UpdatedTag>("tags.txt")
                .remoteRepository(repository, "history", "Duke", "duke@openjdk.java.net", "Updated tags");
    }

    public static StorageBuilder<UpdatedBranch> createBranchStorage(HostedRepository repository) {
        return new StorageBuilder<UpdatedBranch>("branches.txt")
                .remoteRepository(repository, "history", "Duke", "duke@openjdk.java.net", "Updated branches");
    }

    public static StorageBuilder<PullRequestState> createPullRequestStateStorage(HostedRepository repository) {
        return new StorageBuilder<PullRequestState>("prissues.txt")
                .remoteRepository(repository, "history", "Duke", "duke@openjdk.java.net", "Updated prissues");
    }
}
