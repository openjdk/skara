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

import org.openjdk.skara.forge.HostedRepository;

import java.nio.file.Path;

public class StorageBuilder<T> {
    private final String fileName;

    private HostedRepository remoteRepository;
    private String remoteRef;
    private String remoteAuthorName;
    private String remoteAuthorEmail;
    private String remoteMessage;
    private StorageSerializer<T> serializer;
    private StorageDeserializer<T> deserializer;

    /**
     * Create a StorageBuilder instance that will use the given fileName to store data.
     * @param fileName
     * @return
     */
    public StorageBuilder(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Set the storage serializer.
     * @param serializer
     * @return
     */
    public StorageBuilder<T> serializer(StorageSerializer<T> serializer) {
        this.serializer = serializer;
        return this;
    }

    /**
     * Set the storage deserializer.
     * @param deserializer
     * @return
     */
    public StorageBuilder<T> deserializer(StorageDeserializer<T> deserializer) {
        this.deserializer = deserializer;
        return this;
    }

    /**
     * Attach a remote repository to the Storage where any changes will be added as commits.
     * @param repository
     * @param ref
     * @param authorName
     * @param authorEmail
     * @param message
     * @return
     */
    public StorageBuilder<T> remoteRepository(HostedRepository repository, String ref, String authorName, String authorEmail, String message) {
        if (remoteRepository != null) {
            throw new IllegalArgumentException("Can only set a single remote repository");
        }
        remoteRepository = repository;
        remoteRef = ref;
        remoteAuthorName = authorName;
        remoteAuthorEmail = authorEmail;
        remoteMessage = message;
        return this;
    }

    /**
     * Create a Storage instance.
     * @param localFolder
     * @return
     */
    public Storage<T> materialize(Path localFolder) {
        if (remoteRepository != null) {
            return new HostedRepositoryStorage<>(remoteRepository, localFolder, remoteRef, fileName, remoteAuthorName, remoteAuthorEmail, remoteMessage, serializer, deserializer);
        } else {
            return new FileStorage<>(localFolder.resolve(fileName), serializer, deserializer);
        }
    }
}
