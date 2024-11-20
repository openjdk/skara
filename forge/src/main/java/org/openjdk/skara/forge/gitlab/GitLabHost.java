/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class GitLabHost implements Forge {
    private final String name;
    private final URI uri;
    private final boolean useSsh;
    private final Credential pat;
    private final RestRequest request;
    private final Logger log = Logger.getLogger("org.openjdk.skara.forge.gitlab");
    private final List<String> groups;

    private HostUser cachedCurrentUser = null;

    public GitLabHost(String name, URI uri, boolean useSsh, Credential pat, List<String> groups) {
        this.name = name;
        this.uri = uri;
        this.useSsh = useSsh;
        this.pat = pat;
        this.groups = groups;

        var baseApi = URIBuilder.base(uri)
                                .setPath("/api/v4/")
                                .build();
        request = new RestRequest(baseApi, pat.username(), (r) -> Arrays.asList("Private-Token", pat.password()));
    }

    GitLabHost(String name, URI uri, boolean useSsh, List<String> groups) {
        this.name = name;
        this.uri = uri;
        this.useSsh = useSsh;
        this.pat = null;
        this.groups = groups;

        var baseApi = URIBuilder.base(uri)
                                .setPath("/api/v4/")
                                .build();
        request = new RestRequest(baseApi);
    }

    public URI getUri() {
        return uri;
    }

    boolean useSsh() {
        return useSsh;
    }

    Optional<Credential> getPat() {
        return Optional.ofNullable(pat);
    }

    @Override
    public String name() {
        return name;
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

    Optional<JSONObject> getProjectInfo(String name) {
        var encodedName = URLEncoder.encode(name, StandardCharsets.US_ASCII);
        var project = request.get("projects/" + encodedName)
                             .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("retry", true)) : Optional.empty())
                             .execute();
        if (project.contains("retry")) {
            // Depending on web server configuration, GitLab may need double escaping of project names
            encodedName = URLEncoder.encode(encodedName, StandardCharsets.US_ASCII);
            project = request.get("projects/" + encodedName)
                             .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                             .execute();
            if (project.contains("NOT_FOUND")) {
                return Optional.empty();
            }
        }
        return Optional.of(project.asObject());
    }

    Optional<JSONObject> getProjectInfo(int id) {
        var project = request.get("projects/" + id)
                      .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                      .execute();
        if (project.contains("NOT_FOUND")) {
            return Optional.empty();
        } else {
            return Optional.of(project.asObject());
        }
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        return getProjectInfo(name)
                .map(jsonObject -> new GitLabRepository(this, jsonObject));
    }

    HostUser parseAuthorField(JSONValue json) {
        return parseAuthorObject(json.get("author").asObject());
    }

    HostUser parseAuthorObject(JSONObject o) {
        var id = o.get("id").asInt();
        var username = o.get("username").asString();
        var name = o.get("name").asString();
        var email = o.get("email") != null ? o.get("email").asString() : "";
        return HostUser.builder()
                       .id(id)
                       .username(username)
                       .fullName(name)
                       .email(email)
                       .build();
    }

    @Override
    public Optional<HostUser> user(String username) {
        var details = request.get("users")
                             .param("username", username)
                             .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.of()) : Optional.empty())
                             .execute();

        if (details.isNull()) {
            return Optional.empty();
        }

        var users = details.asArray();
        if (users.size() != 1) {
            return Optional.empty();
        }

        return Optional.of(parseAuthorObject(users.get(0).asObject()));
    }

    @Override
    public Optional<HostUser> userById(String id) {
        var details = request.get("users/" + id)
                .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.of()) : Optional.empty())
                .execute();

        if (details.isNull()) {
            return Optional.empty();
        }

        return Optional.of(parseAuthorObject(details.asObject()));
    }


    @Override
    public HostUser currentUser() {
        if (cachedCurrentUser != null) {
            return cachedCurrentUser;
        }
        var details = request.get("user").execute().asObject();
        cachedCurrentUser = parseAuthorObject(details);
        return cachedCurrentUser;
    }

    boolean isProjectForkComplete(String name) {
        var project = getProjectInfo(name);
        if (project.isPresent() && project.get().contains("import_status")) {
            var status = project.get().get("import_status").asString();
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
                             .onError(r -> Optional.of(JSON.of()))
                             .execute();
        return !details.isNull();
    }

    @Override
    public Optional<String> search(Hash hash, boolean includeDiffs) {
        for (var group : groups) {
            var ids = request.get("groups/" + group + "/projects")
                                  .execute()
                                  .stream()
                                  // When searching for a commit, there may be hits in multiple repositories.
                                  // There is no good way of knowing for sure which repository we would rather
                                  // get the commit from, but a reasonable default is to go by the shortest
                                  // path name as that is most likely the main repository of the project.
                                  .sorted(Comparator.comparing(o -> o.get("path").asString().length()))
                                  .map(o -> o.get("id").asInt())
                                  .toList();
            for (var id : ids) {
                var project = new GitLabRepository(this, id);
                var commit = project.commit(hash, includeDiffs);
                if (commit.isPresent()) {
                    return Optional.of(project.name());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public String hostname() {
        return uri.getHost();
    }

    URI getWebUri(String endpoint) {
        return URI.create(uri.toString() + endpoint);
    }

    @Override
    public Duration minTimeStampUpdateInterval() {
        return Duration.ofMinutes(1);
    }

    @Override
    public Duration timeStampQueryPrecision() {
        return Duration.ofSeconds(1);
    }

    @Override
    public List<HostUser> groupMembers(String group) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addGroupMember(String group, HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MemberState groupMemberState(String group, HostUser user) {
        throw new UnsupportedOperationException();
    }
}
