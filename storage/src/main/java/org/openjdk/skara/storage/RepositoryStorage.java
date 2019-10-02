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

import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.util.*;

class RepositoryStorage<T> implements Storage<T> {
    private final Repository repository;
    private final String fileName;
    private final String authorName;
    private final String authorEmail;
    private final String message;
    private final FileStorage<T> fileStorage;

    private Set<T> current;

    RepositoryStorage(Repository repository, String fileName, String authorName, String authorEmail, String message, StorageSerializer<T> serializer, StorageDeserializer<T> deserializer) {
        this.repository = repository;
        this.fileName = fileName;
        this.authorEmail = authorEmail;
        this.authorName = authorName;
        this.message = message;

        try {
            if (!repository.isHealthy()) {
                repository.reinitialize();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            fileStorage = new FileStorage<>(repository.root().resolve(fileName), serializer, deserializer);
            current = current();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Set<T> current() {
        return fileStorage.current();
    }

    @Override
    public void put(Collection<T> items) {
        fileStorage.put(items);
        var updated = current();
        if (current.equals(updated)) {
            return;
        }
        current = updated;
        try {
            repository.add(repository.root().resolve(fileName));
            repository.commit(message, authorName, authorEmail);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
