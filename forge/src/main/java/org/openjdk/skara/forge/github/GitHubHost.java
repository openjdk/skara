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
package org.openjdk.skara.forge.github;

import java.util.stream.Stream;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class GitHubHost implements Forge {
    private final URI uri;
    private final Pattern webUriPattern;
    private final String webUriReplacement;
    private final List<String> altWebUriReplacements;
    private final GitHubApplication application;
    private final Credential pat;
    private final RestRequest request;
    private final RestRequest graphQL;
    private final Duration searchInterval;
    private HostUser currentUser;
    private volatile Instant lastSearch = Instant.now();
    private final Logger log = Logger.getLogger("org.openjdk.skara.forge.github");
    private final Set<String> orgs;
    // If this Forge is created as offline, it will avoid making remote calls
    // when not needed. This is currently limited to only prevent validation
    // when creating a repository object.
    private final boolean offline;

    public GitHubHost(URI uri, GitHubApplication application, Pattern webUriPattern, String webUriReplacement,
            List<String> altWebUriReplacements, Set<String> orgs) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.altWebUriReplacements = altWebUriReplacements;
        this.application = application;
        this.pat = null;
        this.orgs = orgs;
        offline = false;
        searchInterval = Duration.ofSeconds(3);

        var baseApi = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/")
                .build();

        request = new RestRequest(baseApi, application.authId(), (r) -> Arrays.asList(
                "Authorization", "token " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.cloak-preview+json"
        ));

        var graphQLAPI = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/graphql")
                .build();
        graphQL = new RestRequest(graphQLAPI, application.authId(), (r) -> Arrays.asList(
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

    public GitHubHost(URI uri, Credential pat, Pattern webUriPattern, String webUriReplacement,
            List<String> altWebUriReplacements, Set<String> orgs) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.altWebUriReplacements = altWebUriReplacements;
        this.pat = pat;
        this.application = null;
        this.orgs = orgs;
        offline = false;
        searchInterval = Duration.ofSeconds(3);

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi, pat.username(), (r) -> Arrays.asList(
                "Authorization", "token " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.cloak-preview+json"
        ));

        var graphQLAPI = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/graphql")
                .build();
        graphQL = new RestRequest(graphQLAPI, pat.username(), (r) -> Arrays.asList(
                "Authorization", "bearer " + getInstallationToken().orElseThrow(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.shadow-cat-preview+json",
                "Accept", "application/vnd.github.comfort-fade-preview+json"
        ));
    }

    GitHubHost(URI uri, Pattern webUriPattern, String webUriReplacement,
            List<String> altWebUriReplacements, Set<String> orgs, boolean offline) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.altWebUriReplacements = altWebUriReplacements;
        this.pat = null;
        this.application = null;
        this.orgs = orgs;
        this.offline = offline;
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

    @Override
    public String hostname() {
        return uri.getHost();
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

    /**
     * Gets a list of all the alternative URIs for this host for a given endpoint
     * @param endpoint Endpoint to resolve
     * @return List of URIs
     */
    List<URI> getAllWebURIs(String endpoint) {
        var mainURI = getWebURI(endpoint);

        if (altWebUriReplacements.isEmpty()) {
            return List.of(mainURI);
        }
        var baseWebUri = URIBuilder.base(uri)
                .setPath(endpoint)
                .build();

        var matcher = webUriPattern.matcher(baseWebUri.toString());
        if (!matcher.matches()) {
            return List.of(mainURI);
        }

        return Stream.concat(Stream.of(mainURI),
                        altWebUriReplacements.stream()
                                .map(r -> URIBuilder.base(matcher.replaceAll(r)).build()))
                .toList();
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
        } catch (IOException | UncheckedRestException e) {
            log.fine("Error during GitHub host validation: " + e);
            return false;
        }
    }

    Optional<JSONObject> getProjectInfo(String name) {
        var project = request.get("repos/" + name)
                .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                .execute();
        if (project.contains("NOT_FOUND")) {
            return Optional.empty();
        }
        return Optional.of(project.asObject());
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
                Thread.sleep(Duration.ofMillis(500));
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
        if (offline) {
            return Optional.of(new GitHubRepository(this, name));
        } else {
            return getProjectInfo(name)
                    .map(jsonObject -> new GitHubRepository(this, name, jsonObject));
        }
    }

    @Override
    public Optional<HostUser> user(String username) {
        var details = request.get("users/" + URLEncoder.encode(username, StandardCharsets.UTF_8))
                             .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.of()) : Optional.empty())
                             .execute();
        if (details.isNull()) {
            return Optional.empty();
        }

        return Optional.of(toHostUser(details.asObject()));
    }

    @Override
    public Optional<HostUser> userById(String id) {
        var details = request.get("user/" + id)
                .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.of()) : Optional.empty())
                .execute();
        if (details.isNull()) {
            return Optional.empty();
        }

        return Optional.of(toHostUser(details.asObject()));
    }

    /**
     * Gets all members of a GitHub organization.
     */
    @Override
    public List<HostUser> groupMembers(String group) {
        var result = request.get("orgs/" + group + "/members").execute();
        return result.stream().map(o -> toHostUser(o.asObject())).toList();
    }

    /**
     * Gets the membership state of a user in a GitHub organization. Since
     * member invitations need to be accepted by the user, it can be either
     * PENDING or ACTIVE. If the user isn't a member, the state is MISSING.
     */
    @Override
    public MemberState groupMemberState(String group, HostUser user) {
        var result = request.get("orgs/" + group + "/memberships/" + user.username())
                .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("state", "missing")) : Optional.empty())
                .execute();
        var state = result.get("state").asString();
        return switch (state) {
            case "active" -> MemberState.ACTIVE;
            case "pending" -> MemberState.PENDING;
            case "missing" -> MemberState.MISSING;
            default -> throw new IllegalStateException("Unknown state: " + state);
        };
    }

    /**
     * Adds a user to a GitHub organization. This will put the user as PENDING
     * and an invitation is sent to the user. When accepted, the user becomes
     * ACTIVE.
     */
    @Override
    public void addGroupMember(String group, HostUser user) {
        request.put("orgs/" + group + "/memberships/" + user.username())
                .body("role", "member")
                .execute();
    }

    /**
     * Generate a HostUser object from the json snippet. Depending on the source,
     * not all fields may be present.
     */
    static HostUser toHostUser(JSONObject details) {
        // Always present
        var login = details.get("login").asString();
        var id = details.get("id").asInt();
        // Sometimes present
        var name = details.contains("name") ? details.get("name").asString() : login;
        var email = details.contains("email") ? details.get("email").asString() : null;
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
    public Optional<String> search(Hash hash, boolean includeDiffs) {
        var orgsToSearch = orgs.stream().map(o -> "org:" + o).collect(Collectors.joining(" "));
        if (!orgsToSearch.isEmpty()) {
            orgsToSearch = " " + orgsToSearch;
        }
        // /search/commits can only find commits in default branch of each repository
        var result = runSearch("commits", "hash:" + hash.hex() + orgsToSearch);
        var items = result.get("items").asArray();
        if (!items.isEmpty()) {
            // When searching for a commit, there may be hits in multiple repositories.
            // There is no good way of knowing for sure which repository we would rather
            // get the commit from, but a reasonable default is to go by the shortest
            // name as that is most likely the main repository of the project.
            return items.stream()
                    .map(o -> o.get("repository").get("full_name").asString())
                    .min(Comparator.comparing(String::length));
        }

        // If the commit is not found using /search/commits, try to locate it in each repository
        for (var org : orgs) {
            var repoNames = request.get("orgs/" + org + "/repos")
                    .execute()
                    .stream()
                    .sorted(Comparator.comparing(o -> o.get("full_name").asString().length()))
                    .map(o -> o.get("full_name").asString())
                    .toList();

            for (var repoName : repoNames) {
                var githubRepo = new GitHubRepository(this, repoName);
                if (githubRepo.commit(hash).isPresent()) {
                    return Optional.of(repoName);
                }
            }
        }

        return Optional.empty();
    }
}
