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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.webrev.Webrev;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class WebrevStorage {
    private final HostedRepository storage;
    private final String storageRef;
    private final Path baseFolder;
    private final URI baseUri;
    private final EmailAddress author;

    WebrevStorage(HostedRepository storage, String ref, Path baseFolder, URI baseUri, EmailAddress author) {
        this.baseFolder = baseFolder;
        this.baseUri = baseUri;
        this.storage = storage;
        storageRef = ref;
        this.author = author;
    }

    private void generate(PullRequest pr, Repository localRepository, Path folder, Hash base, Hash head) throws IOException {
        Files.createDirectories(folder);
        Webrev.repository(localRepository).output(folder)
              .generate(base, head);
    }

    private void push(Repository localStorage, Path webrevFolder, String identifier) throws IOException {
        var batchIndex = new AtomicInteger();
        try (var files = Files.walk(webrevFolder)) {
            // Try to push 1000 files at a time
            var batches = files.filter(Files::isRegularFile)
                               .filter(file -> {
                                   // Huge files are not that useful in a webrev - but make an exception for the index
                                   try {
                                       if (file.getFileName().toString().equals("index.html")) {
                                           return true;
                                       } else {
                                           return Files.size(file) < 1000 * 1000;
                                       }
                                   } catch (IOException e) {
                                       return false;
                                   }
                               })
                               .collect(Collectors.groupingBy(path -> {
                                   int curIndex = batchIndex.incrementAndGet();
                                   return Math.floorDiv(curIndex, 1000);
                               }));

            for (var batch : batches.entrySet()) {
                localStorage.add(batch.getValue());
                Hash hash;
                var message = "Added webrev for " + identifier +
                        (batches.size() > 1 ? " (" + (batch.getKey() + 1) + "/" + batches.size() + ")" : "");
                try {
                    hash = localStorage.commit(message, author.fullName().orElseThrow(), author.address());
                } catch (IOException e) {
                    // If the commit fails, it probably means that we're resuming a partially completed previous update
                    // where some of the files have already been committed. Ignore it and continue.
                    continue;
                }
                localStorage.push(hash, storage.url(), storageRef);
            }
        }
    }

    private static void clearDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            files.map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
    }

    private URI createAndArchive(PullRequest pr, Repository localRepository, Path scratchPath, Hash base, Hash head, String identifier) {
        try {
            var localStorage = Repository.materialize(scratchPath, storage.url(),
                                                      "+" + storageRef + ":mlbridge_webrevs");
            var relativeFolder = baseFolder.resolve(String.format("%s/webrev.%s", pr.id(), identifier));
            var outputFolder = scratchPath.resolve(relativeFolder);
            // If a previous operation was interrupted there may be content here already - overwrite if so
            if (Files.exists(outputFolder)) {
                clearDirectory(outputFolder);
            }
            generate(pr, localRepository, outputFolder, base, head);
            if (!localStorage.isClean()) {
                push(localStorage, outputFolder, baseFolder.resolve(pr.id()).toString());
            }
            return URIBuilder.base(baseUri).appendPath(relativeFolder.toString().replace('\\', '/')).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    interface WebrevGenerator {
        URI generate(Hash base, Hash head, String identifier);
    }

    WebrevGenerator generator(PullRequest pr, Repository localRepository, Path scratchPath) {
        return (base, head, identifier) -> createAndArchive(pr, localRepository, scratchPath, base, head, identifier);
    }
}
