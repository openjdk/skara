package org.openjdk.skara.forge.bitbucket;

import java.net.URI;
import java.util.Set;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.ForgeFactory;
import org.openjdk.skara.forge.ForgeUtils;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;

public class BitbucketForgeFactory implements ForgeFactory {
    @Override
    public String name() {
        return "bitbucket";
    }

    @Override
    public Set<String> knownHosts() {
        return Set.of();
    }

    @Override
    public Forge create(URI uri, Credential credential, JSONObject configuration) {
        var name = "Bitbucket";
        if (configuration != null && configuration.contains("name")) {
            name = configuration.get("name").asString();
        }
        var useSsh = false;
        if (configuration != null && configuration.contains("sshkey") && credential != null) {
            ForgeUtils.configureSshKey(credential.username(), uri.getHost(), configuration.get("sshkey").asString());
            useSsh = true;
        }
        int sshport = 22;
        if (configuration != null && configuration.contains("sshport")) {
            sshport = configuration.get("sshport").asInt();
        }
        if (credential != null) {
            return new BitbucketHost(name, uri, useSsh, sshport, credential);
        } else {
            return new BitbucketHost(name, uri, useSsh, sshport);
        }
    }
}
