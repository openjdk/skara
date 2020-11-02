package org.openjdk.skara.forge.gitlab;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class GitLabForgeFactory implements ForgeFactory {
    @Override
    public String name() {
        return "gitlab";
    }

    @Override
    public Set<String> knownHosts() {
        return Set.of("gitlab.com");
    }

    @Override
    public Forge create(URI uri, Credential credential, JSONObject configuration) {
        var name = "GitLab";
        if (configuration != null && configuration.contains("name")) {
            name = configuration.get("name").asString();
        }
        Set<String> groups = new HashSet<>();
        if (configuration != null && configuration.contains("groups")) {
            groups = configuration.get("groups")
                                  .stream()
                                  .map(JSONValue::asString)
                                  .collect(Collectors.toSet());
        }
        if (credential != null) {
            return new GitLabHost(name, uri, credential, groups);
        } else {
            return new GitLabHost(name, uri, groups);
        }
    }
}
