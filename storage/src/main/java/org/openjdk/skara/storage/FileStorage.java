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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class FileStorage<T> implements Storage<T> {
    private final Path file;
    private String old;
    private String current;
    private Set<T> deserialized;
    private StorageSerializer<T> serializer;
    private StorageDeserializer<T> deserializer;

    FileStorage(Path file, StorageSerializer<T> serializer, StorageDeserializer<T> deserializer) {
        this.file = file;
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    @Override
    public Set<T> current() {
        if (current == null) {
            try {
                current = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                current = "";
            }
        }
        if (old != current) {
            deserialized = Collections.unmodifiableSet(deserializer.deserialize(current));
            old = current;
        }
        return deserialized;
    }

    @Override
    public void put(Collection<T> items) {
        var updated = serializer.serialize(items, current());
        if (current.equals(updated)) {
            return;
        }
        try {
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        current = updated;
    }
}
