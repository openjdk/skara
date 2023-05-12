package org.openjdk.skara.forge.bitbucket;

import java.net.URI;
import java.util.Optional;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.vcs.Hash;

public class BitbucketHost implements Forge {
    private final String name;
    private final URI uri;
    private final boolean useSsh;
    private final int sshPort;
    private final Credential credential;

    public BitbucketHost(String name, URI uri, boolean useSsh, int sshPort, Credential credential) {
        this.name = name;
        this.uri = uri;
        this.useSsh = useSsh;
        this.sshPort = sshPort;
        this.credential = credential;
    }

    public BitbucketHost(String name, URI uri, boolean useSsh, int sshPort) {
        this.name = name;
        this.uri = uri;
        this.useSsh = useSsh;
        this.sshPort = sshPort;
        this.credential = null;
    }

    @Override
    public String name() {
        return name;
    }

    public URI getUri() {
        return uri;
    }

    public String sshHostString() {
        if (credential == null) {
            throw new IllegalStateException("Cannot use ssh without user name");
        }
        return credential.username() + "." + uri.getHost() + ((sshPort != 22) ? ":" + sshPort : "");
    }

    boolean useSsh() {
        return useSsh;
    }

    Optional<Credential> getCredential() {
        return Optional.ofNullable(credential);
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        return Optional.of(new BitbucketRepository(this, name));
    }

    @Override
    public Optional<HostedCommit> search(Hash hash, boolean includeDiffs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public Optional<HostUser> user(String username) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HostUser currentUser() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String hostname() {
        return uri.getHost();
    }
}
