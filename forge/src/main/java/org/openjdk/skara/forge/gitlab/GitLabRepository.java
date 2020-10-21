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

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitLabRepository implements HostedRepository {
    private final GitLabHost gitLabHost;
    private final String projectName;
    private final RestRequest request;
    private final JSONValue json;
    private final Pattern mergeRequestPattern;

    public GitLabRepository(GitLabHost gitLabHost, String projectName) {
        this(gitLabHost, gitLabHost.getProjectInfo(projectName).orElseThrow(() -> new RuntimeException("Project not found: " + projectName)));
    }

    public GitLabRepository(GitLabHost gitLabHost, int id) {
        this(gitLabHost, gitLabHost.getProjectInfo(id).orElseThrow(() -> new RuntimeException("Project not found by id: " + id)));
    }

    GitLabRepository(GitLabHost gitLabHost, JSONValue json) {
        this.gitLabHost = gitLabHost;
        this.json = json;
        this.projectName = json.get("path_with_namespace").asString();

        var id = json.get("id").asInt();
        var baseApi = URIBuilder.base(gitLabHost.getUri())
                .setPath("/api/v4/projects/" + id + "/")
                .build();

        request = gitLabHost.getPat()
                            .map(pat -> new RestRequest(baseApi, pat.username(), () -> Arrays.asList("Private-Token", pat.password())))
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
        return new GitLabMergeRequest(targetRepo, gitLabHost, pr, targetRepo.request);
    }

    @Override
    public PullRequest pullRequest(String id) {
        var pr = request.get("merge_requests/" + id).execute();
        return new GitLabMergeRequest(this, gitLabHost, pr, request);
    }

    @Override
    public List<PullRequest> pullRequests() {
        return request.get("merge_requests")
                      .param("state", "opened")
                      .execute().stream()
                      .map(value -> new GitLabMergeRequest(this, gitLabHost, value, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> pullRequests(ZonedDateTime updatedAfter) {
        return request.get("merge_requests")
                      .param("order_by", "updated_at")
                      .param("updated_after", updatedAfter.format(DateTimeFormatter.ISO_DATE_TIME))
                      .execute().stream()
                      .map(value -> new GitLabMergeRequest(this, gitLabHost, value, request))
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
    public URI nonTransformedWebUrl() {
        return webUrl();
    }

    @Override
    public URI webUrl(Hash hash) {
        return URIBuilder.base(gitLabHost.getUri())
                         .setPath("/" + projectName + "/commit/" + hash.abbreviate())
                         .build();
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        return URIBuilder.base(gitLabHost.getUri())
                         .setPath("/" + projectName + "/compare/" + baseRef + "..." + headRef)
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
                              return Optional.of(request.get("repository/files/" + escapedConfName)
                                            .param("ref", ref).execute());
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
        var namespace = gitLabHost.currentUser().username();
        request.post("fork")
               .body("namespace", namespace)
               .onError(r -> r.statusCode() == 409 ? Optional.of(JSON.object().put("exists", true)) : Optional.empty())
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

    @Override
    public List<HostedBranch> branches() {
        var branches = request.get("repository/branches").execute();
        return branches.stream()
                       .map(b -> new HostedBranch(b.get("name").asString(),
                                                  new Hash(b.get("commit").get("id").asString())))
                       .collect(Collectors.toList());
    }

    private CommitComment toCommitComment(Hash hash, JSONValue o) {
       var line = o.get("line").isNull()? -1 : o.get("line").asInt();
       var path = o.get("path").isNull()? null : Path.of(o.get("path").asString());
       // GitLab does not offer updated_at for commit comments
       var createdAt = ZonedDateTime.parse(o.get("created_at").asString());
       // GitLab does not offer an id for commit comments
       var id = "";
       return new CommitComment(hash,
                                path,
                                line,
                                id,
                                o.get("note").asString(),
                                gitLabHost.parseAuthorField(o),
                                createdAt,
                                createdAt);
    }

    @Override
    public List<CommitComment> commitComments(Hash hash) {
        return request.get("repository/commits/" + hash.hex() + "/comments")
                      .execute()
                      .stream()
                      .map(o -> toCommitComment(hash, o))
                      .collect(Collectors.toList());
    }

    private Hash commitWithComment(String commitTitle,
                                   String commentBody,
                                   ZonedDateTime commentCreatedAt,
                                   HostUser author) {
        var result = request.get("search")
                            .param("scope", "commits")
                            .param("search", commitTitle)
                            .execute()
                            .stream()
                            .filter(o -> o.get("title").asString().equals(commitTitle))
                            .map(o -> new Hash(o.get("id").asString()))
                            .collect(Collectors.toList());
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No commit with title: " + commitTitle);
        }
        if (result.size() > 1) {
            var filtered = result.stream()
                                 .flatMap(hash -> commitComments(hash).stream()
                                                                      .filter(c -> c.body().equals(commentBody))
                                                                      .filter(c -> c.createdAt().equals(commentCreatedAt))
                                                                      .filter(c -> c.author().equals(author)))
                                 .map(c -> c.commit())
                                 .collect(Collectors.toList());
            if (filtered.isEmpty()) {
                throw new IllegalStateException("No commit with title '" + commitTitle +
                                                "' and comment '" + commentBody + "'");
            }
            if (filtered.size() > 1) {
                var hashes = filtered.stream().map(Hash::hex).collect(Collectors.toList());
                throw new IllegalStateException("Multiple commits with identical comment '" + commentBody + "': "
                                                 + String.join(",", hashes));
            }
            return filtered.get(0);
        }
        return result.get(0);
    }

    @Override
    public List<CommitComment> recentCommitComments() {
        var twoDaysAgo = ZonedDateTime.now().minusDays(2);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-DD");
        return request.get("events")
                      .param("after", twoDaysAgo.format(formatter))
                      .execute()
                      .stream()
                      .filter(o -> o.contains("note") &&
                                   o.get("note").contains("noteable_type") &&
                                   o.get("note").get("noteable_type").asString().equals("Commit"))
                      .map(o -> {
                          var createdAt = ZonedDateTime.parse(o.get("note").get("created_at").asString());
                          var body = o.get("note").get("body").asString();
                          var user = gitLabHost.parseAuthorField(o);
                          var id = o.get("note").get("id").asString();
                          Supplier<Hash> hash = () -> commitWithComment(o.get("target_title").asString(),
                                                                        body,
                                                                        createdAt,
                                                                        user);
                          return new CommitComment(hash, null, -1, id, body, user, createdAt, createdAt);
                      })
                      .collect(Collectors.toList());
    }

    @Override
    public void addCommitComment(Hash hash, String body) {
        var query = JSON.object().put("note", body);
        request.post("repository/commits/" + hash.hex() + "/comments")
               .body(query)
               .execute();
    }

    private CommitMetadata toCommitMetadata(JSONValue o) {
        var hash = new Hash(o.get("id").asString());
        var parents = o.get("parent_ids").stream()
                                      .map(JSONValue::asString)
                                      .map(Hash::new)
                                      .collect(Collectors.toList());
        var author = new Author(o.get("author_name").asString(),
                                o.get("author_email").asString());
        var authored = ZonedDateTime.parse(o.get("authored_date").asString());
        var committer = new Author(o.get("committer_name").asString(),
                                   o.get("committer_email").asString());
        var committed = ZonedDateTime.parse(o.get("committed_date").asString());
        var message = Arrays.asList(o.get("message").asString().split("\n"));
        return new CommitMetadata(hash, parents, author, authored, committer, committed, message);
    }

    Diff toDiff(Hash from, Hash to, JSONValue o) {
        var patches = new ArrayList<Patch>();

        for (var file : o.asArray()) {
            var sourcePath = Path.of(file.get("old_path").asString());
            var sourceFileType = FileType.fromOctal(file.get("a_mode").asString());

            var targetPath = Path.of(file.get("new_path").asString());
            var targetFileType = FileType.fromOctal(file.get("b_mode").asString());

            var status = Status.from('M');
            if (file.get("new_file").asBoolean()) {
                status = Status.from('A');
            } else if (file.get("renamed_file").asBoolean()) {
                status = Status.from('R');
            } else if (file.get("deleted_file").asBoolean()) {
                status = Status.from('D');
            }

            var diff = file.get("diff").asString().split("\n");
            var hunks = UnifiedDiffParser.parseSingleFileDiff(diff);

            patches.add(new TextualPatch(sourcePath, sourceFileType, Hash.zero(),
                                         targetPath, targetFileType, Hash.zero(),
                                         status, hunks));
        }

        return new Diff(from, to, patches);
    }

    @Override
    public Optional<HostedCommit> commit(Hash hash) {
        var c = request.get("repository/commits/" + hash.hex())
                       .onError(r -> Optional.of(JSON.of()))
                       .execute();
        if (!c.isNull()) {
            return Optional.empty();
        }
        var url = URI.create(c.get("web_url").asString());
        var metadata = toCommitMetadata(c);
        var diff = request.get("repository/commits/" + hash.hex() + "/diff")
                          .onError(r -> Optional.of(JSON.of()))
                          .execute();
        var parentDiffs = new ArrayList<Diff>();
        if (!diff.isNull()) {
            parentDiffs.add(toDiff(metadata.parents().get(0), hash, diff));
        }
        return Optional.of(new HostedCommit(metadata, parentDiffs, url));
    }

    @Override
    public List<Check> allChecks(Hash hash) {
        return List.of();
    }
}
