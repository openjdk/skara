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
import java.net.URI;
import java.nio.file.*;
import java.time.*;
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
            this.seed = seedStorage.resolve(hostedRepository.name() + (hostedRepository.repositoryType() == VCS.GIT ? ".git" : ""));
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

        private URI seedUri() {
            var uri = seed.toUri().toString().replaceAll(".git[/\\\\]$", ".git");
            return URI.create(uri);
        }

        private void refreshSeed(boolean allowStale) throws IOException {
            if (!Files.exists(seed)) {
                Files.createDirectories(seed.getParent());
                var tmpSeedFolder = seed.resolveSibling(seed.getFileName().toString() + "-" + UUID.randomUUID());
                Repository.clone(hostedRepository.url(), tmpSeedFolder, true);
                try {
                    Files.move(tmpSeedFolder, seed);
                    log.info("Seeded repository " + hostedRepository.name() + " into " + seed);
                    return;
                } catch (IOException e) {
                    log.info("Failed to populate seed folder " + seed + " - perhaps due to a benign race. Ignoring..");
                    clearDirectory(tmpSeedFolder);
                }
            }

            // If a stale materialization isn't allowed, we will always clone directly from the source and thus do not
            // need to refresh the seed folder
            if (!allowStale) {
                return;
            }
            var seedRepo = Repository.get(seed).orElseThrow(() -> new IOException("Existing seed is corrupt?"));
            try {
                var lastFetch = Files.getLastModifiedTime(seed.resolve("FETCH_HEAD"));
                if (lastFetch.toInstant().isAfter(Instant.now().minus(Duration.ofMinutes(1)))) {
                    log.info("Seed should be up to date, skipping fetch");
                    return;
                } else {
                    log.info("Seed is potentially stale, time to fetch the latest upstream changes");
                }
            } catch (IOException ignored) {
            }
            try {
                seedRepo.fetchAll(hostedRepository.url(), true);
            } catch (IOException e) {
                log.info("Failed to refresh seed - ignoring");
            }
        }

        private Repository seedRepository(boolean allowStale) throws IOException {
            refreshSeed(allowStale);
            return Repository.get(seed).orElseThrow(() -> new IOException("Existing seed is corrupt?"));
        }

        private Repository cloneSeeded(Path path, boolean allowStale, boolean bare) throws IOException {
            refreshSeed(true);
            var remote = allowStale ? seedUri() : hostedRepository.url();
            log.info("Using seed folder " + seed + " when cloning into " + path + " from " + remote + (bare ? " (bare)" : ""));
            return Repository.clone(remote, path, bare, seed);
        }

        private void removeOldClone(Path path, String reason) {
            if (Files.exists(path)) {
                var preserved = path.resolveSibling(seed.getFileName().toString() + "-" + reason + "-" + UUID.randomUUID());
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

        private Repository materializeClone(Path path, boolean allowStale, boolean bare) throws IOException {
            var localRepo = Repository.get(path);
            if (localRepo.isEmpty()) {
                removeOldClone(path, "norepo");
                return cloneSeeded(path, allowStale, bare);
            } else {
                var localRepoInstance = localRepo.get();
                if (!localRepoInstance.isHealthy()) {
                    removeOldClone(path, "unhealthy");
                    return cloneSeeded(path, allowStale, bare);
                } else {
                    try {
                        refreshSeed(allowStale);
                        if (!bare) {
                            localRepoInstance.clean();
                        }
                        return localRepoInstance;
                    } catch (IOException e) {
                        removeOldClone(path, "uncleanable");
                        return cloneSeeded(path, allowStale, bare);
                    }
                }
            }
        }
    }

    public Repository materialize(HostedRepository hostedRepository, Path path) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        return hostedRepositoryInstance.materializeClone(path, false, false);
    }

    public Repository materializeBare(HostedRepository hostedRepository, Path path) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        return hostedRepositoryInstance.materializeClone(path, false, true);
    }

    private Repository checkout(HostedRepository hostedRepository, String ref, Path path, boolean allowStale) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        var localClone = hostedRepositoryInstance.materializeClone(path, true, false);
        var remote = allowStale ? hostedRepositoryInstance.seedUri() : hostedRepository.url();
        log.info("Updating local repository from: " + remote);
        var refHash = localClone.fetch(remote, "+" + ref + ":hostedrepositorypool");
        try {
            localClone.checkout(refHash, true);
        } catch (IOException e) {
            var preserveUnchecked = path.resolveSibling(hostedRepositoryInstance.seed.getFileName().toString() + "-unchecked-" + UUID.randomUUID());
            log.severe("Uncheckoutable local repository detected - preserved in: " + preserveUnchecked);
            Files.move(localClone.root(), preserveUnchecked);
            localClone = hostedRepositoryInstance.materializeClone(path, false, false);
            localClone.checkout(new Branch(ref), true);
        }
        return localClone;
    }

    public Repository checkout(HostedRepository hostedRepository, String ref, Path path) throws IOException {
        return checkout(hostedRepository, ref, path, false);
    }

    public Repository checkoutAllowStale(HostedRepository hostedRepository, String ref, Path path) throws IOException {
        return checkout(hostedRepository, ref, path, true);
    }

    private void fetchWithRetry(Repository repo, URI url) throws IOException {
        IOException lastException = null;
        for (int count = 0; count < 10; ++count) {
            try {
                repo.fetchAll(url, true);
                return;
            } catch (IOException e) {
                lastException = e;
            }
        }

        throw lastException;
    }

    public Optional<List<String>> lines(HostedRepository hostedRepository, Path p, Hash hash) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        var seedRepo = hostedRepositoryInstance.seedRepository(true);
        if (!seedRepo.contains(hash)) {
            // It may fail because the seed is stale - need to refresh it now
            fetchWithRetry(seedRepo, hostedRepository.url());
        }
        return seedRepo.lines(p, hash);
    }

    public Repository seedRepository(HostedRepository hostedRepository, boolean allowStale) throws IOException {
        var hostedRepositoryInstance = new HostedRepositoryInstance(hostedRepository);
        var repo = hostedRepositoryInstance.seedRepository(allowStale);
        if (!allowStale) {
            fetchWithRetry(repo, hostedRepository.url());
        }
        return repo;
    }
}
