/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class GitLabHost implements Forge {
    private final URI uri;
    private final Credential pat;
    private final RestRequest request;
    private final Logger log = Logger.getLogger("org.openjdk.skara.forge.gitlab");

    GitLabHost(URI uri, Credential pat) {
        this.uri = uri;
        this.pat = pat;

        var baseApi = URIBuilder.base(uri)
                                .setPath("/api/v4/")
                                .build();
        request = new RestRequest(baseApi, () -> Arrays.asList("Private-Token", pat.password()));
    }

    GitLabHost(URI uri) {
        this.uri = uri;
        this.pat = null;

        var baseApi = URIBuilder.base(uri)
                                .setPath("/api/v4/")
                                .build();
        request = new RestRequest(baseApi);
    }

    public URI getUri() {
        return uri;
    }

    Optional<Credential> getPat() {
        return Optional.ofNullable(pat);
    }

    @Override
    public boolean isValid() {
        try {
            var version = request.get("version")
                                  .executeUnparsed();
            var parsed = JSON.parse(version);
            if (parsed != null && parsed.contains("version")) {
                return true;
            } else {
                log.fine("Error during GitLab host validation: unexpected version: " + version);
                return false;
            }
        } catch (IOException e) {
            log.fine("Error during GitLab host validation: " + e);
            return false;
        }
    }

    JSONObject getProjectInfo(String name) {
        var encodedName = URLEncoder.encode(name, StandardCharsets.US_ASCII);

        var project = request.get("projects/" + encodedName)
                                     .onError(r -> r.statusCode() == 404 ? JSON.object().put("retry", true) : null)
                                     .execute();
        if (project.contains("retry")) {
            // Depending on web server configuration, GitLab may need double escaping of project names
            encodedName = URLEncoder.encode(encodedName, StandardCharsets.US_ASCII);
            project = request.get("projects/" + encodedName)
                                     .onError(r -> r.statusCode() == 404 ? JSON.object().put("retry", true) : null)
                                     .execute();
        }
        return project.asObject();
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        try {
            return Optional.of(new GitLabRepository(this, name));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private HostUser parseUserDetails(JSONObject details) {
        var id = details.get("id").asInt();
        var username = details.get("username").asString();
        var name = details.get("name").asString();
        return new HostUser(id, username, name);
    }

    @Override
    public Optional<HostUser> user(String username) {
        var details = request.get("users")
                             .param("username", username)
                             .onError(r -> JSON.of())
                             .execute();

        if (details.isNull()) {
            return Optional.empty();
        }

        var users = details.asArray();
        if (users.size() != 1) {
            return Optional.empty();
        }

        return Optional.of(parseUserDetails(users.get(0).asObject()));
    }

    @Override
    public HostUser currentUser() {
        var details = request.get("user").execute().asObject();
        return parseUserDetails(details);
    }

    @Override
    public boolean supportsReviewBody() {
        // GitLab CE does not support this
        return false;
    }

    boolean isProjectForkComplete(String name) {
        var project = getProjectInfo(name);
        if (project.contains("import_status")) {
            var status = project.get("import_status").asString();
            switch (status) {
                case "finished":
                    return true;
                case "started":
                    return false;
                default:
                    throw new RuntimeException("Unknown fork status: " + status);
            }
        } else {
            throw new RuntimeException("Project does not seem to be a fork");
        }
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        long gid = 0L;
        try {
            gid = Long.parseLong(groupId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Group id is not a number: " + groupId);
        }
        var details = request.get("groups/" + gid + "/members/" + user.id())
                             .onError(r -> JSON.of())
                             .execute();
        return !details.isNull();
    }
}
