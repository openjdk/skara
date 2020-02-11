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
        private final String ref;

        private HostedRepositoryInstance(HostedRepository hostedRepository, String ref) {
            this.hostedRepository = hostedRepository;
            this.seed = seedStorage.resolve(hostedRepository.name());
            this.ref = ref;
        }

        private class NewClone {
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

        private NewClone fetchRef(Repository repository) throws IOException {
            var fetchHead = repository.fetch(hostedRepository.url(), "+" + ref + ":hostedrepositorypool");
            return new NewClone(repository, fetchHead);
        }

        private NewClone materializeClone(Path path) throws IOException {
            var localRepo = Repository.get(path);
            if (localRepo.isEmpty()) {
                return fetchRef(cloneSeeded(path));
            } else {
                var localRepoInstance = localRepo.get();
                if (!localRepoInstance.isHealthy()) {
                    var preserveUnhealthy = seed.resolveSibling(seed.getFileName().toString() + "-unhealthy-" + UUID.randomUUID());
                    log.severe("Unhealthy local repository detected - preserved in: " + preserveUnhealthy);
                    Files.move(path, preserveUnhealthy);
                    return fetchRef(cloneSeeded(path));
                } else {
                    try {
                        localRepoInstance.clean();
                        return fetchRef(localRepoInstance);
                    } catch (IOException e) {
                        var preserveUnclean = seed.resolveSibling(seed.getFileName().toString() + "-unclean-" + UUID.randomUUID());
                        log.severe("Uncleanable local repository detected - preserved in: " + preserveUnclean);
                        Files.move(path, preserveUnclean);
                        return fetchRef(cloneSeeded(path));
                    }
                }
            }
        }
    }

    public Repository materialize(HostedRepository hostedRepository, String ref, Path path) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository, ref);
        var clone = hostedRepositoryInstance.materializeClone(path);
        return clone.repository();
    }

    public Repository materialize(PullRequest pr, Path path) throws IOException {
        return materialize(pr.repository(), pr.sourceRef(), path);
    }

    public Repository checkout(HostedRepository hostedRepository, String ref, Path path) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository, ref);
        var clone = hostedRepositoryInstance.materializeClone(path);
        var localRepo = clone.repository();
        try {
            localRepo.checkout(clone.fetchHead(), true);
        } catch (IOException e) {
            var preserveUnchecked = hostedRepositoryInstance.seed.resolveSibling(
                    hostedRepositoryInstance.seed.getFileName().toString() + "-unchecked-" + UUID.randomUUID());
            log.severe("Uncheckoutable local repository detected - preserved in: " + preserveUnchecked);
            Files.move(localRepo.root(), preserveUnchecked);
            clone = hostedRepositoryInstance.fetchRef(hostedRepositoryInstance.cloneSeeded(path));
            localRepo = clone.repository();
            localRepo.checkout(clone.fetchHead(), true);
        }
        return localRepo;
    }

    public Repository checkout(PullRequest pr, Path path) throws IOException {
        return checkout(pr.repository(), pr.headHash().hex(), path);
    }
}
