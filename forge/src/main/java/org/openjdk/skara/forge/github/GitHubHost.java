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
package org.openjdk.skara.forge.github;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class GitHubHost implements Forge {
    private final URI uri;
    private final Pattern webUriPattern;
    private final String webUriReplacement;
    private final GitHubApplication application;
    private final Credential pat;
    private final RestRequest request;
    private final RestRequest graphQL;
    private final Duration searchInterval;
    private HostUser currentUser;
    private volatile Instant lastSearch = Instant.now();
    private final Logger log = Logger.getLogger("org.openjdk.skara.forge.github");
    private final Set<String> orgs;

    public GitHubHost(URI uri, GitHubApplication application, Pattern webUriPattern, String webUriReplacement, Set<String> orgs) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.application = application;
        this.pat = null;
        this.orgs = orgs;
        searchInterval = Duration.ofSeconds(3);

        var baseApi = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/")
                .build();

        request = new RestRequest(baseApi, application.authId(), () -> Arrays.asList(
                "Authorization", "token " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.cloak-preview+json"
        ));

        var graphQLAPI = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/graphql")
                .build();
        graphQL = new RestRequest(graphQLAPI, application.authId(), () -> Arrays.asList(
                "Authorization", "bearer " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.shadow-cat-preview+json",
                "Accept", "application/vnd.github.comfort-fade-preview+json"
        ));
    }

    RestRequest graphQL() {
        if (graphQL == null) {
            throw new IllegalStateException("Cannot use GraphQL API without authorization");
        }
        return graphQL;
    }

    public GitHubHost(URI uri, Credential pat, Pattern webUriPattern, String webUriReplacement, Set<String> orgs) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.pat = pat;
        this.application = null;
        this.orgs = orgs;
        searchInterval = Duration.ofSeconds(3);

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi, pat.username(), () -> Arrays.asList(
                "Authorization", "token " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.cloak-preview+json"
        ));

        var graphQLAPI = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/graphql")
                .build();
        graphQL = new RestRequest(graphQLAPI, pat.username(), () -> Arrays.asList(
                "Authorization", "bearer " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.shadow-cat-preview+json",
                "Accept", "application/vnd.github.comfort-fade-preview+json"
        ));
    }

    GitHubHost(URI uri, Pattern webUriPattern, String webUriReplacement, Set<String> orgs) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.pat = null;
        this.application = null;
        this.orgs = orgs;
        searchInterval = Duration.ofSeconds(10);

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi);
        graphQL = null;
    }

    public URI getURI() {
        return uri;
    }

    @Override
    public String name() {
        return "GitHub";
    }

    URI getWebURI(String endpoint) {
        return getWebURI(endpoint, true);
    }

    URI getWebURI(String endpoint, boolean transform) {
        var baseWebUri = URIBuilder.base(uri)
                                   .setPath(endpoint)
                                   .build();

        if (webUriPattern == null || !transform) {
            return baseWebUri;
        }

        var matcher = webUriPattern.matcher(baseWebUri.toString());
        if (!matcher.matches()) {
            return baseWebUri;

        }
        return URIBuilder.base(matcher.replaceAll(webUriReplacement)).build();
    }

    Optional<String> getInstallationToken() {
        if (application != null) {
            return Optional.of(application.getInstallationToken());
        }

        if (pat != null) {
            return Optional.of(pat.password());
        }

        return Optional.empty();
    }

    Optional<String> authId() {
        if (application != null) {
            return Optional.of(application.authId());
        }

        if (pat != null) {
            return Optional.of(pat.username());
        }

        return Optional.empty();
    }

    // Most GitHub API's return user information in this format
    HostUser parseUserField(JSONValue json) {
        return parseUserObject(json.get("user"));
    }

    HostUser parseUserObject(JSONValue json) {
        return hostUser(json.get("id").asInt(), json.get("login").asString());
    }

    HostUser hostUser(int id, String username) {
        return HostUser.builder()
                       .id(id)
                       .username(username)
                       .supplier(() -> user(username).orElseThrow())
                       .build();
    }

    HostUser hostUser(String username) {
        return HostUser.builder()
                       .username(username)
                       .supplier(() -> user(username).orElseThrow())
                       .build();
    }

    @Override
    public boolean isValid() {
        try {
            var endpoints = request.get("")
                                   .executeUnparsed();
            var parsed = JSON.parse(endpoints);
            if (parsed != null && parsed.contains("current_user_url")) {
                return true;
            } else {
                log.fine("Error during GitHub host validation: unexpected endpoint list: " + endpoints);
                return false;
            }
        } catch (IOException e) {
            log.fine("Error during GitHub host validation: " + e);
            return false;
        }
    }

    JSONObject getProjectInfo(String name) {
        var project = request.get("repos/" + name)
                             .execute();
        return project.asObject();
    }

    JSONObject runSearch(String category, String query) {
        // Searches on GitHub uses a special rate limit, so make sure to wait between consecutive searches
        while (true) {
            synchronized (this) {
                if (lastSearch.isBefore(Instant.now().minus(searchInterval))) {
                    lastSearch = Instant.now();
                    break;
                }
            }
            log.fine("Searching too fast - waiting");
            try {
                Thread.sleep(Duration.ofMillis(500).toMillis());
            } catch (InterruptedException ignored) {
            }
        }
        var result = request.get("search/" + category)
                            .param("q", query)
                            .execute();
        return result.asObject();
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        try {
            return Optional.of(new GitHubRepository(this, name));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<HostUser> user(String username) {
        var details = request.get("users/" + URLEncoder.encode(username, StandardCharsets.UTF_8))
                             .onError(r -> Optional.of(JSON.of()))
                             .execute();
        if (details.isNull()) {
            return Optional.empty();
        }

        return Optional.of(toHostUser(details.asObject()));
    }

    private HostUser toHostUser(JSONObject details) {
        // Always present
        var login = details.get("login").asString();
        var id = details.get("id").asInt();

        var name = details.get("name").asString();
        if (name == null) {
            name = login;
        }
        var email = details.get("email").asString();
        return HostUser.builder()
                       .id(id)
                       .username(login)
                       .fullName(name)
                       .email(email)
                       .build();
    }

    @Override
    public HostUser currentUser() {
        if (currentUser == null) {
            if (application != null) {
                var appDetails = application.getAppDetails();
                var appName = appDetails.get("name").asString() + "[bot]";
                currentUser = user(appName).get();
            } else if (pat != null) {
                // Cannot always trust username in PAT, e.g. Git Credential Manager
                // on Windows always return "PersonalAccessToken" as username.
                // Query GitHub for the username instead.
                var details = request.get("user").execute().asObject();
                currentUser = toHostUser(details);
            } else {
                throw new IllegalStateException("No credentials present");
            }
        }
        return currentUser;
    }

    @Override
    public boolean supportsReviewBody() {
        return true;
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        long gid = 0L;
        try {
            gid = Long.parseLong(groupId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Group id is not a number: " + groupId);
        }
        var username = URLEncoder.encode(user.username(), StandardCharsets.UTF_8);
        var orgs = request.get("users/" + username + "/orgs").execute().asArray();
        for (var org : orgs) {
            if (org.get("id").asLong() == gid) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Optional<HostedCommit> search(Hash hash) {
        var orgsToSearch = orgs.stream().map(o -> "org:" + o).collect(Collectors.joining("+"));
        if (!orgsToSearch.isEmpty()) {
            orgsToSearch = "+" + orgsToSearch;
        }
        var result = runSearch("commits", "hash:" + hash.hex() + orgsToSearch);
        var items = result.get("items").asArray();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        var first = items.get(0);
        var repo = repository(first.get("repository").get("full_name").asString());
        return repo.get().commit(hash);
    }
}
