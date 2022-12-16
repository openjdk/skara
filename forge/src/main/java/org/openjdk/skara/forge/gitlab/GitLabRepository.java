/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.util.logging.Logger;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitLabRepository implements HostedRepository {
    private static final Logger log = Logger.getLogger(GitLabRepository.class.getName());
    private final GitLabHost gitLabHost;
    private final String projectName;
    private final RestRequest request;
    private final JSONValue json;
    private final Pattern mergeRequestPattern;
    private final ZonedDateTime instantiated;

    private static final ZonedDateTime EPOCH = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<Hash, Boolean>>> projectsToTitleToHashes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ZonedDateTime> lastCommitUpdates = new ConcurrentHashMap<>();

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
                            .map(pat -> new RestRequest(baseApi, pat.username(), (r) -> Arrays.asList("Private-Token", pat.password())))
                            .orElseGet(() -> new RestRequest(baseApi));

        var urlPattern = URIBuilder.base(gitLabHost.getUri())
                                   .setPath("/" + projectName + "/merge_requests/").build();
        mergeRequestPattern = Pattern.compile(urlPattern.toString() + "(\\d+)");
        instantiated = ZonedDateTime.now();

        projectsToTitleToHashes.putIfAbsent(projectName, new ConcurrentHashMap<>());
        lastCommitUpdates.putIfAbsent(projectName, EPOCH);
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

    // Sometimes GitLab returns merge requests that cannot be acted upon
    private boolean hasHeadHash(JSONValue json) {
        return json.contains("sha") && !json.get("sha").isNull();
    }

    @Override
    public List<PullRequest> pullRequests() {
        return request.get("merge_requests")
                      .param("order_by", "updated_at")
                      .maxPages(1)
                      .execute().stream()
                      .filter(this::hasHeadHash)
                      .map(this::refetchMergeRequest)
                      .map(value -> new GitLabMergeRequest(this, gitLabHost, value, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> openPullRequests() {
        return request.get("merge_requests")
                      .param("state", "opened")
                      .execute().stream()
                      .filter(this::hasHeadHash)
                      .map(this::refetchMergeRequest)
                      .map(value -> new GitLabMergeRequest(this, gitLabHost, value, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> pullRequestsAfter(ZonedDateTime updatedAfter) {
        return request.get("merge_requests")
                      .param("order_by", "updated_at")
                      .param("updated_after", updatedAfter.format(DateTimeFormatter.ISO_DATE_TIME))
                      .maxPages(1)
                      .execute().stream()
                      .filter(this::hasHeadHash)
                      .map(this::refetchMergeRequest)
                      .map(value -> new GitLabMergeRequest(this, gitLabHost, value, request))
                      .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> openPullRequestsAfter(ZonedDateTime updatedAfter) {
        return request.get("merge_requests")
                .param("state", "opened")
                .param("updated_after", updatedAfter.format(DateTimeFormatter.ISO_DATE_TIME))
                .execute().stream()
                .filter(this::hasHeadHash)
                .map(this::refetchMergeRequest)
                .map(value -> new GitLabMergeRequest(this, gitLabHost, value, request))
                .collect(Collectors.toList());
    }

    /**
     * This method is used to work around a bug in GitLab where list query
     * results for merge requests sometimes return stale data. Fetching them
     * directly using the ID will always return up-to-date data. The method
     * logs when stale data is actually detected to give us a way to
     * empirically verify when the bug is no longer present.
     */
    private JSONValue refetchMergeRequest(JSONValue origData) {
        var updatedAt = ZonedDateTime.parse(origData.get("updated_at").asString());
        // Only do the refetch on merge requests that have been updated recently.
        // The 3 hours cut off is rather arbitrarily chosen. We will have to see
        // if it is enough. Having some kind of cut off is reasonable as we would
        // otherwise risk running a lot of queries on the first run after a
        // restart.
        if (updatedAt.isAfter(ZonedDateTime.now().minus(Duration.ofHours(3)))) {
            var id = origData.get("iid");
            var newData = request.get("merge_requests/" + id).execute();
            // We can't compare the full json object returned from a list query
            // and get query call as they will always be different. The part we
            // worry about is the labels, so compare just that.
            JSONValue origLabels = origData.get("labels");
            JSONValue newLabels = newData.get("labels");
            if (!origLabels.equals(newLabels)) {
                log.warning("Possibly stale merge request data received for " + name() + "#" + id
                        + " orig: " + origLabels + " new: " + newLabels);
            }
            return newData;
        } else {
            return origData;
        }
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
        if (gitLabHost.useSsh()) {
            return URI.create("ssh://git@" + gitLabHost.getPat().orElseThrow().username() + "." + gitLabHost.getUri().getHost() + "/" + projectName + ".git");
        } else {
            var builder = URIBuilder
                    .base(gitLabHost.getUri())
                    .setPath("/" + projectName + ".git");
            gitLabHost.getPat().ifPresent(pat -> builder.setAuthentication(pat.username() + ":" + pat.password()));
            return builder.build();
        }
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
                         .setPath("/" + projectName + "/commit/" + hash)
                         .build();
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        return URIBuilder.base(gitLabHost.getUri())
                         .setPath("/" + projectName + "/compare/" + baseRef + "..." + headRef)
                         .build();
    }

    @Override
    public URI diffUrl(String prId) {
        // GitLab is too smart for it's own best and mangles URLs that contain a
        // partial hit with the base MR, hence the double slash.
        return URIBuilder.base(gitLabHost.getUri())
                .setPath("/" + projectName + "/-/merge_requests//" + prId + ".diff")
                .build();
    }

    @Override
    public VCS repositoryType() {
        return VCS.GIT;
    }

    @Override
    public Optional<String> fileContents(String filename, String ref) {
        var encodedFileName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        var content = request.get("repository/files/" + encodedFileName)
                .param("ref", ref)
                .onError(response -> {
                    // Retry once with additional escaping of the path fragment
                    // Only retry when the error is exactly "File Not Found"
                    if (response.statusCode() == 404 && JSON.parse(response.body()).get("message").asString().endsWith("File Not Found")) {
                        log.warning("First time request returned bad status: " + response.statusCode());
                        log.info("First time response body: " + response.body());
                        var doubleEncodedFileName = URLEncoder.encode(encodedFileName, StandardCharsets.UTF_8);
                        return Optional.of(request.get("repository/files/" + doubleEncodedFileName)
                                .param("ref", ref)
                                .onError(r -> r.statusCode() == 404 && JSON.parse(r.body()).get("message").asString().endsWith("File Not Found") ?
                                        Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                                .execute());
                    }
                    return Optional.empty();
                })
                .execute();
        if (content.contains("NOT_FOUND")) {
            return Optional.empty();
        }
        var decodedContent = Base64.getDecoder().decode(content.get("content").asString());
        return Optional.of(new String(decodedContent, StandardCharsets.UTF_8));
    }

    @Override
    public void writeFileContents(String filename, String content, Branch branch, String message, String authorName, String authorEmail) {
        var encodedFileName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        var body = JSON.object()
                .put("commit_message", message)
                .put("branch", branch.name())
                .put("author_name", authorName)
                .put("author_email", authorEmail)
                .put("encoding", "base64")
                .put("content", new String(Base64.getEncoder().encode(content.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        request.put("repository/files/" + encodedFileName)
                .body(body)
                .onError(response -> {
                    // Gitlab requires POST for creating new files and PUT for updating existing.
                    // Retry with POST if we get 400 response.
                    if (response.statusCode() == 400) {
                        return Optional.of(request.post("repository/files/" + encodedFileName)
                                .body(body)
                                .execute());
                    }
                    return Optional.empty();
                })
                .execute();
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
    public Optional<Hash> branchHash(String ref) {
        var branch = request.get("repository/branches/" + ref)
                .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty())
                .execute();
        if (branch.contains("NOT_FOUND")) {
            return Optional.empty();
        }
        return Optional.of(new Hash(branch.get("commit").get("id").asString()));
    }

    @Override
    public List<HostedBranch> branches() {
        var branches = request.get("repository/branches").execute();
        return branches.stream()
                       .map(b -> new HostedBranch(b.get("name").asString(),
                                                  new Hash(b.get("commit").get("id").asString())))
                       .collect(Collectors.toList());
    }

    @Override
    public void deleteBranch(String ref) {
        request.delete("repository/branches/" + URLEncoder.encode(ref, StandardCharsets.US_ASCII))
               .execute();
    }

    private CommitComment toCommitComment(Hash hash, JSONValue o) {
       var line = o.get("line").isNull()? -1 : o.get("line").asInt();
       var path = o.get("path").isNull()? null : Path.of(o.get("path").asString());
       // GitLab does not offer updated_at for commit comments
       var createdAt = ZonedDateTime.parse(o.get("created_at").asString());
       // GitLab does not offer an id for commit comments
       var body = o.get("note").asString();
       var user = gitLabHost.parseAuthorField(o);
       var id = Integer.toString((hash.hex() + createdAt.toString() + user.id()).hashCode());
       return new CommitComment(hash,
                                path,
                                line,
                                id,
                                body,
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

    private Set<Hash> commitsWithTitle(String commitTitle, Map<String, Set<Hash>> commitTitlesToHashes) {
        if (commitTitlesToHashes.containsKey(commitTitle)) {
            return commitTitlesToHashes.get(commitTitle);
        }

        if (commitTitle.endsWith("...")) {
            var candidates = new HashSet<Hash>();
            var prefix = commitTitle.substring(0, commitTitle.length() - "...".length());
            for (var title : commitTitlesToHashes.keySet()) {
                if (title.startsWith(prefix)) {
                    candidates.addAll(commitTitlesToHashes.get(title));
                }
            }
            return candidates;
        }

        return Set.of();
    }

    private Hash commitWithComment(String commitTitle,
                                   ZonedDateTime commentCreatedAt,
                                   HostUser author,
                                   Map<String, Set<Hash>> commitTitleToCommits) {
        var candidates = commitsWithTitle(commitTitle, commitTitleToCommits);
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }

        for (var candidate : candidates) {
            var comments = commitComments(candidate);
            for (var comment : comments) {
                if (comment.createdAt().equals(commentCreatedAt) &&
                    comment.author().equals(author)) {
                    return candidate;
                }
            }
        }

        throw new RuntimeException("Did not find commit with title " + commitTitle + " for repository " + projectName);
    }

    @Override
    public List<CommitComment> recentCommitComments(Map<String, Set<Hash>> commitTitleToCommits, Set<Integer> excludeAuthors) {
        var fourDaysAgo = ZonedDateTime.now().minusDays(4);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        var notes = request.get("events")
                      .param("after", fourDaysAgo.format(formatter))
                      .execute()
                      .stream()
                      .filter(o -> o.contains("note") &&
                                   o.get("note").contains("noteable_type") &&
                                   o.get("note").get("noteable_type").asString().equals("Commit"))
                      .filter(o -> o.contains("target_type") &&
                                   !o.get("target_type").isNull() &&
                                   o.get("target_type").asString().equals("Note"))
                      .filter(o -> o.contains("author") &&
                                   o.get("author").contains("id") &&
                                   !excludeAuthors.contains(o.get("author").get("id").asInt()))
                      .collect(Collectors.toList());

        // Fetch eventual new commits
        var commits = request.get("repository/commits")
                             .param("since", ZonedDateTime.now().minusHours(1).format(DateTimeFormatter.ISO_DATE_TIME))
                             .execute()
                             .asArray();
        for (var commit : commits) {
            var hash = new Hash(commit.get("id").asString());
            var title = commit.get("title").asString();
            if (commitTitleToCommits.containsKey(title)) {
                commitTitleToCommits.get(title).add(hash);
            } else {
                commitTitleToCommits.put(title, Set.of(hash));
            }
        }

        return notes.stream()
                    .map(o -> {
                        var createdAt = ZonedDateTime.parse(o.get("note").get("created_at").asString());
                        var body = o.get("note").get("body").asString();
                        var user = gitLabHost.parseAuthorField(o);
                        var id = o.get("note").get("id").asInt();
                        var hash = commitWithComment(o.get("target_title").asString(),
                                                     createdAt,
                                                     user,
                                                     commitTitleToCommits);
                        return new CommitComment(hash, null, -1, String.valueOf(id), body, user, createdAt, createdAt);
                    })
                    .collect(Collectors.toList());
    }

    @Override
    public CommitComment addCommitComment(Hash hash, String body) {
        var query = JSON.object().put("note", body);
        var result = request.post("repository/commits/" + hash.hex() + "/comments")
                            .body(query)
                            .execute();
        return toCommitComment(hash, result);
    }

    @Override
    public void updateCommitComment(String id, String body) {
        throw new RuntimeException("not implemented yet");
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
            Path sourcePath = null;
            FileType sourceFileType = null;
            Path targetPath = null;
            FileType targetFileType = null;
            Status status = null;

            if (file.get("new_file").asBoolean()) {
                status = Status.from('A');
                targetPath = Path.of(file.get("new_path").asString());
                targetFileType = FileType.fromOctal(file.get("b_mode").asString());
            } else if (file.get("renamed_file").asBoolean()) {
                status = Status.from('R');
                sourcePath = Path.of(file.get("old_path").asString());
                sourceFileType = FileType.fromOctal(file.get("a_mode").asString());
                targetPath = Path.of(file.get("new_path").asString());
                targetFileType = FileType.fromOctal(file.get("b_mode").asString());
            } else if (file.get("deleted_file").asBoolean()) {
                status = Status.from('D');
                sourcePath = Path.of(file.get("old_path").asString());
                sourceFileType = FileType.fromOctal(file.get("a_mode").asString());
            } else {
                status = Status.from('M');
                sourcePath = Path.of(file.get("old_path").asString());
                sourceFileType = FileType.fromOctal(file.get("a_mode").asString());
                targetPath = Path.of(file.get("new_path").asString());
                targetFileType = FileType.fromOctal(file.get("b_mode").asString());
            }

            var diff = file.get("diff").asString();
            var hunks = diff.isEmpty() ?
                new ArrayList<Hunk>() :
                UnifiedDiffParser.parseSingleFileDiff(diff.split("\n"));

            patches.add(new TextualPatch(sourcePath, sourceFileType, Hash.zero(),
                                         targetPath, targetFileType, Hash.zero(),
                                         status, hunks));
        }

        return new Diff(from, to, patches);
    }

    @Override
    public Optional<HostedCommit> commit(Hash hash, boolean includeDiffs) {
        var c = request.get("repository/commits/" + hash.hex())
                       .onError(r -> Optional.of(JSON.of()))
                       .execute();
        if (c.isNull()) {
            return Optional.empty();
        }
        var url = URI.create(c.get("web_url").asString());
        var metadata = toCommitMetadata(c);

        List<Diff> diffs = List.of();
        if (includeDiffs) {
            var diff = request.get("repository/commits/" + hash.hex() + "/diff")
                    .onError(r -> Optional.of(JSON.of()))
                    .execute();
            if (!diff.isNull()) {
                diffs = List.of(toDiff(metadata.parents().get(0), hash, diff));
            }
        }
        return Optional.of(new HostedCommit(metadata, diffs, url));
    }

    @Override
    public List<Check> allChecks(Hash hash) {
        return List.of();
    }

    @Override
    public WorkflowStatus workflowStatus() {
        if (json.contains("jobs_enabled")) {
            return json.get("jobs_enabled").asBoolean() ? WorkflowStatus.ENABLED : WorkflowStatus.DISABLED;
        } else {
            return WorkflowStatus.DISABLED;
        }
    }

    @Override
    public URI webUrl(Branch branch) {
        var endpoint = "/" + projectName + "/-/tree/" + branch.name();
        return gitLabHost.getWebUri(endpoint);
    }

    @Override
    public URI webUrl(Tag tag) {
        var endpoint = "/" + projectName + "/-/tags/" + tag.name();
        return gitLabHost.getWebUri(endpoint);
    }

    @Override
    public URI createPullRequestUrl(HostedRepository target, String targetRef, String sourceRef) {
        var id = json.get("id").asInt();
        var targetId = ((GitLabRepository) target).json.get("id").asInt();
        var endpoint = "/" + projectName + "/-/merge_requests/new?" +
                       "merge_request[source_project_id]=" + id +
                       "&merge_request[source_branch]=" + sourceRef +
                       "&merge_request[target_project]=" + targetId +
                       "&merge_request[target_branch]=" + targetRef;
        return gitLabHost.getWebUri(endpoint);
    }

    @Override
    public void addCollaborator(HostUser user, boolean canPush) {
        var accessLevel = canPush ? "30" : "20";
        request.post("members")
               .body("user_id", user.id())
               .body("access_level", accessLevel)
               .execute();
    }

    @Override
    public boolean canPush(HostUser user) {
        var accessLevel = request.get("members/" + user.id())
                                 .onError(r -> r.statusCode() == 404 ?
                                                   Optional.of(JSON.object().put("access_level", 0)) :
                                                   Optional.empty())
                                 .execute()
                                 .get("access_level")
                                 .asInt();
        return accessLevel >= 30;
    }

    @Override
    public void restrictPushAccess(Branch branch, HostUser user) {
        // Not possible to implement using GitLab Community Edition.
        // Must work around in admin web UI using groups.
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
                // Color is Blue-Gray and matches all current labels
                .put("color", "#428BCA");
        if (label.description().isPresent()) {
            params.put("description", label.description().get());
        }
        request.post("labels")
                .body(params)
                .execute();
    }

    @Override
    public void updateLabel(Label label) {
        var params = JSON.object()
                .put("new_name", label.name());
        if (label.description().isPresent()) {
            params.put("description", label.description().get());
        } else {
            throw new UnsupportedOperationException("Gitlab does not support clearing the description");
        }
        request.put("labels/" + label.name())
                .body(params)
                .execute();
    }

    @Override
    public void deleteLabel(Label label) {
        request.delete("labels/" + label.name())
                .execute();
    }
}
