package org.openjdk.skara.forge.github;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitHubForgeFactory implements ForgeFactory {
    @Override
    public String name() {
        return "github";
    }

    @Override
    public Set<String> knownHosts() {
        return Set.of("github.com");
    }

    @Override
    public Forge create(URI uri, Credential credential, JSONObject configuration) {
        Pattern webUriPattern = null;
        String webUriReplacement = null;
        if (configuration != null && configuration.contains("weburl")) {
            webUriPattern = Pattern.compile(configuration.get("weburl").get("pattern").asString());
            webUriReplacement = configuration.get("weburl").get("replacement").asString();
        }

        Set<String> orgs = new HashSet<>();
        if (configuration != null && configuration.contains("orgs")) {
            orgs = configuration.get("orgs")
                                .stream()
                                .map(JSONValue::asString)
                                .collect(Collectors.toSet());
        }

        if (credential != null) {
            if (credential.username().contains(";")) {
                var separator = credential.username().indexOf(";");
                var id = credential.username().substring(0, separator);
                var installation = credential.username().substring(separator + 1);
                var app = new GitHubApplication(credential.password(), id, installation);
                return new GitHubHost(uri, app, webUriPattern, webUriReplacement, orgs);
            } else {
                return new GitHubHost(uri, credential, webUriPattern, webUriReplacement, orgs);
            }
        } else {
            return new GitHubHost(uri, webUriPattern, webUriReplacement, orgs);
        }
    }
}
