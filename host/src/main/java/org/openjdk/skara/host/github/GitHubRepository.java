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
package org.openjdk.skara.host.github;

import org.openjdk.skara.host.*;
import org.openjdk.skara.host.network.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitHubRepository implements HostedRepository {
    private final GitHubHost gitHubHost;
    private final String repository;
    private final RestRequest request;
    private final JSONValue json;
    private final Pattern pullRequestPattern;

    GitHubRepository(GitHubHost gitHubHost, String repository) {
        this.gitHubHost = gitHubHost;
        this.repository = repository;

        var apiBase = URIBuilder
                .base(gitHubHost.getURI())
                .appendSubDomain("api")
                .setPath("/repos/" + repository + "/")
                .build();
        request = new RestRequest(apiBase, () -> Arrays.asList(
                "Authorization", "token " + gitHubHost.getInstallationToken(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json"));
        json = gitHubHost.getProjectInfo(repository);
        var urlPattern = URIBuilder.base(gitHubHost.getURI())
                .setPath("/" + repository + "/pull/").build();
        pullRequestPattern = Pattern.compile(urlPattern.toString() + "(\\d+)");
    }

    @Override
    public Optional<HostedRepository> getParent() {
        if (json.get("fork").asBoolean()) {
            var parent = json.get("parent").get("full_name").asString();
            return Optional.of(new GitHubRepository(gitHubHost, parent));
        }
        return Optional.empty();
    }

    @Override
    public Host host() {
        return gitHubHost;
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target,
                                         String targetRef,
                                         String sourceRef,
                                         String title,
                                         List<String> body) {
        if (!(target instanceof GitHubRepository)) {
            throw new IllegalArgumentException("target repository must be a GitHub repository");
        }

        var upstream = (GitHubRepository) target;
        var namespace = host().getCurrentUserDetails().userName();
        var pr = upstream.request.post("pulls")
                                 .body("title", title)
                                 .body("head", namespace + ":" + sourceRef)
                                 .body("base", targetRef)
                                 .body("body", String.join("\n", body))
                                 .execute();

        return new GitHubPullRequest(upstream, pr, request);
    }

    @Override
    public PullRequest getPullRequest(String id) {
        var pr = request.get("pulls/" + id).execute();
        return new GitHubPullRequest(this, pr, request);
    }

    @Override
    public List<PullRequest> getPullRequests() {
        return request.get("pulls").execute().asArray().stream()
                      .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                      .collect(Collectors.toList());
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        var matcher = pullRequestPattern.matcher(url);
        if (matcher.find()) {
            return Optional.of(getPullRequest(matcher.group(1)));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public String getName() {
        return repository;
    }

    @Override
    public URI getUrl() {
        return URIBuilder
                .base(gitHubHost.getURI())
                .setPath("/" + repository + ".git")
                .setAuthentication("x-access-token:" + gitHubHost.getInstallationToken())
                .build();
    }

    @Override
    public URI getWebUrl() {
        return URIBuilder.base(gitHubHost.getWebURI())
                         .setPath("/" + repository)
                         .build();    }

    @Override
    public URI getWebUrl(Hash hash) {
        return URIBuilder.base(gitHubHost.getWebURI())
                .setPath("/" + repository + "/commit/" + hash.abbreviate())
                .build();
    }

    @Override
    public VCS getRepositoryType() {
        return VCS.GIT;
    }

    @Override
    public String getFileContents(String filename, String ref) {
        var conf = request.get("contents/" + filename)
                          .param("ref", ref)
                          .execute().asObject();
        // Content may contain newline characters
        return conf.get("content").asString().lines()
                   .map(line -> new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8))
                   .collect(Collectors.joining());
    }

    @Override
    public String getNamespace() {
        return URIBuilder.base(gitHubHost.getURI()).build().getHost();
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public HostedRepository fork() {
        var response = request.post("forks").execute();
        return gitHubHost.getRepository(response.get("full_name").asString());
    }

    @Override
    public long getId() {
        return json.get("id").asLong();
    }

    @Override
    public Hash getBranchHash(String ref) {
        var branch = request.get("branches/" + ref).execute();
        return new Hash(branch.get("commit").get("sha").asString());
    }
}
