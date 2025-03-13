/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.Diff;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GitHubPullRequest implements PullRequest {
    private final JSONValue json;
    private final RestRequest request;
    private final GitHubHost host;
    private final GitHubRepository repository;
    private final Logger log = Logger.getLogger("org.openjdk.skara.host");

    private List<Label> labels = null;

    private static final int GITHUB_PR_COMMENT_BODY_MAX_SIZE = 64_000;

    GitHubPullRequest(GitHubRepository repository, JSONValue jsonValue, RestRequest request) {
        this.host = (GitHubHost)repository.forge();
        this.repository = repository;
        this.request = request;
        this.json = jsonValue;

        labels = json.get("labels")
                     .stream()
                     .map(v -> new Label(v.get("name").asString(), v.get("description").asString()))
                     .sorted()
                     .collect(Collectors.toList());
    }

    @Override
    public HostedRepository repository() {
        return repository;
    }

    @Override
    public IssueProject project() {
        return null;
    }

    @Override
    public String id() {
        return json.get("number").toString();
    }

    @Override
    public HostUser author() {
        return host.parseUserField(json);
    }

    @Override
    public List<Review> reviews() {
        var currentTargetRef = targetRef();
        var reviews = request.get("pulls/" + json.get("number").toString() + "/reviews")
                .param("per_page", "100").execute().stream()
                .map(JSONValue::asObject)
                .filter(obj -> !(obj.get("state").asString().equals("COMMENTED") && obj.get("body").asString().isEmpty()))
                .map(obj -> {
                    var reviewer = host.parseUserField(obj);
                    var commitId = obj.get("commit_id");
                    Hash hash = null;
                    if (commitId != null) {
                        hash = new Hash(commitId.asString());
                    }
                    Review.Verdict verdict;
                    switch (obj.get("state").asString()) {
                        case "APPROVED":
                            verdict = Review.Verdict.APPROVED;
                            break;
                        case "CHANGES_REQUESTED":
                            verdict = Review.Verdict.DISAPPROVED;
                            break;
                        default:
                            verdict = Review.Verdict.NONE;
                            break;
                    }
                    var id = obj.get("id").toString();
                    var body = obj.get("body").asString();
                    var createdAt = ZonedDateTime.parse(obj.get("submitted_at").asString());
                    return new Review(createdAt, reviewer, verdict, hash, id, body, currentTargetRef);
                })
                .collect(Collectors.toList());

        var targetRefChanges = targetRefChanges();
        return PullRequest.calculateReviewTargetRefs(reviews, targetRefChanges);
    }

    @Override
    public List<ReferenceChange> targetRefChanges() {
        // If the base ref has changed after a review, we treat those as invalid - unless it was a PreIntegration ref
        var parts = repository.name().split("/");
        var owner = parts[0];
        var name = parts[1];
        var number = id();

        var query = "{\n" +
                "  repository(owner: \"" + owner + "\", name: \"" + name + "\") {\n" +
                "    pullRequest(number: " + number + ") {\n" +
                "      timelineItems(itemTypes: BASE_REF_CHANGED_EVENT, last: 10) {\n" +
                "        nodes {\n" +
                "          __typename\n" +
                "          ... on BaseRefChangedEvent {\n" +
                "            currentRefName,\n" +
                "            previousRefName,\n" +
                "            createdAt\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        var data = host.graphQL()
                .post()
                .body(JSON.object().put("query", query))
                // This is a single point graphql query so shouldn't need to be limited to once a second
                .skipLimiter(true)
                .execute()
                .get("data");
        return data.get("repository").get("pullRequest").get("timelineItems").get("nodes").stream()
                .map(JSONValue::asObject)
                .map(obj -> new ReferenceChange(obj.get("previousRefName").asString(), obj.get("currentRefName").asString(),
                        ZonedDateTime.parse(obj.get("createdAt").asString())))
                .toList();
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
        var query = JSON.object();
        switch (verdict) {
            case APPROVED:
                query.put("event", "APPROVE");
                break;
            case DISAPPROVED:
                query.put("event", "REQUEST_CHANGES");
                break;
            case NONE:
                query.put("event", "COMMENT");
                break;
        }
        if (body != null && !body.isEmpty()) {
            query.put("body", body);
        }
        query.put("commit_id", headHash().hex());
        query.put("comments", JSON.array());
        request.post("pulls/" + json.get("number").toString() + "/reviews")
               .body(query)
               .execute();
    }

    @Override
    public void updateReview(String id, String body) {
        request.put("pulls/" + json.get("number").toString() + "/reviews/" + id)
               .body("body", body)
               .execute();
    }

    private ReviewComment parseReviewComment(ReviewComment parent, JSONObject reviewJson, boolean includeLocationData) {
        var author = host.parseUserField(reviewJson);
        var threadId = parent == null ? reviewJson.get("id").toString() : parent.threadId();

        int line = reviewJson.get("original_line").asInt();
        var originalCommitId = reviewJson.get("original_commit_id");
        Hash hash = null;
        if (originalCommitId != null) {
            hash = new Hash(originalCommitId.asString());
        }
        var path = reviewJson.get("path").asString();

        if (includeLocationData && reviewJson.get("side").asString().equals("LEFT")) {
            var commitInfo = request.get("commits/" + hash).execute();

            // If line is present, it indicates the line in the merge-base commit
            if (!reviewJson.get("line").isNull()) {
                hash = new Hash(json.get("base").get("sha").asString());
                line = reviewJson.get("line").asInt();
            } else {
                hash = new Hash(commitInfo.get("parents").asArray().get(0).get("sha").asString());
            }

            // It's possible that the file in question was renamed / deleted in an earlier commit, would
            // need to parse all the commits in the PR to be sure. But this should cover most cases.
            for (var file : commitInfo.get("files").asArray()) {
                if (file.get("filename").asString().equals(path)) {
                    if (file.get("status").asString().equals("renamed")) {
                        path = file.get("previous_filename").asString();
                    }
                    break;
                }
            }
        }

        var comment = new ReviewComment(parent,
                                        threadId,
                                        hash,
                                        path,
                                        line,
                                        reviewJson.get("id").toString(),
                                        reviewJson.get("body").asString(),
                                        author,
                                        ZonedDateTime.parse(reviewJson.get("created_at").asString()),
                                        ZonedDateTime.parse(reviewJson.get("updated_at").asString()));
        return comment;
    }

    @Override
    public ReviewComment addReviewComment(Hash base, Hash hash, String path, int line, String body) {
        var query = JSON.object()
                        .put("body", body)
                        .put("commit_id", hash.hex())
                        .put("path", path)
                        .put("side", "RIGHT")
                        .put("line", line);
        var response = request.post("pulls/" + json.get("number").toString() + "/comments")
                              .body(query)
                              .execute();
        return parseReviewComment(null, response.asObject(), true);
    }

    @Override
    public ReviewComment addReviewCommentReply(ReviewComment parent, String body) {
        var query = JSON.object()
                        .put("body", body)
                        .put("in_reply_to", Integer.parseInt(parent.threadId()));
        var response = request.post("pulls/" + json.get("number").toString() + "/comments")
                              .body(query)
                              .execute();
        return parseReviewComment(parent, response.asObject(), true);
    }

    private List<ReviewComment> reviewComments(boolean includeLocationData) {
        var ret = new ArrayList<ReviewComment>();
        var reviewComments = request.get("pulls/" + json.get("number").toString() + "/comments")
                .param("per_page", "100").execute().stream()
                .map(JSONValue::asObject)
                .collect(Collectors.toList());
        var idToComment = new HashMap<String, ReviewComment>();

        for (var reviewComment : reviewComments) {
            ReviewComment parent = null;
            if (reviewComment.contains("in_reply_to_id")) {
                parent = idToComment.get(reviewComment.get("in_reply_to_id").toString());
            }
            var comment = parseReviewComment(parent, reviewComment, includeLocationData);
            idToComment.put(comment.id(), comment);
            ret.add(comment);
        }

        return ret;
    }

    @Override
    public List<ReviewComment> reviewComments() {
        return reviewComments(true);
    }

    @Override
    public List<? extends Comment> reviewCommentsAsComments() {
        return reviewComments(false);
    }

    @Override
    public Hash headHash() {
        return new Hash(json.get("head").get("sha").asString());
    }

    @Override
    public String fetchRef() {
        return "pull/" + id() + "/head";
    }

    @Override
    public String sourceRef() {
        return json.get("head").get("ref").asString();
    }

    @Override
    public Optional<HostedRepository> sourceRepository() {
        if (json.get("head").get("repo").isNull()) {
            return Optional.empty();
        } else {
            return Optional.of(new GitHubRepository(host, json.get("head").get("repo").get("full_name").asString()));
        }
    }

    @Override
    public String targetRef() {
        return json.get("base").get("ref").asString();
    }

    @Override
    public String title() {
        return json.get("title").asString().strip();
    }

    @Override
    public void setTitle(String title) {
        request.patch("pulls/" + json.get("number").toString())
               .body("title", title)
               .execute();
    }

    @Override
    public String body() {
        var body = json.get("body").asString();
        if (body == null) {
            body = "";
        }
        return body;
    }

    @Override
    public void setBody(String body) {
        request.patch("pulls/" + json.get("number").toString())
               .body("body", body)
               .execute();
    }

    private Comment parseComment(JSONValue comment) {
        var ret = new Comment(comment.get("id").toString(),
                              comment.get("body").asString(),
                              host.parseUserField(comment),
                              ZonedDateTime.parse(comment.get("created_at").asString()),
                              ZonedDateTime.parse(comment.get("updated_at").asString()));
        return ret;
    }

    @Override
    public List<Comment> comments() {
        return request.get("issues/" + json.get("number").toString() + "/comments")
                .param("per_page", "100").execute().stream()
                .map(this::parseComment)
                .collect(Collectors.toList());
    }

    @Override
    public Comment addComment(String body) {
        body = limitBodySize(body);
        var comment = request.post("issues/" + json.get("number").toString() + "/comments")
                .body("body", body)
                .execute();
        return parseComment(comment);
    }

    @Override
    public void removeComment(Comment comment) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Comment updateComment(String id, String body) {
        body = limitBodySize(body);
        var comment = request.patch("issues/comments/" + id)
                             .body("body", body)
                             .onError(r -> {
                                 if (r.statusCode() == 404) {
                                     return Optional.of(JSON.object().put("NOT_FOUND", true));
                                 }
                                 throw new RuntimeException("Invalid response");
                             })
                             .execute();
        if (comment.contains("NOT_FOUND")) {
            var reviewComment = request.patch("pulls/comments/" + id)
                                       .body("body", body)
                                       .execute();
            return parseReviewComment(null, reviewComment.asObject(), false);
        }
        return parseComment(comment);
    }

    @Override
    public ZonedDateTime createdAt() {
        return ZonedDateTime.parse(json.get("created_at").asString());
    }

    @Override
    public ZonedDateTime updatedAt() {
        return ZonedDateTime.parse(json.get("updated_at").asString());
    }

    @Override
    public State state() {
        if (json.get("state").asString().equals("open")) {
            return State.OPEN;
        }
        return State.CLOSED;
    }

    @Override
    public Map<String, Check> checks(Hash hash) {
        var checks = request.get("commits/" + hash.hex() + "/check-runs").execute();

        return checks.get("check_runs").stream()
                .collect(Collectors.toMap(c -> c.get("name").asString(),
                        c -> {
                            var checkBuilder = CheckBuilder.create(c.get("name").asString(), new Hash(c.get("head_sha").asString()));
                            checkBuilder.startedAt(ZonedDateTime.parse(c.get("started_at").asString()));

                            var completed = c.get("status").asString().equals("completed");
                            if (completed) {
                                var conclusion = c.get("conclusion").asString();
                                var completedAtString = c.get("completed_at").asString();
                                var completedAt = completedAtString != null ? ZonedDateTime.parse(completedAtString) : null;
                                switch (conclusion) {
                                    case "cancelled" -> checkBuilder.cancel(completedAt);
                                    case "success" -> checkBuilder.complete(true, completedAt);
                                    case "action_required", "failure", "neutral" -> checkBuilder.complete(false, completedAt);
                                    case "skipped" -> checkBuilder.skipped(completedAt);
                                    case "stale" -> checkBuilder.stale();
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

                            return checkBuilder.build();
                        }, (a, b) -> b));
    }

    @Override
    public void createCheck(Check check) {
        // update and create are currently identical operations, both do an HTTP
        // POST to the /repos/:owner/:repo/check-runs endpoint. There is an additional
        // endpoint explicitly for updating check-runs, but that is not currently used.
        updateCheck(check);
    }

    @Override
    public void updateCheck(Check check) {
        var completedQuery = JSON.object();
        completedQuery.put("name", check.name());
        completedQuery.put("head_branch", json.get("head").get("ref"));
        completedQuery.put("head_sha", check.hash().hex());

        if (check.title().isPresent() && check.summary().isPresent()) {
            var outputQuery = JSON.object();
            outputQuery.put("title", check.title().get());
            outputQuery.put("summary", check.summary().get());

            var annotations = JSON.array();
            for (var annotation : check.annotations().subList(0, Math.min(check.annotations().size(), 50))) {
                var annotationQuery = JSON.object();
                annotationQuery.put("path", annotation.path());
                annotationQuery.put("start_line", annotation.startLine());
                annotationQuery.put("end_line", annotation.endLine());
                annotation.startColumn().ifPresent(startColumn -> annotationQuery.put("start_column", startColumn));
                annotation.endColumn().ifPresent(endColumn -> annotationQuery.put("end_column", endColumn));
                switch (annotation.level()) {
                    case NOTICE:
                        annotationQuery.put("annotation_level", "notice");
                        break;
                    case WARNING:
                        annotationQuery.put("annotation_level", "warning");
                        break;
                    case FAILURE:
                        annotationQuery.put("annotation_level", "failure");
                        break;
                }

                annotationQuery.put("message", annotation.message());
                annotation.title().ifPresent(title -> annotationQuery.put("title", title));
                annotations.add(annotationQuery);
            }

            outputQuery.put("annotations", annotations);
            completedQuery.put("output", outputQuery);
        }

        if (check.status() == CheckStatus.IN_PROGRESS) {
            completedQuery.put("status", "in_progress");
        } else {
            completedQuery.put("status", "completed");
            completedQuery.put("conclusion", check.status().name().toLowerCase());
            completedQuery.put("completed_at", check.completedAt().orElse(ZonedDateTime.now(ZoneOffset.UTC))
                    .format(DateTimeFormatter.ISO_INSTANT));
        }

        completedQuery.put("started_at", check.startedAt().format(DateTimeFormatter.ISO_INSTANT));
        check.metadata().ifPresent(metadata -> completedQuery.put("external_id", metadata));

        request.post("check-runs").body(completedQuery).execute();
    }

    @Override
    public URI changeUrl() {
        return URIBuilder.base(webUrl()).appendPath("/files").build();
    }

    @Override
    public URI changeUrl(Hash base) {
        return URIBuilder.base(webUrl()).appendPath("/files/" + base.abbreviate() + ".." + headHash().abbreviate()).build();
    }

    @Override
    public URI commentUrl(Comment comment) {
        return URIBuilder.base(webUrl()).appendPath("#issuecomment-" + comment.id()).build();
    }

    @Override
    public URI reviewCommentUrl(ReviewComment reviewComment) {
        return URIBuilder.base(webUrl()).appendPath("#discussion_r" + reviewComment.id()).build();
    }

    @Override
    public URI reviewUrl(Review review) {
        return URIBuilder.base(webUrl()).appendPath("#pullrequestreview-" + review.id()).build();
    }

    @Override
    public boolean isDraft() {
        return json.get("draft").asBoolean();
    }

    @Override
    public void setState(State state) {
        request.patch("pulls/" + json.get("number").toString())
               .body("state", state != State.OPEN ? "closed" : "open")
               .execute();
    }

    @Override
    public void addLabel(String label) {
        labels = null;
        var query = JSON.object().put("labels", JSON.array().add(label));
        request.post("issues/" + json.get("number").toString() + "/labels")
               .body(query)
               .execute();
    }

    @Override
    public void removeLabel(String label) {
        labels = null;
        request.delete("issues/" + json.get("number").toString() + "/labels/" + label)
               .onError(r -> {
                   // The GitHub API explicitly states that 404 is the response for deleting labels currently not set
                   if (r.statusCode() == 404) {
                       return Optional.of(JSONValue.fromNull());
                   }
                   throw new RuntimeException("Invalid response");
               })
               .execute();
    }

    @Override
    public void setLabels(List<String> labels) {
        var labelArray = JSON.array();
        for (var label : labels) {
            labelArray.add(label);
        }
        var query = JSON.object().put("labels", labelArray);
        var newLabels = request.put("issues/" + json.get("number").toString() + "/labels")
                               .body(query)
                               .execute()
                               .stream()
                               .map(o -> new Label(o.get("name").asString(), o.get("description").asString()))
                               .collect(Collectors.toList());
        this.labels = newLabels;
    }

    @Override
    public List<Label> labels() {
        if (labels == null) {
            labels = request.get("issues/" + json.get("number").toString() + "/labels").execute().stream()
                            .map(JSONValue::asObject)
                            .map(obj -> new Label(obj.get("name").asString(), obj.get("description").asString()))
                            .sorted()
                            .collect(Collectors.toList());
        }
        return labels;
    }

    private URI getWebUrl(boolean transform) {
        var host = (GitHubHost)repository.forge();
        var endpoint = "/" + repository.name() + "/pull/" + id();
        return host.getWebURI(endpoint, transform);
    }

    @Override
    public URI webUrl() {
        return getWebUrl(true);
    }

    @Override
    public URI nonTransformedWebUrl() {
        return getWebUrl(false);
    }

    @Override
    public String toString() {
        return "GitHubPullRequest #" + id() + " by " + author();
    }

    @Override
    public List<HostUser> assignees() {
        return json.get("assignees").asArray()
                                    .stream()
                                    .map(host::parseUserObject)
                                    .collect(Collectors.toList());
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        var assignee_ids = JSON.array();
        for (var assignee : assignees) {
            assignee_ids.add(assignee.username());
        }
        var param = JSON.object().put("assignees", assignee_ids);
        request.patch("issues/" + json.get("number").toString()).body(param).execute();
    }

    @Override
    public void makeNotDraft() {
        if (!isDraft()) {
            return;
        }

        var parts = repository.name().split("/");
        var owner = parts[0];
        var name = parts[1];
        var number = id();

        var query = String.join("\n", List.of(
            "query {",
            "    repository(owner: \"" + owner + "\", name: \"" + name + "\") {",
            "        pullRequest(number: " + number + ") {",
            "            id",
            "        }",
            "    }",
            "}"
        ));
        var data = host.graphQL()
                       .post()
                       .body(JSON.object().put("query", query))
                       .execute()
                       .get("data");
        var prId = data.get("repository")
                            .get("pullRequest")
                            .get("id").asString();

        var input = "{pullRequestId:\"" + prId + "\"}";
        // Do not care about the returned PR id, but the markPullRequestReadyForReview
        // mutation requires non-nullable selection.
        var mutation = String.join("\n", List.of(
            "mutation {",
            "    markPullRequestReadyForReview(input: " + input + ") {",
            "        pullRequest {",
            "            id",
            "        }",
            "    }",
            "}"
        ));
        host.graphQL()
            .post()
            .body(JSON.object().put("query", mutation))
            .execute();
    }

    @Override
    public Optional<ZonedDateTime> lastMarkedAsDraftTime() {
        var lastMarkedAsDraftTime = request.get("issues/" + json.get("number").toString() + "/timeline")                .execute().stream()
                .map(JSONValue::asObject)
                .filter(obj -> obj.contains("event"))
                .filter(obj -> obj.get("event").asString().equals("convert_to_draft"))
                .map(obj -> ZonedDateTime.parse(obj.get("created_at").asString()))
                .max(ZonedDateTime::compareTo);
        if (lastMarkedAsDraftTime.isEmpty() && isDraft()) {
            return Optional.of(createdAt());
        }
        return lastMarkedAsDraftTime;
    }

    @Override
    public URI diffUrl() {
        return URI.create(webUrl() + ".diff");
    }

    @Override
    public Optional<ZonedDateTime> labelAddedAt(String label) {
        return request.get("issues/" + json.get("number").toString() + "/timeline")
                      .execute()
                      .stream()
                      .map(JSONValue::asObject)
                      .filter(obj -> obj.contains("event"))
                      .filter(obj -> obj.get("event").asString().equals("labeled"))
                      .filter(obj -> obj.get("label").get("name").asString().equals(label))
                      .map(o -> ZonedDateTime.parse(o.get("created_at").asString()))
                      .findFirst();
    }

    @Override
    public void setTargetRef(String targetRef) {
        request.patch("pulls/" + json.get("number").toString())
               .body("base", targetRef)
               .execute();
    }

    @Override
    public URI headUrl() {
        return URI.create(webUrl() + "/commits/" + headHash().hex());
    }

    @Override
    public Diff diff() {
        // Need to specify an explicit per_page < 70 to guarantee that we get patch information in the result set.
        var files = request.get("pulls/" + json.get("number").toString() + "/files")
                           .param("per_page", "50")
                           .execute();
        var targetHash = repository.branchHash(targetRef()).orElseThrow();
        var complete = files.asArray().size() == json.get("changed_files").asInt();
        return repository.toDiff(targetHash, headHash(), files, complete);
    }

    @Override
    public Optional<HostUser> closedBy() {
        if (!isClosed()) {
            return Optional.empty();
        }

        return request.get("issues/" + json.get("number").toString() + "/timeline")
                      .execute()
                      .stream()
                      .map(JSONValue::asObject)
                      .filter(obj -> obj.contains("event"))
                      .filter(obj -> obj.get("event").asString().equals("closed"))
                      .max(Comparator.comparing(o -> ZonedDateTime.parse(o.get("created_at").asString())))
                      .map(e -> host.parseUserObject(e.get("actor")));
    }

    @Override
    public URI filesUrl(Hash hash) {
        var endpoint = "/" + repository.name() + "/pull/" + id() + "/files/" + hash.hex();
        return host.getWebURI(endpoint);
    }

    @Override
    public Optional<ZonedDateTime> lastForcePushTime() {
        var timelineJSON = request.get("issues/" + json.get("number").toString() + "/timeline")
                .execute();
        return timelineJSON
                .stream()
                .map(JSONValue::asObject)
                .filter(obj -> obj.contains("event"))
                .filter(obj -> obj.get("event").asString().equals("head_ref_force_pushed"))
                .filter(obj -> ZonedDateTime.parse(obj.get("created_at").asString()).isAfter(lastMarkedAsReadyTime(timelineJSON)))
                .map(obj -> ZonedDateTime.parse(obj.get("created_at").asString()))
                .max(Comparator.naturalOrder());
    }

    @Override
    public Optional<Hash> findIntegratedCommitHash() {
        return findIntegratedCommitHash(List.of(repository.forge().currentUser().id()));
    }

    /**
     * For GitHubPullRequest, the json represents the complete snapshot
     */
    @Override
    public Object snapshot() {
        return json;
    }

    public String limitBodySize(String body) {
        if (body.length() > GITHUB_PR_COMMENT_BODY_MAX_SIZE) {
            return body.substring(0, GITHUB_PR_COMMENT_BODY_MAX_SIZE)
                    + "...";
        }
        return body;
    }

    private ZonedDateTime lastMarkedAsReadyTime(JSONValue timelineJSON) {
        return timelineJSON
                .stream()
                .map(JSONValue::asObject)
                .filter(obj -> obj.contains("event"))
                .filter(obj -> obj.get("event").asString().equals("ready_for_review"))
                .map(obj -> ZonedDateTime.parse(obj.get("created_at").asString()))
                .max(ZonedDateTime::compareTo)
                .orElseGet(this::createdAt);
    }
}
