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
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.Hash;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;

public class GitLabMergeRequest implements PullRequest {
    private final JSONValue json;
    private final RestRequest request;
    private final Logger log = Logger.getLogger("org.openjdk.skara.host");;
    private final GitLabRepository repository;

    GitLabMergeRequest(GitLabRepository repository, JSONValue jsonValue, RestRequest request) {
        this.repository = repository;
        this.json = jsonValue;
        this.request = request.restrict("merge_requests/" + json.get("iid").toString() + "/");
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
        return json.get("iid").toString();
    }

    @Override
    public HostUser author() {
        return repository.forge().user(json.get("author").get("username").asString()).get();
    }

    @Override
    public List<Review> reviews() {

        class CommitDate {
            private Hash hash;
            private ZonedDateTime date;
        }

        var commits = request.get("commits").execute().stream()
                             .map(JSONValue::asObject)
                             .map(obj -> {
                                 var ret = new CommitDate();
                                 ret.hash = new Hash(obj.get("id").asString());
                                 ret.date = ZonedDateTime.parse(obj.get("created_at").asString());
                                 return ret;
                             })
                             .sorted(Comparator.comparing(cd -> cd.date))
                             .collect(Collectors.toList());

        if (commits.size() == 0) {
            throw new RuntimeException("Reviews on a PR without any commits?");
        }

        return request.get("award_emoji").execute().stream()
                      .map(JSONValue::asObject)
                      .filter(obj -> obj.get("name").asString().equals("thumbsup") ||
                              obj.get("name").asString().equals("thumbsdown") ||
                              obj.get("name").asString().equals("question"))
                      .map(obj -> {
                          var reviewer = repository.forge().user(obj.get("user").get("username").asString());
                          Review.Verdict verdict;
                          switch (obj.get("name").asString()) {
                              case "thumbsup":
                                  verdict = Review.Verdict.APPROVED;
                                  break;
                              case "thumbsdown":
                                  verdict = Review.Verdict.DISAPPROVED;
                                  break;
                              default:
                                  verdict = Review.Verdict.NONE;
                                  break;
                          }

                          var createdAt = ZonedDateTime.parse(obj.get("updated_at").asString());

                          // Find the latest commit that isn't created after our review
                          var hash = commits.get(0).hash;
                          for (var cd : commits) {
                              if (createdAt.isAfter(cd.date)) {
                                  hash = cd.hash;
                              }
                          }
                          var id = obj.get("id").asInt();
                          return new Review(createdAt, reviewer.get(), verdict, hash, id, null);
                      })
                      .collect(Collectors.toList());
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
        // Remove any previous awards
        var awards = request.get("award_emoji").execute().stream()
                            .map(JSONValue::asObject)
                            .filter(obj -> obj.get("name").asString().equals("thumbsup") ||
                                    obj.get("name").asString().equals("thumbsdown") ||
                                    obj.get("name").asString().equals("question"))
                            .filter(obj -> obj.get("user").get("username").asString().equals(repository.forge().currentUser().userName()))
                            .map(obj -> obj.get("id").toString())
                            .collect(Collectors.toList());
        for (var award : awards) {
            request.delete("award_emoji/" + award).execute();
        }

        String award;
        switch (verdict) {
            case APPROVED:
                award = "thumbsup";
                break;
            case DISAPPROVED:
                award = "thumbsdown";
                break;
            default:
                award = "question";
                break;
        }
        request.post("award_emoji")
               .body("name", award)
               .execute();
    }

    private ReviewComment parseReviewComment(String discussionId, ReviewComment parent, JSONObject note) {
        int line;
        String path;
        Hash hash;

        // Is the comment on the old or the new version of the file?
        if (note.get("position").get("new_line").isNull()) {
            line = note.get("position").get("old_line").asInt();
            path = note.get("position").get("old_path").asString();
            hash = new Hash(note.get("position").get("start_sha").asString());
        } else {
            line = note.get("position").get("new_line").asInt();
            path = note.get("position").get("new_path").asString();
            hash = new Hash(note.get("position").get("head_sha").asString());
        }

        var comment = new ReviewComment(parent,
                                        discussionId,
                                        hash,
                                        path,
                                        line,
                                        note.get("id").toString(),
                                        note.get("body").asString(),
                                        new HostUser(note.get("author").get("id").asInt(),
                                                     note.get("author").get("username").asString(),
                                                     note.get("author").get("name").asString()),
                                        ZonedDateTime.parse(note.get("created_at").asString()),
                                        ZonedDateTime.parse(note.get("updated_at").asString()));
        return comment;
    }

    @Override
    public ReviewComment addReviewComment(Hash base, Hash hash, String path, int line, String body) {
        log.fine("Posting a new review comment");
        var query = JSON.object()
                        .put("body", body)
                        .put("position", JSON.object()
                                             .put("base_sha", base.hex())
                                             .put("start_sha", base.hex())
                                             .put("head_sha", hash.hex())
                                             .put("position_type", "text")
                                             .put("new_path", path)
                                             .put("new_line", line));
        var comments = request.post("discussions").body(query).execute();
        if (comments.get("notes").asArray().size() != 1) {
            throw new RuntimeException("Failed to create review comment");
        }
        var parsedComment = parseReviewComment(comments.get("id").asString(), null,
                                               comments.get("notes").asArray().get(0).asObject());
        log.fine("Id of new review comment: " + parsedComment.id());
        return parsedComment;
    }

    @Override
    public ReviewComment addReviewCommentReply(ReviewComment parent, String body) {
        var discussionId = parent.threadId();
        var comment = request.post("discussions/" + discussionId + "/notes")
                             .body("body", body)
                             .execute();
        return parseReviewComment(discussionId, parent, comment.asObject());
    }

    private List<ReviewComment> parseDiscussion(JSONObject discussion) {
        var ret = new ArrayList<ReviewComment>();
        ReviewComment parent = null;
        for (var note : discussion.get("notes").asArray()) {
            // Ignore system generated comments
            if (note.get("system").asBoolean()) {
                continue;
            }
            // Ignore plain comments
            if (!note.contains("position")) {
                continue;
            }

            var comment = parseReviewComment(discussion.get("id").asString(), parent, note.asObject());
            parent = comment;
            ret.add(comment);
        }

        return ret;
    }

    @Override
    public List<ReviewComment> reviewComments() {
        return request.get("discussions").execute().stream()
                      .filter(entry -> !entry.get("individual_note").asBoolean())
                      .flatMap(entry -> parseDiscussion(entry.asObject()).stream())
                      .collect(Collectors.toList());
    }

    @Override
    public Hash headHash() {
        return new Hash(json.get("sha").asString());
    }

    @Override
    public String fetchRef() {
        return "merge-requests/" + id() + "/head";
    }

    @Override
    public String sourceRef() {
        return json.get("source_branch").asString();
    }

    @Override
    public HostedRepository sourceRepository() {
        return new GitLabRepository((GitLabHost) repository.forge(),
                                    json.get("head").get("source_project_id").asString());
    }

    @Override
    public String targetRef() {
        return json.get("target_branch").asString();
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
        request.put("")
               .body("title", title)
               .execute();
    }

    @Override
    public String body() {
        var body = json.get("description").asString();
        if (body == null) {
            body = "";
        }
        return body;
    }

    @Override
    public void setBody(String body) {
        request.put("")
               .body("description", body)
               .execute();
    }

    private Comment parseComment(JSONValue comment) {
        var ret = new Comment(comment.get("id").toString(),
                              comment.get("body").asString(),
                              new HostUser(comment.get("author").get("id").asInt(),
                                           comment.get("author").get("username").asString(),
                                           comment.get("author").get("name").asString()),
                              ZonedDateTime.parse(comment.get("created_at").asString()),
                              ZonedDateTime.parse(comment.get("updated_at").asString()));
        return ret;
    }

    @Override
    public List<Comment> comments() {
        return request.get("notes").param("sort", "asc").execute().stream()
                      .filter(entry -> !entry.contains("position")) // Ignore comments with a position - they are review comments
                      .filter(entry -> !entry.get("system").asBoolean()) // Ignore system generated comments
                .map(this::parseComment)
                .collect(Collectors.toList());
    }

    @Override
    public Comment addComment(String body) {
        log.fine("Posting a new comment");
        var comment = request.post("notes")
                             .body("body", body)
                             .execute();
        var parsedComment = parseComment(comment);
        log.fine("Id of new comment: " + parsedComment.id());
        return parsedComment;
    }

    @Override
    public Comment updateComment(String id, String body) {
        log.fine("Updating existing comment " + id);
        var comment = request.put("notes/" + id)
                             .body("body", body)
                             .execute();
        var parsedComment = parseComment(comment);
        log.fine("Id of updated comment: " + parsedComment.id());
        return parsedComment;
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

    private final String checkMarker = "<!-- Merge request status check message (%s) -->";
    private final String checkResultMarker = "<!-- Merge request status check result (%s) (%s) (%s) (%s) -->";
    private final String checkResultPattern = "<!-- Merge request status check result \\(([-\\w]+)\\) \\((\\w+)\\) \\(%s\\) \\((\\S+)\\) -->";

    private Optional<Comment> getStatusCheckComment(String name) {
        var marker = String.format(checkMarker, name);

        return comments().stream()
                         .filter(c -> c.body().contains(marker))
                         .findFirst();
    }

    private String encodeMarkdown(String message) {
        return message.replaceAll("\n", "  \n");
    }

    private final Pattern checkBodyPattern = Pattern.compile("^# ([^\\n\\r]*)\\R(.*)",
                                                             Pattern.DOTALL | Pattern.MULTILINE);

    @Override
    public Map<String, Check> checks(Hash hash) {
        var pattern = Pattern.compile(String.format(checkResultPattern, hash.hex()));
        var matchers = comments().stream()
                                 .collect(Collectors.toMap(comment -> comment,
                        comment -> pattern.matcher(comment.body())));

        return matchers.entrySet().stream()
                .filter(entry -> entry.getValue().find())
                .collect(Collectors.toMap(entry -> entry.getValue().group(1),
                        entry -> {
                            var checkBuilder = CheckBuilder.create(entry.getValue().group(1), hash);
                            checkBuilder.startedAt(entry.getKey().createdAt());
                            var status = entry.getValue().group(2);
                            var completedAt = entry.getKey().updatedAt();
                            switch (status) {
                                case "RUNNING":
                                    // do nothing
                                    break;
                                case "SUCCESS":
                                    checkBuilder.complete(true, completedAt);
                                    break;
                                case "FAILURE":
                                    checkBuilder.complete(false, completedAt);
                                    break;
                                case "CANCELLED":
                                    checkBuilder.cancel(completedAt);
                                    break;
                                default:
                                    throw new IllegalStateException("Unknown status: " + status);
                            }
                            if (!entry.getValue().group(3).equals("NONE")) {
                                checkBuilder.metadata(new String(Base64.getDecoder().decode(entry.getValue().group(3)), StandardCharsets.UTF_8));
                            }
                            var checkBodyMatcher = checkBodyPattern.matcher(entry.getKey().body());
                            if (checkBodyMatcher.find()) {
                                // escapeMarkdown adds an additional space before the newline
                                var title = checkBodyMatcher.group(1);
                                var nonEscapedTitle = title.substring(0, title.length() - 2);
                                checkBuilder.title(nonEscapedTitle);
                                checkBuilder.summary(checkBodyMatcher.group(2));
                            }
                            return checkBuilder.build();
                        }));
    }

    private String statusFor(Check check) {
        switch (check.status()) {
            case IN_PROGRESS:
                return "RUNNING";
            case SUCCESS:
                return "SUCCESS";
            case FAILURE:
                return "FAILURE";
            case CANCELLED:
                return "CANCELLED";
            default:
                throw new RuntimeException("Unknown check status");
        }
    }

    private String metadataFor(Check check) {
        if (check.metadata().isPresent()) {
            return Base64.getEncoder().encodeToString(check.metadata().get().getBytes(StandardCharsets.UTF_8));
        }
        return "NONE";
    }

    private String linkToDiff(String path, Hash hash, int line) {
        return "[" + path + " line " + line + "](" + URIBuilder.base(repository.url())
                         .setPath("/" + repository.name()+ "/blob/" + hash.hex() + "/" + path)
                         .setAuthentication(null)
                         .build() + "#L" + Integer.toString(line) + ")";
    }

    private String bodyFor(Check check) {
        var status = check.status();
        String body;
        switch (status) {
            case IN_PROGRESS:
                body = ":hourglass_flowing_sand: The merge request check **" + check.name() + "** is currently running...";
                break;
            case SUCCESS:
                body = ":tada: The merge request check **" + check.name() + "** completed successfully!";
                break;
            case FAILURE:
                body = ":warning: The merge request check **" + check.name() + "** identified the following issues:";
                break;
            case CANCELLED:
                body = ":x: The merge request check **" + check.name() + "** has been cancelled.";
                break;
            default:
                throw new RuntimeException("Unknown check status");
        }

        if (check.title().isPresent()) {
            body += encodeMarkdown("\n" + "# " + check.title().get());
        }

        if (check.summary().isPresent()) {
            body += encodeMarkdown("\n" + check.summary().get());
        }

        for (var annotation : check.annotations()) {
            var annotationString = "  - ";
            switch (annotation.level()) {
                case NOTICE:
                    annotationString += "Notice: ";
                    break;
                case WARNING:
                    annotationString += "Warning: ";
                    break;
                case FAILURE:
                    annotationString += "Failure: ";
                    break;
            }
            annotationString += linkToDiff(annotation.path(), check.hash(), annotation.startLine());
            annotationString += "\n    - " + annotation.message().lines().collect(Collectors.joining("\n    - "));

            body += "\n" + annotationString;
        }

        return body;
    }

    private void updateCheckComment(Optional<Comment> previous, Check check) {
        var status = statusFor(check);
        var metadata = metadataFor(check);
        var markers = String.format(checkMarker, check.name()) + "\n" +
                      String.format(checkResultMarker,
                                    check.name(),
                                    status,
                                    check.hash(),
                                    metadata);

        var body = bodyFor(check);
        var message = markers + "\n" + body;
        previous.ifPresentOrElse(
                p  -> updateComment(p.id(), message),
                () -> addComment(message));
    }

    @Override
    public void createCheck(Check check) {
        log.info("Looking for previous status check comment");

        var previous = getStatusCheckComment(check.name());
        updateCheckComment(previous, check);
    }

    @Override
    public void updateCheck(Check check) {
        log.info("Looking for previous status check comment");

        var previous = getStatusCheckComment(check.name())
                .orElseGet(() -> addComment("Progress deleted?"));
        updateCheckComment(Optional.of(previous), check);
    }

    @Override
    public URI changeUrl() {
        return URIBuilder.base(webUrl()).appendPath("/diffs").build();
    }

    @Override
    public URI changeUrl(Hash base) {
        return URIBuilder.base(webUrl()).appendPath("/diffs")
                         .setQuery(Map.of("start_sha", base.hex()))
                         .build();
    }

    @Override
    public boolean isDraft() {
        return json.get("work_in_progress").asBoolean();
    }


    @Override
    public void setState(State state) {
        request.put("")
               .body("state_event", state != State.OPEN ? "close" : "reopen")
               .execute();
    }

    @Override
    public void addLabel(String label) {
        // GitLab does not allow adding/removing single labels, only setting the full list
        // We retrieve the list again here to try to minimize the race condition window
        var currentJson = request.get("").execute().asObject();
        var labels = Stream.concat(currentJson.get("labels").stream()
                .map(JSONValue::asString),
                List.of(label).stream())
                .collect(Collectors.toSet());
        request.put("")
               .body("labels", String.join(",", labels))
               .execute();
    }

    @Override
    public void removeLabel(String label) {
        var currentJson = request.get("").execute().asObject();
        var labels = currentJson.get("labels").stream()
                .map(JSONValue::asString)
                .filter(l -> !l.equals(label))
                .collect(Collectors.toSet());
        request.put("")
               .body("labels", String.join(",", labels))
               .execute();
    }

    @Override
    public List<String> labels() {
        var currentJson = request.get("").execute().asObject();
        return currentJson.get("labels").stream()
                .map(JSONValue::asString)
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(repository.webUrl())
                         .setPath("/" + repository.name() + "/merge_requests/" + id())
                         .build();
    }

    @Override
    public String toString() {
        return "GitLabMergeRequest #" + id() + " by " + author();
    }

    @Override
    public List<HostUser> assignees() {
        var assignee = json.get("assignee").asObject();
        if (assignee != null) {
            var user = repository.forge().user(assignee.get("username").asString());
            return List.of(user.get());
        }
        return Collections.emptyList();
    }

    @Override
    public void setAssignees(List<HostUser> assignees) {
        var id = assignees.size() == 0 ? 0 : Integer.valueOf(assignees.get(0).id());
        var param = JSON.object().put("assignee_id", id);
        request.put().body(param).execute();
        if (assignees.size() > 1) {
            var rest = assignees.subList(1, assignees.size());
            var usernames = rest.stream()
                                .map(HostUser::userName)
                                .map(username -> "@" + username)
                                .collect(Collectors.joining(" "));
            var comment = usernames + " can you have a look at this merge request?";
            addComment(comment);
        }
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
    public void setProperty(String name,JSONValue value) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public void removeProperty(String name) {
        throw new RuntimeException("not implemented yet");
    }
}
