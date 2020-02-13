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
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitHubRepository implements HostedRepository {
    private final GitHubHost gitHubHost;
    private final String repository;
    private final RestRequest request;
    private final Pattern pullRequestPattern;

    private JSONValue cachedJSON;

    GitHubRepository(GitHubHost gitHubHost, String repository) {
        this.gitHubHost = gitHubHost;
        this.repository = repository;

        var apiBase = URIBuilder
                .base(gitHubHost.getURI())
                .appendSubDomain("api")
                .setPath("/repos/" + repository + "/")
                .build();
        request = new RestRequest(apiBase, () -> {
            var headers = new ArrayList<>(List.of(
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.shadow-cat-preview+json",
                "Accept", "application/vnd.github.comfort-fade-preview+json"));
            var token = gitHubHost.getInstallationToken();
            if (token.isPresent()) {
                headers.add("Authorization");
                headers.add("token " + token.get());
            }
            return headers;
        });
        this.cachedJSON = null;
        var urlPattern = gitHubHost.getWebURI("/" + repository + "/pull/").toString();
        pullRequestPattern = Pattern.compile(urlPattern + "(\\d+)");
    }

    private JSONValue json() {
        if (cachedJSON == null) {
            cachedJSON = gitHubHost.getProjectInfo(repository);
        }
        return cachedJSON;
    }

    @Override
    public Optional<HostedRepository> parent() {
        if (json().get("fork").asBoolean()) {
            var parent = json().get("parent").get("full_name").asString();
            return Optional.of(new GitHubRepository(gitHubHost, parent));
        }
        return Optional.empty();
    }

    @Override
    public Forge forge() {
        return gitHubHost;
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target,
                                         String targetRef,
                                         String sourceRef,
                                         String title,
                                         List<String> body,
                                         boolean draft) {
        if (!(target instanceof GitHubRepository)) {
            throw new IllegalArgumentException("target repository must be a GitHub repository");
        }

        var upstream = (GitHubRepository) target;
        var user = forge().currentUser().userName();
        var namespace = user.endsWith("[bot]") ? "" : user + ":";
        var params = JSON.object()
                         .put("title", title)
                         .put("head", namespace + sourceRef)
                         .put("base", targetRef)
                         .put("body", String.join("\n", body))
                         .put("draft", draft);
        var pr = upstream.request.post("pulls")
                                 .body(params)
                                 .execute();

        return new GitHubPullRequest(upstream, pr, request);
    }

    @Override
    public PullRequest pullRequest(String id) {
        var pr = request.get("pulls/" + id).execute();
        return new GitHubPullRequest(this, pr, request);
    }

    @Override
    public List<PullRequest> pullRequests() {
        return request.get("pulls").execute().asArray().stream()
                      .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> pullRequests(ZonedDateTime updatedAfter) {
        return request.get("pulls")
                      .param("state", "all")
                      .param("sort", "updated")
                      .param("direction", "desc")
                      .maxPages(1)
                      .execute().asArray().stream()
                      .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                      .filter(pr -> pr.updatedAt().isAfter(updatedAfter))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        var query = "\"" + body + "\" in:comments type:pr repo:" + repository;
        if (author != null) {
            query += " commenter:" + author;
        }
        var result = gitHubHost.runSearch(query);
        return result.get("items").stream()
                .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        var matcher = pullRequestPattern.matcher(url);
        if (matcher.find()) {
            return Optional.of(pullRequest(matcher.group(1)));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return repository;
    }

    @Override
    public URI url() {
        var builder = URIBuilder.base(gitHubHost.getURI())
                                .setPath("/" + repository + ".git");
        var token = gitHubHost.getInstallationToken();
        if (token.isPresent()) {
            builder.setAuthentication("x-access-token:" + token.get());
        }
        return builder.build();
    }

    @Override
    public URI webUrl() {
        var endpoint = "/" + repository;
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public URI webUrl(Hash hash) {
        var endpoint = "/" + repository + "/commit/" + hash.abbreviate();
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public VCS repositoryType() {
        return VCS.GIT;
    }

    @Override
    public String fileContents(String filename, String ref) {
        var conf = request.get("contents/" + filename)
                          .param("ref", ref)
                          .execute().asObject();
        // Content may contain newline characters
        return conf.get("content").asString().lines()
                   .map(line -> new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8))
                   .collect(Collectors.joining());
    }

    @Override
    public String namespace() {
        return URIBuilder.base(gitHubHost.getURI()).build().getHost();
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public HostedRepository fork() {
        var response = request.post("forks").execute();
        return gitHubHost.repository(response.get("full_name").asString()).orElseThrow(RuntimeException::new);
    }

    @Override
    public long id() {
        return json().get("id").asLong();
    }

    @Override
    public Hash branchHash(String ref) {
        var branch = request.get("branches/" + ref).execute();
        return new Hash(branch.get("commit").get("sha").asString());
    }
}
