/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
        var offline = false;
        Set<String> orgs = new HashSet<>();

        if (configuration != null) {
            if (configuration.contains("weburl")) {
                webUriPattern = Pattern.compile(configuration.get("weburl").get("pattern").asString());
                webUriReplacement = configuration.get("weburl").get("replacement").asString();
            }

            if (configuration.contains("offline")) {
                offline = configuration.get("offline").asBoolean();
            }

            if (configuration.contains("orgs")) {
                orgs = configuration.get("orgs")
                    .stream()
                    .map(JSONValue::asString)
                    .collect(Collectors.toSet());
            }
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
            return new GitHubHost(uri, webUriPattern, webUriReplacement, orgs, offline);
        }
    }
}
