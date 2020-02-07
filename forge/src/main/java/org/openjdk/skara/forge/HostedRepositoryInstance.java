package org.openjdk.skara.forge;

import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;

public class HostedRepositoryInstance {
    private final HostedRepository hostedRepository;
    private final Path seed;
    private final String ref;

    private final Logger log = Logger.getLogger("org.openjdk.skara.forge");

    public HostedRepositoryInstance(HostedRepository hostedRepository, Path seedStorage, String ref) {
        this.hostedRepository = hostedRepository;
        this.seed = seedStorage.resolve(hostedRepository.name());
        this.ref = ref;
    }

    private void initializeSeed() throws IOException {
        if (!Files.exists(seed)) {
            var tmpSeedFolder = seed.resolveSibling(seed.getFileName().toString() + "-" + UUID.randomUUID());
            Repository.clone(hostedRepository.url(), tmpSeedFolder, true);
            try {
                Files.move(tmpSeedFolder, seed);
            } catch (IOException e) {
                log.info("Failed to populate seed folder " + seed + " - perhaps due to a benign race. Ignoring..");
            }
        }
    }

    public Repository materialize(Path path) throws IOException {
        var localRepo = hostedRepository.url().getPath().endsWith(".git") ? Repository.init(path, VCS.GIT) : Repository.init(path, VCS.HG);
        if (!localRepo.exists()) {
            initializeSeed();
            return Repository.clone(hostedRepository.url(), path, true, seed);
        }

        if (!localRepo.isHealthy()) {
            var preserveUnhealthy = seed.resolveSibling(seed.getFileName().toString() + "-unhealthy-" + UUID.randomUUID());
            log.severe("Unhealthy local repository detected - preserved in: " + preserveUnhealthy);
            Files.move(localRepo.root(), preserveUnhealthy);
            initializeSeed();
            return Repository.clone(hostedRepository.url(), path, true, seed);
        }

        try {
            localRepo.clean();
        } catch (IOException e) {
            var preserveUnclean = seed.resolveSibling(seed.getFileName().toString() + "-unclean-" + UUID.randomUUID());
            log.severe("Uncleanable local repository detected - preserved in: " + preserveUnclean);
            Files.move(localRepo.root(), preserveUnclean);
            initializeSeed();
            return Repository.clone(hostedRepository.url(), path, true, seed);
        }

        localRepo.fetch(hostedRepository.url(), ref);
        return localRepo;
    }

    public Repository checkout(Path path) throws IOException {
        var localRepo = materialize(path);
        var refHash = localRepo.resolve(ref).orElseThrow();
        try {
            localRepo.checkout(refHash, true);
        } catch (IOException e) {
            var preserveUnchecked = seed.resolveSibling(seed.getFileName().toString() + "-unchecked-" + UUID.randomUUID());
            log.severe("Uncheckoutable local repository detected - preserved in: " + preserveUnchecked);
            Files.move(localRepo.root(), preserveUnchecked);
            initializeSeed();
            localRepo = Repository.clone(hostedRepository.url(), path, true, seed);
            localRepo.checkout(refHash, true);
        }
        return localRepo;
    }

}
