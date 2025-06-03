/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.BeforeAll;
import org.openjdk.skara.test.TestableRepository;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RepositoryStorageTests {
    private static boolean hgAvailable = true;

    @BeforeAll
    static void checkHgAvailability() {
        try {
            var pb = new ProcessBuilder("hg", "--version");
            pb.redirectErrorStream(true);
            var process = pb.start();
            process.waitFor();
            hgAvailable = (process.exitValue() == 0);
        } catch (Exception e) {
            hgAvailable = false;
        }
    }

    private RepositoryStorage<String> stringStorage(Repository repository) {
        return new RepositoryStorage<>(repository, "db.txt", "Duke", "duke@openjdk.org", "Test update",
                                       (added, cur) -> Stream.concat(cur.stream(), added.stream())
                                                             .sorted()
                                                             .collect(Collectors.joining(";")),
                                       cur -> Arrays.stream(cur.split(";"))
                                                    .filter(str -> !str.isEmpty())
                                                    .collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void simple(VCS vcs) throws IOException {
        assumeFalse(vcs == VCS.HG && !hgAvailable);
        var tmpDir = Files.createTempDirectory("repositorystorage");
        var repository = TestableRepository.init(tmpDir, vcs);
        var storage = stringStorage(repository);

        assertEquals(Set.of(), storage.current());
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void multiple(VCS vcs) throws IOException {
        assumeFalse(vcs == VCS.HG && !hgAvailable);
        var tmpDir = Files.createTempDirectory("repositorystorage");
        var repository = TestableRepository.init(tmpDir, vcs);
        var storage = stringStorage(repository);

        assertEquals(Set.of(), storage.current());
        storage.put(Set.of("hello", "there"));
        assertEquals(Set.of("hello", "there"), storage.current());
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void retained(VCS vcs) throws IOException {
        assumeFalse(vcs == VCS.HG && !hgAvailable);
        var tmpDir = Files.createTempDirectory("repositorystorage");
        var repository = TestableRepository.init(tmpDir, vcs);
        var storage = stringStorage(repository);

        assertEquals(Set.of(), storage.current());
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());

        var newRepository = Repository.get(tmpDir).orElseThrow();
        var newStorage = stringStorage(repository);
        assertEquals(Set.of("hello there"), newStorage.current());
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void duplicates(VCS vcs) throws IOException {
        assumeFalse(vcs == VCS.HG && !hgAvailable);
        var tmpDir = Files.createTempDirectory("repositorystorage");
        var repository = TestableRepository.init(tmpDir, vcs);
        var storage = stringStorage(repository);

        assertEquals(Set.of(), storage.current());
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());
        storage.put("hello there again");
        assertEquals(Set.of("hello there", "hello there again"), storage.current());
    }
}
