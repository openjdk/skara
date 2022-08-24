package org.openjdk.skara.bots.pr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.openjdk.skara.census.Census;
import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.census.Namespace;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.HostedRepositoryPool;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.jcheck.JCheckConfiguration;

/**
 * The LimitedCensusInstance does not have a Project. Use this when the project
 * may be invalid or unavailable to avoid errors, otherwise use CensusInstance
 */
class LimitedCensusInstance {

    protected final Census census;
    protected final JCheckConfiguration configuration;
    protected final Namespace namespace;

    LimitedCensusInstance(Census census, JCheckConfiguration configuration, Namespace namespace) {
        this.census = census;
        this.configuration = configuration;
        this.namespace = namespace;
    }

    static Optional<LimitedCensusInstance> createLimitedCensusInstance(HostedRepositoryPool hostedRepositoryPool,
            HostedRepository censusRepo, String censusRef, Path folder, HostedRepository repository, String ref,
            HostedRepository confOverrideRepo, String confOverrideName, String confOverrideRef) {
        Path repoFolder = getRepoFolder(hostedRepositoryPool, censusRepo, censusRef, folder);

        try {
            Optional<JCheckConfiguration> configuration = jCheckConfiguration(hostedRepositoryPool,
                    repository, ref, confOverrideRepo, confOverrideName, confOverrideRef);
            if (configuration.isEmpty()) {
                return Optional.empty();
            }
            var census = Census.parse(repoFolder);
            var namespace = namespace(census, repository.namespace());
            return Optional.of(new LimitedCensusInstance(census, configuration.get(), namespace));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse census at " + repoFolder, e);
        }
    }

    private static Namespace namespace(Census census, String hostNamespace) {
        //var namespace = census.namespace(pr.repository().getNamespace());
        var namespace = census.namespace(hostNamespace);
        if (namespace == null) {
            throw new RuntimeException("Namespace not found in census: " + hostNamespace);
        }

        return namespace;
    }

    private static Optional<JCheckConfiguration> configuration(HostedRepositoryPool hostedRepositoryPool, HostedRepository remoteRepo, String name, String ref) throws IOException {
        return hostedRepositoryPool.lines(remoteRepo, Path.of(name), ref).map(JCheckConfiguration::parse);
    }

    private static Optional<JCheckConfiguration> jCheckConfiguration(HostedRepositoryPool hostedRepositoryPool,
            HostedRepository repository, String ref, HostedRepository confOverrideRepo, String confOverrideName,
            String confOverrideRef) throws IOException {
        Optional<JCheckConfiguration> configuration;
        if (confOverrideRepo == null) {
            configuration = configuration(hostedRepositoryPool, repository, ".jcheck/conf", ref);
        } else {
            configuration = configuration(hostedRepositoryPool,
                    confOverrideRepo,
                    confOverrideName,
                    confOverrideRef);
        }
        return configuration;
    }

    private static Path getRepoFolder(HostedRepositoryPool hostedRepositoryPool, HostedRepository censusRepo, String censusRef, Path folder) {
        var repoName = censusRepo.url().getHost() + "/" + censusRepo.name();
        var repoFolder = folder.resolve(URLEncoder.encode(repoName, StandardCharsets.UTF_8));
        try {
            hostedRepositoryPool.checkoutAllowStale(censusRepo, censusRef, repoFolder);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot materialize census to " + repoFolder, e);
        }
        return repoFolder;
    }

    Optional<Contributor> contributor(HostUser hostUser) {
        var contributor = namespace.get(hostUser.id());
        return Optional.ofNullable(contributor);
    }

    Census census() {
        return census;
    }

    JCheckConfiguration configuration() {
        return configuration;
    }

    Namespace namespace() {
        return namespace;
    }
}
