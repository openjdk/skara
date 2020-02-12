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
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
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

    private List<String> labels = null;

    GitHubPullRequest(GitHubRepository repository, JSONValue jsonValue, RestRequest request) {
        this.host = (GitHubHost)repository.forge();
        this.repository = repository;
        this.request = request;
        this.json = jsonValue;

        labels = json.get("labels")
                     .stream()
                     .map(v -> v.get("name").asString())
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
        var reviews = request.get("pulls/" + json.get("number").toString() + "/reviews").execute().stream()
                             .map(JSONValue::asObject)
                             .filter(obj -> !(obj.get("state").asString().equals("COMMENTED") && obj.get("body").asString().isEmpty()))
                             .map(obj -> {
                                 var reviewer = host.parseUserField(obj);
                                 var hash = new Hash(obj.get("commit_id").asString());
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
                                 var id = obj.get("id").asInt();
                                 var body = obj.get("body").asString();
                                 var createdAt = ZonedDateTime.parse(obj.get("submitted_at").asString());
                                 return new Review(createdAt, reviewer, verdict, hash, id, body);
                             })
                             .collect(Collectors.toList());
        return reviews;
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
        query.put("body", body);
        query.put("commit_id", headHash().hex());
        query.put("comments", JSON.array());
        request.post("pulls/" + json.get("number").toString() + "/reviews")
               .body(query)
               .execute();
    }

    private ReviewComment parseReviewComment(ReviewComment parent, JSONObject json) {
        var author = host.parseUserField(json);
        var threadId = parent == null ? json.get("id").toString() : parent.threadId();

        int line = json.get("original_line").asInt();
        var hash = new Hash(json.get("original_commit_id").asString());
        var path = json.get("path").asString();

        if (json.get("side").asString().equals("LEFT")) {
            var commitInfo = request.get("commits/" + hash).execute();

            // It's possible that the file in question was renamed / deleted in an earlier commit, would
            // need to parse all the commits in the PR to be sure. But this should cover most cases.
            hash = new Hash(commitInfo.get("parents").asArray().get(0).get("sha").asString());
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
                                        json.get("id").toString(),
                                        json.get("body").asString(),
                                        author,
                                        ZonedDateTime.parse(json.get("created_at").asString()),
                                        ZonedDateTime.parse(json.get("updated_at").asString()));
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
        return parseReviewComment(null, response.asObject());
    }

    @Override
    public ReviewComment addReviewCommentReply(ReviewComment parent, String body) {
        var query = JSON.object()
                        .put("body", body)
                        .put("in_reply_to", Integer.parseInt(parent.threadId()));
        var response = request.post("pulls/" + json.get("number").toString() + "/comments")
                              .body(query)
                              .execute();
        return parseReviewComment(parent, response.asObject());
    }

    @Override
    public List<ReviewComment> reviewComments() {
        var ret = new ArrayList<ReviewComment>();
        var reviewComments = request.get("pulls/" + json.get("number").toString() + "/comments").execute().stream()
                                    .map(JSONValue::asObject)
                                    .collect(Collectors.toList());
        var idToComment = new HashMap<String, ReviewComment>();

        for (var reviewComment : reviewComments) {
            ReviewComment parent = null;
            if (reviewComment.contains("in_reply_to_id")) {
                parent = idToComment.get(reviewComment.get("in_reply_to_id").toString());
            }
            var comment = parseReviewComment(parent, reviewComment);
            idToComment.put(comment.id(), comment);
            ret.add(comment);
        }

        return ret;
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
    public HostedRepository sourceRepository() {
        return new GitHubRepository(host, json.get("head").get("repo").get("full_name").asString());
    }

    @Override
    public String targetRef() {
        return json.get("base").get("ref").asString();
    }

    @Override
    public Hash targetHash() {
        return repository.branchHash(targetRef());
    }

    @Override
    public String title() {
        return json.get("title").asString();
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
        var ret = new Comment(Integer.toString(comment.get("id").asInt()),
                              comment.get("body").asString(),
                              host.parseUserField(comment),
                              ZonedDateTime.parse(comment.get("created_at").asString()),
                              ZonedDateTime.parse(comment.get("updated_at").asString()));
        return ret;
    }

    @Override
    public List<Comment> comments() {
        return request.get("issues/" + json.get("number").toString() + "/comments").execute().stream()
                .map(this::parseComment)
                .collect(Collectors.toList());
    }

    @Override
    public Comment addComment(String body) {
        var comment = request.post("issues/" + json.get("number").toString() + "/comments")
                .body("body", body)
                .execute();
        return parseComment(comment);
    }

    @Override
    public Comment updateComment(String id, String body) {
        var comment = request.patch("issues/comments/" + id)
                .body("body", body)
                .execute();
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
                                var completedAt = ZonedDateTime.parse(c.get("completed_at").asString());
                                switch (conclusion) {
                                    case "cancelled":
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
                       return JSONValue.fromNull();
                   }
                   throw new RuntimeException("Invalid response");
               })
               .execute();
    }

    @Override
    public List<String> labels() {
        if (labels == null) {
            labels = request.get("issues/" + json.get("number").toString() + "/labels").execute().stream()
                            .map(JSONValue::asObject)
                            .map(obj -> obj.get("name").asString())
                            .sorted()
                            .collect(Collectors.toList());
        }
        return labels;
    }

    @Override
    public URI webUrl() {
        var host = (GitHubHost)repository.forge();
        var endpoint = "/" + repository.name() + "/pull/" + id();
        return host.getWebURI(endpoint);
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
            assignee_ids.add(assignee.userName());
        }
        var param = JSON.object().put("assignees", assignee_ids);
        request.patch("issues/" + json.get("number").toString()).body(param).execute();
    }

    @Override
    public List<Link> links() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void addLink(Link link) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void removeLink(Link link) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Map<String, JSONValue> properties() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void setProperty(String name, JSONValue value) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void removeProperty(String name) {
        throw new RuntimeException("not implemented yet");
    }
}
