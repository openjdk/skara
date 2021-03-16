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
package org.openjdk.skara.forge.gitlab;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.json.JSONValue;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
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

    private void configureSshKey(String userName, String hostName, String sshKey) {
        var cfgPath = Path.of(System.getProperty("user.home"), ".ssh");
        if (!Files.isDirectory(cfgPath)) {
            try {
                Files.createDirectories(cfgPath);
            } catch (IOException ignored) {
            }
        }

        var cfgFile = cfgPath.resolve("config");
        var existing = "";
        try {
            existing = Files.readString(cfgFile, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }

        var userHost = userName + "." + hostName;
        var existingBlock = Pattern.compile("^Match host " + Pattern.quote(userHost) + "(?:\\R[ \\t]+.*)+", Pattern.MULTILINE);
        var existingMatcher = existingBlock.matcher(existing);
        var filtered = existingMatcher.replaceAll("");
        var result = "Match host " + userHost + "\n" +
                "  Hostname " + hostName + "\n" +
                "  PreferredAuthentications publickey\n" +
                "  StrictHostKeyChecking no\n" +
                "  IdentityFile " + sshKey + "\n" +
                "\n";

        try {
            Files.writeString(cfgFile, result + filtered.strip() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        var useSsh = false;
        if (configuration != null && configuration.contains("sshkey") && credential != null) {
            configureSshKey(credential.username(), uri.getHost(), configuration.get("sshkey").asString());
            useSsh = true;
        }
        if (credential != null) {
            return new GitLabHost(name, uri, useSsh, credential, groups);
        } else {
            return new GitLabHost(name, uri, useSsh, groups);
        }
    }
}
