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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.regex.Matcher;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Label;
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
    private final List<Pattern> pullRequestPatterns;

    private JSONValue cachedJSON;
    private List<HostedBranch> branches;

    GitHubRepository(GitHubHost gitHubHost, String repository, JSONValue json) {
        this(gitHubHost, repository);
        cachedJSON = json;
    }

    GitHubRepository(GitHubHost gitHubHost, String repository) {
        this.gitHubHost = gitHubHost;
        this.repository = repository;

        var apiBase = URIBuilder
                .base(gitHubHost.getURI())
                .appendSubDomain("api")
                .setPath("/repos/" + repository + "/")
                .build();
        request = new RestRequest(apiBase, gitHubHost.authId().orElse(null), (r) -> {
            var headers = new ArrayList<>(List.of(
                "X-GitHub-Api-Version", "2022-11-28"));
            var token = gitHubHost.getInstallationToken();
            if (token.isPresent()) {
                headers.add("Authorization");
                headers.add("token " + token.get());
            }
            return headers;
        });
        var urlPatterns = gitHubHost.getAllWebURIs("/" + repository + "/pull/");
        pullRequestPatterns = urlPatterns.stream()
                .map(u -> Pattern.compile(u + "(\\d+)"))
                .toList();
    }

    private JSONValue json() {
        if (cachedJSON == null) {
            cachedJSON = gitHubHost.getProjectInfo(repository)
                    .orElseThrow(() -> new RuntimeException("Repository not found: " + repository));
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
        if (!(target instanceof GitHubRepository upstream)) {
            throw new IllegalArgumentException("target repository must be a GitHub repository");
        }

        var params = JSON.object()
                         .put("title", title)
                         .put("head", group() + ":" + sourceRef)
                         .put("base", targetRef)
                         .put("body", String.join("\n", body))
                         .put("draft", draft);
        var pr = upstream.request.post("pulls")
                                 .body(params)
                                 .execute();

        return new GitHubPullRequest(upstream, pr, upstream.request);
    }

    @Override
    public PullRequest pullRequest(String id) {
        var pr = request.get("pulls/" + id).execute();
        return new GitHubPullRequest(this, pr, request);
    }

    @Override
    public List<PullRequest> pullRequests() {
        return request.get("pulls")
                      .param("state", "all")
                      .param("sort", "updated")
                      .param("direction", "desc")
                      .execute().asArray().stream()
                      .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> openPullRequests() {
        return request.get("pulls")
                      .param("state", "open")
                      .execute().asArray().stream()
                      .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> pullRequestsAfter(ZonedDateTime updatedAfter) {
        return request.get("pulls")
                      .param("state", "all")
                      .param("sort", "updated")
                      .param("direction", "desc")
                      .execute().asArray().stream()
                      .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                      .filter(pr -> !pr.updatedAt().isBefore(updatedAfter))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> openPullRequestsAfter(ZonedDateTime updatedAfter) {
        return request.get("pulls")
                .param("state", "open")
                .execute().asArray().stream()
                .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                .filter(pr -> !pr.updatedAt().isBefore(updatedAfter))
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
        return pullRequestPatterns.stream()
                .map(p -> p.matcher(url))
                .filter(Matcher::find)
                .map(m -> pullRequest(m.group(1)))
                .findAny();
    }

    @Override
    public String name() {
        return repository;
    }

    @Override
    public String group() {
        return repository.split("/")[0];
    }

    @Override
    public URI authenticatedUrl() {
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
        var endpoint = "/" + repository + "/commit/" + hash;
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        var endpoint = "/" + repository + "/compare/" + baseRef + "..." + headRef;
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public URI diffUrl(String prId) {
        var endpoint = "/" + repository + "/pull/" + prId + ".diff";
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public VCS repositoryType() {
        return VCS.GIT;
    }

    @Override
    public URI url() {
        var endpoint = "/" + repository + ".git";
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public Optional<String> fileContents(String filename, String ref) {
        // Get file contents using raw format. This allows us to get files of
        // size up to 100MB (up from 1MB if getting in object from).
        try {
            var content = request.get("contents/" + filename)
                    .param("ref", ref)
                    .header("Accept", "application/vnd.github.raw+json")
                    .executeUnparsed();
            return Optional.of(content);
        } catch (UncheckedRestException e) {
            // The onError handler is not used with executeUnparsed, so have to
            // resort to catching exception for 404 handling.
            // For GitHub, if ref not found, it returns "No commit found for the ref ",
            // if file not found, it returns "Not Found".
            if (e.getStatusCode() == 404 && e.getMessage().contains("Not Found")) {
                return Optional.empty();
            } else {
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeFileContents(String filename, String content, Branch branch, String message, String authorName, String authorEmail, boolean createNewFile) {
        var body = JSON.object()
                .put("message", message)
                .put("branch", branch.name())
                .put("committer", JSON.object()
                        .put("name", authorName)
                        .put("email", authorEmail))
                .put("content", new String(Base64.getEncoder().encode(content.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));

        // If the file exists, we have to supply the current sha with the update request.
        if (!createNewFile) {
            var currentFileData = request.get("contents/" + filename)
                    .param("ref", branch.name())
                    .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                    .execute();
            if (currentFileData.contains("sha")) {
                body.put("sha", currentFileData.get("sha").asString());
            }
        }

        request.put("contents/" + filename)
                .body(body)
                .execute();
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
    public Optional<Hash> branchHash(String ref) {
        var branch = request.get("branches/" + ref)
                .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                .execute();
        if (branch.contains("NOT_FOUND")) {
            return Optional.empty();
        }
        return Optional.of(new Hash(branch.get("commit").get("sha").asString()));
    }

    @Override
    public List<HostedBranch> branches() {
        var branches = request.get("branches").execute();
        return branches.stream()
                       .map(b -> new HostedBranch(b.get("name").asString(),
                                                  new Hash(b.get("commit").get("sha").asString())))
                       .collect(Collectors.toList());
    }

    @Override
    public String defaultBranchName() {
        return json().get("default_branch").asString();
    }

    @Override
    public void protectBranchPattern(String pattern) {
        // This could be implemented using GraphQL, but we currently don't need it for GitHub
        throw new UnsupportedOperationException();
    }

    @Override
    public void unprotectBranchPattern(String pattern) {
        // This could be implemented using GraphQL, but we currently don't need it for GitHub
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteBranch(String ref) {
        request.delete("git/refs/heads/" + ref)
               .execute();
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
    public List<CommitComment> recentCommitComments(ReadOnlyRepository unused, Set<Integer> excludeAuthors,
            List<Branch> branches, ZonedDateTime updatedAfter) {
        var parts = name().split("/");
        var owner = parts[0];
        var name = parts[1];

        var query = String.join("\n", List.of(
            "{",
            "  repository(owner: \"" + owner + "\", name: \"" + name + "\") {",
            "    commitComments(last: 100) {",
            "      nodes {",
            "        createdAt,",
            "        updatedAt,",
            "        author {,",
            "          login,",
            "          __typename,",
            "          ... on Bot {",
            "            databaseId",
            "          },",
            "          ... on User {",
            "            databaseId",
            "          },",
            "        },",
            "        databaseId,",
            "        commit { oid },",
            "        body",
            "      }",
            "    }",
            "  }",
            "}"
        ));

        var data = gitHubHost.graphQL()
                             .post()
                             .body(JSON.object().put("query", query))
                             // This is a single point graphql query so shouldn't need to be limited to once a second
                             .skipLimiter(true)
                             .execute()
                             .get("data");
        var comments = data.get("repository")
                           .get("commitComments")
                           .get("nodes")
                           .stream()
                           .filter(o -> !excludeAuthors.contains(o.get("author").get("databaseId").asInt()))
                           .map(o -> {
                               var hash = new Hash(o.get("commit").get("oid").asString());
                               var createdAt = ZonedDateTime.parse(o.get("createdAt").asString());
                               var updatedAt = ZonedDateTime.parse(o.get("updatedAt").asString());
                               var id = String.valueOf(o.get("databaseId").asInt());
                               var body = o.get("body").asString();
                               var username = o.get("author").get("login").asString();
                               var typename = o.get("author").get("__typename").asString();
                               if (typename.equals("Bot")) {
                                   username += "[bot]";
                               }
                               var userid = o.get("author").get("databaseId").asInt();
                               var user = gitHubHost.hostUser(userid, username);
                               return new CommitComment(hash,
                                                        null,
                                                        -1,
                                                        id,
                                                        body,
                                                        user,
                                                        createdAt,
                                                        updatedAt);
                           })
                           // It's not possible to filter on timestamp in the GraphQL API, but we
                           // can at least filter here to limit the amount of data returned to the
                           // caller.
                           .filter(c -> c.updatedAt().isAfter(updatedAfter))
                           .collect(Collectors.toList());
        Collections.reverse(comments);
        return comments;
    }

    @Override
    public CommitComment addCommitComment(Hash hash, String body) {
        var query = JSON.object().put("body", body);
        var result = request.post("commits/" + hash.hex() + "/comments")
                            .body(query)
                            .execute();
        return toCommitComment(result);
    }

    @Override
    public void updateCommitComment(String id, String body) {
        var query = JSON.object().put("body", body);
        request.patch("comments/" + id)
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

    private Status toStatus(String status) {
        switch (status) {
            case "modified":
                return Status.from('M');
            case "removed":
                return Status.from('D');
            case "added":
                return Status.from('A');
            case "renamed":
                return Status.from('R');
            case "copied":
                return Status.from('C');
            default:
                throw new IllegalArgumentException("Unexpected status: " + status);
        }
    }

    Diff toDiff(Hash from, Hash to, JSONValue files, boolean complete) {
        var patches = new ArrayList<Patch>();

        for (var file : files.asArray()) {
            var status = toStatus(file.get("status").asString());
            var targetPath = Path.of(file.get("filename").asString());
            var sourcePath = status.isRenamed() || status.isCopied() ?
                Path.of(file.get("previous_filename").asString()) :
                targetPath;
            var filetype = FileType.fromOctal("100644");

            var hunks = List.<Hunk>of();
            if (file.contains("patch")) {
                var diff = file.get("patch").asString().split("\n");
                hunks = UnifiedDiffParser.parseSingleFileDiff(diff);
            }

            patches.add(new TextualPatch(sourcePath, filetype, Hash.zero(),
                                         targetPath, filetype, Hash.zero(),
                                         status, hunks));
        }

        return new Diff(from, to, patches, complete);
    }

    @Override
    public Optional<HostedCommit> commit(Hash hash, boolean includeDiffs) {
        var queryBuilder = request.get("commits/" + hash.hex())
                .onError(r -> Optional.of(JSON.of()));
        if (includeDiffs) {
            // Need to specify an explicit per_page < 70 to guarantee that we get patch information in the result set.
            queryBuilder.param("per_page", "50");
        } else {
            // Minimize size of response when diffs aren't needed.
            queryBuilder
                    .param("per_page", "1")
                    .maxPages(1);
        }

        var o = queryBuilder.execute();

        if (o.isNull()) {
            return Optional.empty();
        }

        var metadata = toCommitMetadata(o);
        List<Diff> diffs;
        if (includeDiffs) {
            var totalAdditions = o.get("stats").get("additions").asInt();
            var totalDeletions = o.get("stats").get("deletions").asInt();
            var sumAdditions = 0;
            var sumDeletions = 0;
            for (var patch : o.get("files").asArray()) {
                sumAdditions += patch.get("additions").asInt();
                sumDeletions += patch.get("deletions").asInt();
            }
            var complete = totalAdditions == sumAdditions && totalDeletions == sumDeletions;
            diffs = List.of(toDiff(metadata.parents().get(0), hash, o.get("files"), complete));
        } else {
            diffs = List.of();
        }
        var url = URI.create(o.get("html_url").asString());
        var webUrl = gitHubHost.getWebURI(url.getPath());
        return Optional.of(new HostedCommit(metadata, diffs, url, webUrl));
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
                                 case "cancelled" -> checkBuilder.cancel(completedAt);
                                 case "success" -> checkBuilder.complete(true, completedAt);
                                 case "action_required", "failure", "neutral" -> checkBuilder.complete(false, completedAt);
                                 case "skipped" -> checkBuilder.skipped(completedAt);
                                 default -> throw new IllegalStateException("Unexpected conclusion: " + conclusion);
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

    @Override
    public WorkflowStatus workflowStatus() {
        var workflows = request.get("actions/workflows").execute();
        var count = workflows.asObject().get("total_count").asInt();
        if (count == 0) {
            return WorkflowStatus.NOT_CONFIGURED;
        } else {
            return WorkflowStatus.ENABLED;
        }
    }

    @Override
    public URI webUrl(Branch branch) {
        var endpoint = "/" + repository + "/tree/" + branch.name();
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public URI webUrl(Tag tag) {
        var endpoint = "/" + repository + "/releases/tag/" + tag.name();
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public URI createPullRequestUrl(HostedRepository target, String targetRef, String sourceRef) {
        var sourceGroup = repository.split("/")[0];
        var endpoint = "/" + target.name() + "/pull/" + targetRef + "..." + sourceGroup + ":" + sourceRef;
        return gitHubHost.getWebURI(endpoint);
    }

    @Override
    public List<Collaborator> collaborators() {
        var result = request.get("collaborators")
                .param("affiliation", "direct")
                .execute();
        return result.stream()
                .map(o -> new Collaborator(GitHubHost.toHostUser(o.asObject()), o.get("permissions").get("push").asBoolean()))
                .toList();
    }

    @Override
    public void addCollaborator(HostUser user, boolean canPush) {
        var query = JSON.object().put("permission", canPush ? "push" : "pull");
        request.put("collaborators/" + user.username())
               .body(query)
               .execute();

    }

    @Override
    public void removeCollaborator(HostUser user) {
        request.delete("collaborators/" + user.username()).execute();
    }

    @Override
    public boolean canPush(HostUser user) {
        var permission = request.get("collaborators/" + user.username() + "/permission")
                                .onError(r -> r.statusCode() == 404 ?
                                                  Optional.of(JSON.object().put("permission", "none")) :
                                                  Optional.empty())
                                .execute()
                                .get("permission")
                                .asString();
        return permission.equals("admin") || permission.equals("write");
    }

    @Override
    public void restrictPushAccess(Branch branch, HostUser user) {
        var restrictions =
            JSON.object()
                .put("users", JSON.array().add(user.username()))
                .put("teams", JSON.array())
                .put("apps", JSON.array());
        var query =
            JSON.object()
                .put("required_status_checks", JSON.of())
                .put("enforce_admins", JSON.of())
                .put("required_pull_request_reviews", JSON.of())
                .put("restrictions", restrictions);

        request.put("branches/" + branch.name() + "/protection")
               .body(query)
               .execute();
    }

    @Override
    public List<Label> labels() {
        return request.get("labels")
                      .execute()
                      .stream()
                      .map(o -> new Label(o.get("name").asString(), o.get("description").asString()))
                      .collect(Collectors.toList());
    }

    @Override
    public void addLabel(Label label) {
        var params = JSON.object()
                .put("name", label.name())
                // Color is Gray and matches all current labels
                .put("color", "ededed");
        if (label.description().isPresent()) {
            params.put("description", label.description().get());
        }
        request.post("labels")
                .body(params)
                .execute();
    }

    @Override
    public void updateLabel(Label label) {
        var params = JSON.object();
        if (label.description().isPresent()) {
            params.put("description", label.description().get());
        } else {
            params.put("description", JSONValue.fromNull());
        }
        request.post("labels/" + label.name())
                .body(params)
                .execute();
    }

    @Override
    public void deleteLabel(Label label) {
        request.delete("labels/" + label.name())
                .execute();
    }

    @Override
    public int deleteDeployKeys(Duration age) {
        var expired = request.get("keys").execute()
                .stream()
                .filter(key -> ZonedDateTime.parse(key.get("created_at").asString())
                        .isBefore(ZonedDateTime.now().minus(age)))
                .toList();
        for (var key : expired) {
            request.delete("keys/" + key.get("id")).execute();
        }
        return expired.size();
    }

    @Override
    public boolean canCreatePullRequest(HostUser user) {
        return true;
    }

    @Override
    public List<PullRequest> openPullRequestsWithTargetRef(String targetRef) {
        return request.get("pulls")
                .param("state", "open")
                .param("base", targetRef)
                .execute().asArray().stream()
                .map(jsonValue -> new GitHubPullRequest(this, jsonValue, request))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> deployKeyTitles(Duration age) {
        var parts = name().split("/");
        var owner = parts[0];
        var name = parts[1];

        var query = String.join("\n", List.of(
                "{",
                "  repository(owner: \"" + owner + "\", name: \"" + name + "\") {",
                "    deployKeys(first: 100) {",
                "      edges {",
                "       node{",
                "           id",
                "           title",
                "           createdAt",
                "           }",
                "      }",
                "    }",
                "  }",
                "}"
        ));

        var data = gitHubHost.graphQL()
                .post()
                .body(JSON.object().put("query", query))
                // This is a single point graphql query so shouldn't need to be limited to once a second
                .skipLimiter(true)
                .execute()
                .get("data");
        return data.get("repository").get("deployKeys").get("edges").stream()
                .filter(key -> ZonedDateTime.parse(key.get("node").get("createdAt").asString())
                        .isBefore(ZonedDateTime.now().minus(age)))
                .map(key -> key.get("node").get("title").asString())
                .toList();
    }
}
