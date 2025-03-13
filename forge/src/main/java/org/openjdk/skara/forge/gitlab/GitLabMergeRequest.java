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
package org.openjdk.skara.forge.gitlab;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;
import org.openjdk.skara.vcs.*;

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
    private final GitLabHost host;

    // Only cache the label names as those are most commonly used and converting to
    // Label objects is expensive. This list is always sorted.
    private List<String> labels;

    // Lazy cache for comparisonSnapshot
    private Object comparisonSnapshot;

    private static final int GITLAB_MR_COMMENT_BODY_MAX_SIZE = 64_000;
    private static final String DRAFT_PREFIX = "Draft:";

    GitLabMergeRequest(GitLabRepository repository, GitLabHost host, JSONValue jsonValue, RestRequest request) {
        this.repository = repository;
        this.host = host;
        this.json = jsonValue;
        this.request = request.restrict("merge_requests/" + json.get("iid").toString() + "/");

        labels = json.get("labels").stream()
                     .map(JSONValue::asString)
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
        return json.get("iid").toString();
    }

    @Override
    public HostUser author() {
        return host.parseAuthorField(json);
    }

    @Override
    public List<Review> reviews() {

        class CommitDate {
            private Hash hash;
            private ZonedDateTime date;
        }

        var commits = request.get("versions").execute().stream()
                             .map(JSONValue::asObject)
                             .map(obj -> {
                                 var ret = new CommitDate();
                                 ret.hash = new Hash(obj.get("head_commit_sha").asString());
                                 ret.date = ZonedDateTime.parse(obj.get("created_at").asString());
                                 return ret;
                             })
                             .collect(Collectors.toCollection(ArrayList::new));
        // Commits are returned in reverse chronological order. We want them
        // primarily in chronological order based on the "created_at" date
        // and secondary in the reverse order they originally came in. We can
        // trust that List::sort is stable.
        Collections.reverse(commits);
        commits.sort(Comparator.comparing(cd -> cd.date));

        // It's possible to create a merge request without any commits
        if (commits.size() == 0) {
            return List.of();
        }

        var currentTargetRef = targetRef();
        var notes = request.get("notes").execute();
        var reviews = notes.stream()
                               .map(JSONValue::asObject)
                               .filter(obj -> obj.get("system").asBoolean())
                               // This matches both approved and unapproved notes
                               .filter(obj -> obj.get("body").asString().contains("approved this merge request"))
                               .map(obj -> {
                                   var reviewerObj = obj.get("author").asObject();
                                   var reviewer = HostUser.create(reviewerObj.get("id").asInt(),
                                                                  reviewerObj.get("username").asString(),
                                                                  reviewerObj.get("name").asString());
                                   var verdict = obj.get("body").asString().contains("unapproved") ? Review.Verdict.NONE : Review.Verdict.APPROVED;
                                   var createdAt = ZonedDateTime.parse(obj.get("created_at").asString());

                                   // Find the latest commit that isn't created after our review
                                   Hash hash = null;
                                   for (var cd : commits) {
                                       if (createdAt.isAfter(cd.date)) {
                                           hash = cd.hash;
                                       }
                                   }
                                   var id = obj.get("id").toString();
                                   return new Review(createdAt, reviewer, verdict, hash, id, "", currentTargetRef);
                               }).toList();
        var targetRefChanges = targetRefChanges(notes);
        return PullRequest.calculateReviewTargetRefs(reviews, targetRefChanges);
    }

    private static final Pattern REF_CHANGES_PATTERN = Pattern.compile("changed target branch from `(.*)` to `(.*)`");
    private List<ReferenceChange> targetRefChanges(JSONValue notes) {
        return notes.stream()
                .map(JSONValue::asObject)
                .filter(obj -> obj.get("system").asBoolean())
                .map(obj -> {
                    var matcher = REF_CHANGES_PATTERN.matcher(obj.get("body").asString());
                    if (matcher.matches()) {
                        return new ReferenceChange(matcher.group(1), matcher.group(2), ZonedDateTime.parse(obj.get("created_at").asString()));
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ReferenceChange> targetRefChanges() {
        return targetRefChanges(request.get("notes").execute());
    }

    @Override
    public void addReview(Review.Verdict verdict, String body) {
        // Remove any previous awards
        var awards = request.get("award_emoji").execute().stream()
                            .map(JSONValue::asObject)
                            .filter(obj -> obj.get("name").asString().equals("thumbsup") ||
                                    obj.get("name").asString().equals("thumbsdown") ||
                                    obj.get("name").asString().equals("question"))
                            .filter(obj -> obj.get("user").get("username").asString().equals(repository.forge().currentUser().username()))
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

    @Override
    public void updateReview(String id, String body) {
        throw new RuntimeException("not implemented yet");
    }

    private ReviewComment parseReviewComment(String discussionId, ReviewComment parent, JSONObject note) {
        int line;
        String path;
        Hash hash;

        var position = note.get("position");
        // Is this a line comment?
        // For line comments, this field is always set, either to a value or null, but
        // for file comments there is no new_line field at all.
        if (position.get("new_line") != null) {
            // Is the comment on the old or the new version of the file?
            if (position.get("new_line").isNull()) {
                line = position.get("old_line").asInt();
                path = position.get("old_path").asString();
                hash = new Hash(position.get("start_sha").asString());
            } else {
                line = position.get("new_line").asInt();
                path = position.get("new_path").asString();
                hash = new Hash(position.get("head_sha").asString());
            }
        } else {
            // This comment does not have a line. Gitlab seems to only allow file comments
            // on the new file
            line = 0;
            path = position.get("new_path").asString();
            hash = new Hash(position.get("head_sha").asString());
        }

        var comment = new ReviewComment(parent,
                                        discussionId,
                                        hash,
                                        path,
                                        line,
                                        note.get("id").toString(),
                                        note.get("body").asString(),
                                        HostUser.create(note.get("author").get("id").asInt(),
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
    public Optional<HostedRepository> sourceRepository() {
        if (json.get("source_project_id").isNull()) {
            return Optional.empty();
        } else {
            var projectId = json.get("source_project_id").asInt();
            var project = ((GitLabHost) repository.forge()).getProjectInfo(projectId);
            if (project.isEmpty()) {
                return Optional.empty();
            } else {
                return Optional.of(new GitLabRepository((GitLabHost) repository.forge(), project.get()));
            }
        }
    }

    @Override
    public String targetRef() {
        var targetRef = json.get("target_branch").asString();
        return targetRef;
    }

    /**
     * In GitLab, if the pull request is in draft mode, the title will include the draft prefix
     */
    @Override
    public String title() {
        var title = json.get("title").asString().strip();
        String pattern = "(?i)^draft:?\\s*";
        return title.replaceAll(pattern, "").strip();
    }

    /**
     * In GitLab, when the bot attempts to update the pull request title,
     * it should check if the pull request is in draft mode.
     * If it is, the bot should add the draft prefix.
     */
    @Override
    public void setTitle(String title) {
        if (isDraft()) {
            title = DRAFT_PREFIX + " " + title;
        }
        request.put("")
               .body("title", title)
               .execute();
    }

    /**
     * This method sets the title without checking if the pull request is in draft mode.
     */
    private void setTitleWithoutDraftPrefix(String title) {
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
                              HostUser.create(comment.get("author").get("id").asInt(),
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
        body = limitBodySize(body);
        var comment = request.post("notes")
                             .body("body", body)
                             .execute();
        var parsedComment = parseComment(comment);
        log.fine("Id of new comment: " + parsedComment.id());
        return parsedComment;
    }

    @Override
    public void removeComment(Comment comment) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Comment updateComment(String id, String body) {
        log.fine("Updating existing comment " + id);
        body =  limitBodySize(body);
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
        if (json.get("state").asString().equals("opened")) {
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
                         .setQuery(Map.of("start_sha", List.of(base.hex())))
                         .build();
    }

    @Override
    public URI commentUrl(Comment comment) {
        return URIBuilder.base(webUrl()).appendPath("#note_" + comment.id()).build();
    }

    @Override
    public URI reviewCommentUrl(ReviewComment reviewComment) {
        return URIBuilder.base(webUrl()).appendPath("#note_" + reviewComment.id()).build();
    }

    @Override
    public URI reviewUrl(Review review) {
        return URIBuilder.base(webUrl()).appendPath("#note_" + review.id()).build();
    }

    @Override
    public boolean isDraft() {
        return json.get("draft").asBoolean();
    }


    @Override
    public void setState(State state) {
        request.put("")
               .body("state_event", state != State.OPEN ? "close" : "reopen")
               .execute();
    }

    private Map<String, Label> labelNameToLabel;

    /**
     * Lookup a label from the repository labels. Initialize and refresh a cache
     * of the repository labels lazily.
     */
    private Label labelNameToLabel(String labelName) {
        if (labelNameToLabel == null || !labelNameToLabel.containsKey(labelName)) {
            labelNameToLabel = repository.labels()
                    .stream()
                    .collect(Collectors.toMap(Label::name, l -> l));
        }
        return labelNameToLabel.get(labelName);
    }

    @Override
    public void addLabel(String label) {
        labels = null;
        request.put("")
                .body("add_labels", label)
                .execute();
    }

    @Override
    public void removeLabel(String label) {
        labels = null;
        request.put("")
                .body("remove_labels", label)
                .execute();
    }

    @Override
    public void setLabels(List<String> labels) {
        request.put("")
               .body("labels", String.join(",", labels))
               .execute();
        this.labels = labels.stream().sorted().toList();
    }

    @Override
    public List<Label> labels() {
        return labelNames().stream()
                .map(this::labelNameToLabel)
                // Avoid throwing NPE for unknown labels
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<String> labelNames() {
        if (labels == null) {
            labels = request.get("").execute().get("labels").stream()
                    .map(JSONValue::asString)
                    .sorted()
                    .collect(Collectors.toList());
        }
        return labels;
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
                                .map(HostUser::username)
                                .map(username -> "@" + username)
                                .collect(Collectors.joining(" "));
            var comment = usernames + " can you have a look at this merge request?";
            addComment(comment);
        }
    }

    @Override
    public void makeNotDraft() {
        if (isDraft()) {
            setTitleWithoutDraftPrefix(title());
        }
    }

    @Override
    public Optional<ZonedDateTime> lastMarkedAsDraftTime() {
        var draftMessage = "marked this merge request as **draft**";
        var notes = request.get("notes").execute();
        var lastMarkedAsDraftTime = notes.stream()
                .map(JSONValue::asObject)
                .filter(obj -> obj.get("system").asBoolean())
                .filter(obj -> draftMessage.equals(obj.get("body").asString()))
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
        return request.get("resource_label_events")
                      .execute()
                      .stream()
                      .map(JSONValue::asObject)
                      .filter(obj -> obj.contains("action"))
                      .filter(obj -> obj.get("action").asString().equals("add"))
                      .filter(obj -> obj.get("label").get("name").asString().equals(label))
                      .map(o -> ZonedDateTime.parse(o.get("created_at").asString()))
                      .findFirst();
    }

    @Override
    public void setTargetRef(String targetRef) {
        request.put("")
               .body("target_branch", targetRef)
               .execute();
    }

    @Override
    public URI headUrl() {
        return URI.create(webUrl() + "/diffs?commit_id=" + headHash().hex());
    }

    @Override
    public Diff diff() {
        var changes = request.get("changes").param("access_raw_diffs", "true").execute();
        boolean complete;
        if (changes.get("overflow").asBoolean()) {
            complete = false;
        } else {
            complete = !changes.get("changes_count").asString().contains("+");
        }
        var targetHash = repository.branchHash(targetRef()).orElseThrow();
        return repository.toDiff(targetHash, headHash(), changes.get("changes"), complete);
    }

    @Override
    public Optional<HostUser> closedBy() {
        if (!isClosed()) {
            return Optional.empty();
        }
        JSONValue closedBy = json.get("closed_by");
        // When MR is in what Skara considers "closed", it may also have been
        // integrated directly in Gitlab. If so, the closed_by field will be
        // null, and the merged_by field will be populated instead.
        if (closedBy.isNull()) {
            closedBy = json.get("merged_by");
        }
        if (closedBy.isNull()) {
            return Optional.empty();
        }
        return Optional.of(host.parseAuthorObject(closedBy.asObject()));
    }

    @Override
    public URI filesUrl(Hash hash) {
        var versionId = request.get("versions").execute().stream()
                               .filter(version -> hash.hex().equals(version.get("head_commit_sha").asString()))
                               .map(version -> String.valueOf(version.get("id").asInt()))
                               .findFirst();
        String uri;
        if (versionId.isEmpty()) {
            uri = "/" + repository.name() + "/-/merge_requests/" + id() + "/diffs?commit_id=" + hash.hex();
        } else {
            uri = "/" + repository.name() + "/-/merge_requests/" + id() + "/diffs?diff_id=" + versionId.get();
        }
        return host.getWebUri(uri);
    }

    @Override
    public Optional<ZonedDateTime> lastForcePushTime() {
        return Optional.empty();
    }

    @Override
    public Optional<Hash> findIntegratedCommitHash() {
        return findIntegratedCommitHash(List.of(repository.forge().currentUser().id()));
    }

    /**
     * For GitLabMergeRequest, a snapshot comparison needs to include the comments
     * and reviews, which are both part of the general "notes".
     */
    @Override
    public Object snapshot() {
        if (comparisonSnapshot == null) {
            comparisonSnapshot = List.of(json, request.get("notes").execute());
        }
        return comparisonSnapshot;
    }

    /**
     * Equality for a GitLabMergeRequest is based on the data snapshot retrieved
     * when the instance was created.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GitLabMergeRequest that = (GitLabMergeRequest) o;
        return json.equals(that.json);
    }

    @Override
    public int hashCode() {
        return Objects.hash(json);
    }

    private String limitBodySize(String body) {
        if (body.length() > GITLAB_MR_COMMENT_BODY_MAX_SIZE) {
            return body.substring(0, GITLAB_MR_COMMENT_BODY_MAX_SIZE)
                    + "...";
        }
        return body;
    }
}
