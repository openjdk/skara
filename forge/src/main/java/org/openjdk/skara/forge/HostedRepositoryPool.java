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
package org.openjdk.skara.forge;

import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

public class HostedRepositoryPool {
    private final Path seedStorage;
    private final Logger log = Logger.getLogger("org.openjdk.skara.forge");

    public HostedRepositoryPool(Path seedStorage) {
        this.seedStorage = seedStorage;
    }

    private class HostedRepositoryInstance {
        private final HostedRepository hostedRepository;
        private final Path seed;

        private HostedRepositoryInstance(HostedRepository hostedRepository) {
            this.hostedRepository = hostedRepository;
            this.seed = seedStorage.resolve(hostedRepository.name());
        }

        private void clearDirectory(Path directory) {
            try {
                Files.walk(directory)
                     .map(Path::toFile)
                     .sorted(Comparator.reverseOrder())
                     .forEach(File::delete);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        }

        private void initializeSeed() throws IOException {
            if (!Files.exists(seed)) {
                Files.createDirectories(seed.getParent());
                var tmpSeedFolder = seed.resolveSibling(seed.getFileName().toString() + "-" + UUID.randomUUID());
                Repository.clone(hostedRepository.url(), tmpSeedFolder, true);
                try {
                    Files.move(tmpSeedFolder, seed);
                    log.info("Seeded repository " + hostedRepository.name() + " into " + seed);
                } catch (IOException e) {
                    log.info("Failed to populate seed folder " + seed + " - perhaps due to a benign race. Ignoring..");
                    clearDirectory(tmpSeedFolder);
                }
            }
        }

        private Repository cloneSeeded(Path path) throws IOException {
            initializeSeed();
            log.info("Using seed folder " + seed + " when cloning into " + path);
            return Repository.clone(hostedRepository.url(), path, false, seed);
        }

        private void removeOldClone(Path path, String reason) {
            if (!Files.exists(seed)) {
                try {
                    Files.createDirectories(seed.getParent());
                } catch (IOException e) {
                    log.severe("Failed to create seed parent folder: " + seed.getParent());
                    log.throwing("HostedRepositoryInstance", "preserveOldClone", e);
                }
            }
            if (Files.exists(path)) {
                var preserved = seed.resolveSibling(seed.getFileName().toString() + "-" + reason + "-" + UUID.randomUUID());
                log.severe("Invalid local repository detected (" + reason + ") - preserved in: " + preserved);
                try {
                    Files.move(path, preserved);
                } catch (IOException e) {
                    log.severe("Failed to preserve old clone at " + path);
                    log.throwing("HostedRepositoryInstance", "preserveOldClone", e);
                } finally {
                    if (Files.exists(path)) {
                        clearDirectory(path);
                    }
                }
            }
        }

        private Repository materializeClone(Path path) throws IOException {
            var localRepo = Repository.get(path);
            if (localRepo.isEmpty()) {
                removeOldClone(path, "norepo");
                return cloneSeeded(path);
            } else {
                var localRepoInstance = localRepo.get();
                if (!localRepoInstance.isHealthy()) {
                    removeOldClone(path, "unhealthy");
                    return cloneSeeded(path);
                } else {
                    try {
                        localRepoInstance.clean();
                        return localRepoInstance;
                    } catch (IOException e) {
                        removeOldClone(path, "uncleanable");
                        return cloneSeeded(path);
                    }
                }
            }
        }
    }

    public Repository materialize(HostedRepository hostedRepository, Path path) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        return hostedRepositoryInstance.materializeClone(path);
    }

    private static class NewClone {
        private final Repository repository;
        private final Hash fetchHead;

        NewClone(Repository repository, Hash fetchHead) {
            this.repository = repository;
            this.fetchHead = fetchHead;
        }

        Repository repository() {
            return repository;
        }

        Hash fetchHead() {
            return fetchHead;
        }
    }

    private NewClone fetchRef(HostedRepository hostedRepository, Repository repository, String ref) throws IOException {
        var fetchHead = repository.fetch(hostedRepository.url(), "+" + ref + ":hostedrepositorypool");
        return new NewClone(repository, fetchHead);
    }

    public Repository checkout(HostedRepository hostedRepository, String ref, Path path) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        var clone = fetchRef(hostedRepository, hostedRepositoryInstance.materializeClone(path), ref);
        var localRepo = clone.repository();
        try {
            localRepo.checkout(clone.fetchHead(), true);
        } catch (IOException e) {
            var preserveUnchecked = hostedRepositoryInstance.seed.resolveSibling(
                    hostedRepositoryInstance.seed.getFileName().toString() + "-unchecked-" + UUID.randomUUID());
            log.severe("Uncheckoutable local repository detected - preserved in: " + preserveUnchecked);
            Files.move(localRepo.root(), preserveUnchecked);
            clone = fetchRef(hostedRepository, hostedRepositoryInstance.cloneSeeded(path), ref);
            localRepo = clone.repository();
            localRepo.checkout(clone.fetchHead(), true);
        }
        return localRepo;
    }

    public Repository checkout(PullRequest pr, Path path) throws IOException {
        return checkout(pr.repository(), pr.headHash().hex(), path);
    }
}
