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
package org.openjdk.skara.storage;

import org.junit.jupiter.api.*;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

public class HostedRepositoryStorageTests {
    String serializer(Collection<String> added, Set<String> existing) {
        return Stream.concat(added.stream(), existing.stream())
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    Set<String> deserializer(String serialized) {
        return serialized.lines().collect(Collectors.toSet());
    }

    @Test
    void failedMaterialization(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
                var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var storage = new HostedRepositoryStorage<>(repo, tempFolder.path(), "master", "test.txt",
                                                        "duke", "duke@openjdk.java.org",
                                                        "Updated storage", this::serializer, this::deserializer);
            storage.put(List.of("a", "b"));

            // Corrupt the destination path and materialize again
            var localRepo = Repository.init(tempFolder.path(), VCS.GIT);
            localRepo.checkout(new Branch("storage"));
            assertThrows(RuntimeException.class, () -> new HostedRepositoryStorage<>(repo, tempFolder.path(), "master", "test.txt",
                                                                                     "duke", "duke@openjdk.java.org",
                                                                                     "Updated storage", this::serializer, this::deserializer));
        }
    }
}
