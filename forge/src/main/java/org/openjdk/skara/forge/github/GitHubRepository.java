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
import java.nio.file.Path;
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
    private List<HostedBranch> branches;

    GitHubRepository(GitHubHost gitHubHost, String repository) {
        this.gitHubHost = gitHubHost;
        this.repository = repository;

        var apiBase = URIBuilder
                .base(gitHubHost.getURI())
                .appendSubDomain("api")
                .setPath("/repos/" + repository + "/")
                .build();
        request = new RestRequest(apiBase, gitHubHost.authId().orElse(null), () -> {
            var headers = new ArrayList<>(List.of(
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json",
                "Accept", "application/vnd.github.shadow-cat-preview+json",
                "Accept", "application/vnd.github.comfort-fade-preview+json",
                "Accept", "application/vnd.github.mockingbird-preview+json"));
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

    boolean multipleBranches() {
        if (branches == null) {
            branches = branches();
        }
        return branches.size() > 1;
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
        var user = forge().currentUser().username();
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
        var result = gitHubHost.runSearch("issues", query);
        return result.get("items").stream()
                     .map(jsonValue -> jsonValue.get("number").asInt())
                     .map(id -> pullRequest(id.toString()))
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
    public URI nonTransformedWebUrl() {
        var endpoint = "/" + repository;
        return gitHubHost.getWebURI(endpoint, false);
    }

    @Override
    public URI webUrl(Hash hash) {
        var endpoint = "/" + repository + "/commit/" + hash.abbreviate();
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        var endpoint = "/" + repository + "/compare/" + baseRef + "..." + headRef;
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

    @Override
    public List<HostedBranch> branches() {
        var branches = request.get("branches").execute();
        return branches.stream()
                       .map(b -> new HostedBranch(b.get("name").asString(),
                                                  new Hash(b.get("commit").get("sha").asString())))
                       .collect(Collectors.toList());
    }

    private CommitComment toCommitComment(JSONValue o) {
        var hash = new Hash(o.get("commit_id").asString());
        var line = o.get("line").isNull()? -1 : o.get("line").asInt();
        var path = o.get("path").isNull()? null : Path.of(o.get("path").asString());
        return new CommitComment(hash,
                                 path,
                                 line,
                                 o.get("id").toString(),
                                 o.get("body").asString(),
                                 gitHubHost.parseUserField(o),
                                 ZonedDateTime.parse(o.get("created_at").asString()),
                                 ZonedDateTime.parse(o.get("updated_at").asString()));
    }

    @Override
    public List<CommitComment> commitComments(Hash hash) {
        return request.get("commits/" + hash.hex() + "/comments")
                      .execute()
                      .stream()
                      .map(this::toCommitComment)
                      .collect(Collectors.toList());
    }

    @Override
    public List<CommitComment> recentCommitComments() {
        var parts = name().split("/");
        var owner = parts[0];
        var name = parts[1];

        var query = String.join("\n", List.of(
            "query {",
            "    repository(owner: \"" + owner + "\", name: \"" + name + "\") {",
            "        commitComments(last: 200) {",
            "            nodes {",
            "                createdAt",
            "                updatedAt",
            "                author { login }",
            "                databaseId",
            "                commit { oid }",
            "                body",
            "            }",
            "        }",
            "    }",
            "}"
        ));

        var data = gitHubHost.graphQL()
                             .post()
                             .body(JSON.object().put("query", query))
                             .execute()
                             .get("data");
        return data.get("repository")
                   .get("commitComments")
                   .get("nodes")
                   .stream()
                   .map(o -> {
                       var hash = new Hash(o.get("commit").get("oid").asString());
                       var createdAt = ZonedDateTime.parse(o.get("createdAt").asString());
                       var updatedAt = ZonedDateTime.parse(o.get("updatedAt").asString());
                       var id = o.get("databaseId").asString();
                       var body = o.get("body").asString();
                       var user = gitHubHost.hostUser(o.get("login").asString());
                       return new CommitComment(hash,
                                                null,
                                                -1,
                                                id,
                                                body,
                                                user,
                                                createdAt,
                                                updatedAt);
                   })
                   .collect(Collectors.toList());
    }

    @Override
    public void addCommitComment(Hash hash, String body) {
        var query = JSON.object().put("body", body);
        request.post("commits/" + hash.hex() + "/comments")
               .body(query)
               .execute();
    }

    private CommitMetadata toCommitMetadata(JSONValue o) {
        var hash = new Hash(o.get("sha").asString());
        var parents = o.get("parents").stream()
                                      .map(p -> new Hash(p.get("sha").asString()))
                                      .collect(Collectors.toList());
        var commit = o.get("commit").asObject();
        var author = new Author(commit.get("author").get("name").asString(),
                                commit.get("author").get("email").asString());
        var authored = ZonedDateTime.parse(commit.get("author").get("date").asString());
        var committer = new Author(commit.get("committer").get("name").asString(),
                                   commit.get("committer").get("email").asString());
        var committed = ZonedDateTime.parse(commit.get("committer").get("date").asString());
        var message = Arrays.asList(commit.get("message").asString().split("\n"));
        return new CommitMetadata(hash, parents, author, authored, committer, committed, message);
    }

    Diff toDiff(Hash from, Hash to, JSONValue files) {
        var patches = new ArrayList<Patch>();

        for (var file : files.asArray()) {
            var status = Status.from(file.get("status").asString().toUpperCase().charAt(0));
            var targetPath = Path.of(file.get("filename").asString());
            var sourcePath = status.isRenamed() || status.isCopied() ?
                Path.of(file.get("previous_filename").asString()) :
                targetPath;
            var filetype = FileType.fromOctal("100644");

            var diff = file.get("patch").asString().split("\n");
            var hunks = UnifiedDiffParser.parseSingleFileDiff(diff);

            patches.add(new TextualPatch(sourcePath, filetype, Hash.zero(),
                                         targetPath, filetype, Hash.zero(),
                                         status, hunks));
        }

        return new Diff(from, to, patches);
    }

    @Override
    public Optional<HostedCommit> commit(Hash hash) {
        var o = request.get("commits/" + hash.hex())
                       .onError(r -> Optional.of(JSON.of()))
                       .execute();
        if (o.isNull()) {
            return Optional.empty();
        }

        var metadata = toCommitMetadata(o);
        var diffs = toDiff(metadata.parents().get(0), hash, o.get("files"));
        return Optional.of(new HostedCommit(metadata, List.of(diffs), URI.create(o.get("html_url").asString())));
    }

    @Override
    public List<Check> allChecks(Hash hash) {
        var checks = request.get("commits/" + hash.hex() + "/check-runs").execute();

        return checks.get("check_runs").stream()
                     .map(c -> {
                         var checkBuilder = CheckBuilder.create(c.get("name").asString(), new Hash(c.get("head_sha").asString()));
                         checkBuilder.startedAt(ZonedDateTime.parse(c.get("started_at").asString()));

                         var completed = c.get("status").asString().equals("completed");
                         if (completed) {
                             var conclusion = c.get("conclusion").asString();
                             var completedAt = ZonedDateTime.parse(c.get("completed_at").asString());
                             switch (conclusion) {
                                 case "cancelled":
                                 case "skipped":
                                     checkBuilder.cancel(completedAt);
                                     break;
                                 case "success":
                                     checkBuilder.complete(true, completedAt);
                                     break;
                                 case "failure":
                                     // fallthrough
                                 case "neutral":
                                     checkBuilder.complete(false, completedAt);
                                     break;
                                 default:
                                     throw new IllegalStateException("Unexpected conclusion: " + conclusion);
                             }
                         }
                         if (c.contains("external_id")) {
                             checkBuilder.metadata(c.get("external_id").asString());
                         }
                         if (c.contains("output")) {
                             var output = c.get("output").asObject();
                             if (output.contains("title")) {
                                 checkBuilder.title(output.get("title").asString());
                             }
                             if (output.contains("summary")) {
                                 checkBuilder.summary(output.get("summary").asString());
                             }
                         }
                         if (c.contains("details_url")) {
                             checkBuilder.details(URI.create(c.get("details_url").asString()));
                         }

                         return checkBuilder.build(); }
                     )
                     .collect(Collectors.toList());
    }
}
