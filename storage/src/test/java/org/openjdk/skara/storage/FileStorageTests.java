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
package org.openjdk.skara.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileStorageTests {
    private FileStorage<String> stringStorage(Path fileName) {
        return new FileStorage<>(fileName, (added, cur) -> Stream.concat(cur.stream(), added.stream())
                                                                 .sorted()
                                                                 .collect(Collectors.joining(";")),
                                 cur -> Arrays.stream(cur.split(";"))
                                              .filter(str -> !str.isEmpty())
                                              .collect(Collectors.toSet()));
    }

    @Test
    void simple() throws IOException {
        var tmpFile = Files.createTempFile("filestorage", ".txt");
        var storage = stringStorage(tmpFile);

        assertEquals(Set.of(), storage.current());
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());

        Files.delete(tmpFile);
    }

    @Test
    void multiple() throws IOException {
        var tmpFile = Files.createTempFile("filestorage", ".txt");
        var storage = stringStorage(tmpFile);

        assertEquals(Set.of(), storage.current());
        storage.put(List.of("hello", "there"));
        assertEquals(Set.of("hello", "there"), storage.current());

        Files.delete(tmpFile);
    }

    @Test
    void retained() throws IOException {
        var tmpFile = Files.createTempFile("filestorage", ".txt");
        var storage = stringStorage(tmpFile);

        assertEquals(Set.of(), storage.current());
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());

        var newStorage = stringStorage(tmpFile);
        assertEquals(Set.of("hello there"), newStorage.current());

        Files.delete(tmpFile);
    }

    private static class CountingDeserializer implements StorageDeserializer<String> {
        private int counter = 0;

        CountingDeserializer() {
        }

        int counter() {
            return counter;
        }

        @Override
        public Set<String> deserialize(String serialized) {
            counter++;
            return Arrays.stream(serialized.split(";"))
                         .filter(str -> !str.isEmpty())
                         .collect(Collectors.toSet());
        }
    }

    @Test
    void cached() throws IOException {
        var tmpFile = Files.createTempFile("filestorage", ".txt");
        var deserializer = new CountingDeserializer();
        var storage = new FileStorage<String>(tmpFile,
                                              (added, cur) -> Stream.concat(cur.stream(), added.stream())
                                                                    .sorted()
                                                                    .collect(Collectors.joining(";")),
                                              deserializer);
        assertEquals(Set.of(), storage.current());
        assertEquals(1, deserializer.counter());

        // Another call to current() should not cause deseralization
        storage.current();
        assertEquals(1, deserializer.counter());

        // Updated content should cause deseralization
        storage.put("hello there");
        assertEquals(Set.of("hello there"), storage.current());
        assertEquals(2, deserializer.counter());

        // Another call to current() should not cause deseralization
        assertEquals(Set.of("hello there"), storage.current());
        assertEquals(2, deserializer.counter());

        Files.delete(tmpFile);
    }
}
