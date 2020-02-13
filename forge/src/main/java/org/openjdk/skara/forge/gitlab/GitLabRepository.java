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
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitLabRepository implements HostedRepository {
    private final GitLabHost gitLabHost;
    private final String projectName;
    private final RestRequest request;
    private final JSONValue json;
    private final Pattern mergeRequestPattern;

    public GitLabRepository(GitLabHost gitLabHost, String projectName) {
        this.gitLabHost = gitLabHost;
        json = gitLabHost.getProjectInfo(projectName);
        this.projectName = json.get("path_with_namespace").asString();

        var id = json.get("id").asInt();
        var baseApi = URIBuilder.base(gitLabHost.getUri())
                .setPath("/api/v4/projects/" + id + "/")
                .build();

        request = gitLabHost.getPat()
                            .map(pat -> new RestRequest(baseApi, () -> Arrays.asList("Private-Token", pat.password())))
                            .orElseGet(() -> new RestRequest(baseApi));

        var urlPattern = URIBuilder.base(gitLabHost.getUri())
                                   .setPath("/" + projectName + "/merge_requests/").build();
        mergeRequestPattern = Pattern.compile(urlPattern.toString() + "(\\d+)");
    }

    @Override
    public Forge forge() {
        return gitLabHost;
    }

    @Override
    public Optional<HostedRepository> parent() {
        if (json.contains("forked_from_project")) {
            var parent = json.get("forked_from_project").get("path_with_namespace").asString();
            return Optional.of(new GitLabRepository(gitLabHost, parent));
        }
        return Optional.empty();
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target,
                                         String targetRef,
                                         String sourceRef,
                                         String title,
                                         List<String> body,
                                         boolean draft) {
        if (!(target instanceof GitLabRepository)) {
            throw new IllegalArgumentException("target must be a GitLab repository");
        }

        var pr = request.post("merge_requests")
                        .body("source_branch", sourceRef)
                        .body("target_branch", targetRef)
                        .body("title", draft ? "WIP: " : "" + title)
                        .body("description", String.join("\n", body))
                        .body("target_project_id", Long.toString(target.id()))
                        .execute();

        var targetRepo = (GitLabRepository) target;
        return new GitLabMergeRequest(targetRepo, pr, targetRepo.request);
    }

    @Override
    public PullRequest pullRequest(String id) {
        var pr = request.get("merge_requests/" + id).execute();
        return new GitLabMergeRequest(this, pr, request);
    }

    @Override
    public List<PullRequest> pullRequests() {
        return request.get("merge_requests")
                      .param("state", "opened")
                      .execute().stream()
                      .map(value -> new GitLabMergeRequest(this, value, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> pullRequests(ZonedDateTime updatedAfter) {
        return request.get("merge_requests")
                      .param("order_by", "updated_at")
                      .param("updated_after", updatedAfter.format(DateTimeFormatter.ISO_DATE_TIME))
                      .execute().stream()
                      .map(value -> new GitLabMergeRequest(this, value, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        var matcher = mergeRequestPattern.matcher(url);
        if (matcher.find()) {
            return Optional.of(pullRequest(matcher.group(1)));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return projectName;
    }

    @Override
    public URI url() {
        var builder = URIBuilder
                .base(gitLabHost.getUri())
                .setPath("/" + projectName + ".git");
        gitLabHost.getPat().ifPresent(pat -> builder.setAuthentication(pat.username() + ":" + pat.password()));
        return builder.build();
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(gitLabHost.getUri())
                         .setPath("/" + projectName)
                         .build();
    }

    @Override
    public URI webUrl(Hash hash) {
        return URIBuilder.base(gitLabHost.getUri())
                         .setPath("/" + projectName + "/commit/" + hash.abbreviate())
                         .build();
    }

    @Override
    public VCS repositoryType() {
        return VCS.GIT;
    }

    @Override
    public String fileContents(String filename, String ref) {
        var confName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        var conf = request.get("repository/files/" + confName)
                          .param("ref", ref)
                          .onError(response -> {
                              // Retry once with additional escaping of the path fragment
                              var escapedConfName = URLEncoder.encode(confName, StandardCharsets.UTF_8);
                              return request.get("repository/files/" + escapedConfName)
                                            .param("ref", ref).execute();
                          })
                          .execute();
        var content = Base64.getDecoder().decode(conf.get("content").asString());
        return new String(content, StandardCharsets.UTF_8);
    }

    @Override
    public String namespace() {
        return URIBuilder.base(gitLabHost.getUri()).build().getHost();
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        if (!body.contains("object_kind")) {
            return Optional.empty();
        }
        if (!body.contains("project") || !body.get("project").contains("path_with_namespace")) {
            return Optional.empty();
        }
        if (!body.get("project").get("path_with_namespace").asString().equals(projectName)) {
            return Optional.empty();
        }

        int id = -1;

        if (body.get("object_kind").asString().equals("merge_request")) {
            if (!body.contains("object_attributes") || !body.get("object_attributes").contains("iid")) {
                return Optional.empty();
            }
            id = body.get("object_attributes").get("iid").asInt();
        }

        if (body.contains("merge_request")) {
            if (!body.get("merge_request").contains("iid")) {
                return Optional.empty();
            }
            id = body.get("merge_request").get("iid").asInt();
        }

        if (id != -1) {
            var pr = pullRequest(Integer.toString(id));
            var webHook = new WebHook(List.of(pr));
            return Optional.of(webHook);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public HostedRepository fork() {
        var namespace = gitLabHost.currentUser().userName();
        request.post("fork")
               .body("namespace", namespace)
               .onError(r -> r.statusCode() == 409 ? JSON.object().put("exists", true) : null)
               .execute();
        var nameOnlyStart = projectName.lastIndexOf('/');
        var nameOnly = nameOnlyStart >= 0 ? projectName.substring(nameOnlyStart + 1) : projectName;
        var forkedRepoName = namespace + "/" + nameOnly;
        while (!gitLabHost.isProjectForkComplete(forkedRepoName)) {
            try {
                Thread.sleep(Duration.ofSeconds(1).toMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return gitLabHost.repository(forkedRepoName).orElseThrow(RuntimeException::new);
    }

    @Override
    public long id() {
        return json.get("id").asLong();
    }

    @Override
    public Hash branchHash(String ref) {
        var branch = request.get("repository/branches/" + ref).execute();
        return new Hash(branch.get("commit").get("id").asString());
    }
}
