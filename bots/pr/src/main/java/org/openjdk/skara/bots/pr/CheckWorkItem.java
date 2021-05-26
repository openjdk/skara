/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class CheckWorkItem extends PullRequestWorkItem {
    private final Pattern metadataComments = Pattern.compile("<!-- (?:(add|remove) (?:contributor|reviewer))|(?:summary: ')|(?:solves: ')|(?:additional required reviewers)");
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    static final Pattern ISSUE_ID_PATTERN = Pattern.compile("^(?:(?<prefix>[A-Za-z][A-Za-z0-9]+)-)?(?<id>[0-9]+)(?::\\s+(?<title>.+))?$");
    private static final Pattern BACKPORT_TITLE_PATTERN = Pattern.compile("^Backport\\s*([0-9a-z]{40})\\s*$");
    private static final String ELLIPSIS = "…";

    CheckWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        super(bot, pr, errorHandler);
    }

    private String encodeReviewer(HostUser reviewer, CensusInstance censusInstance) {
        var census = censusInstance.census();
        var project = censusInstance.project();
        var namespace = censusInstance.namespace();
        var contributor = namespace.get(reviewer.id());
        if (contributor == null) {
            return "unknown-" + reviewer.id();
        } else {
            var censusVersion = census.version().format();
            var username = contributor.username();
            return contributor.username() + project.isLead(username, censusVersion) +
                    project.isReviewer(username, censusVersion) + project.isCommitter(username, censusVersion) +
                    project.isAuthor(username, censusVersion);
        }
    }

    String getMetadata(CensusInstance censusInstance, String title, String body, List<Comment> comments,
                       List<Review> reviews, Set<String> labels, String targetRef, boolean isDraft, Duration expiresIn) {
        try {
            var approverString = reviews.stream()
                                        .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                        .map(review -> encodeReviewer(review.reviewer(), censusInstance) + review.hash().hex())
                                        .sorted()
                                        .collect(Collectors.joining());
            var commentString = comments.stream()
                                        .filter(comment -> comment.author().id().equals(pr.repository().forge().currentUser().id()))
                                        .flatMap(comment -> comment.body().lines())
                                        .filter(line -> metadataComments.matcher(line).find())
                                        .collect(Collectors.joining());
            var labelString = labels.stream()
                                    .sorted()
                                    .collect(Collectors.joining());
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(title.strip().getBytes(StandardCharsets.UTF_8));
            digest.update(body.strip().getBytes(StandardCharsets.UTF_8));
            digest.update(approverString.getBytes(StandardCharsets.UTF_8));
            digest.update(commentString.getBytes(StandardCharsets.UTF_8));
            digest.update(labelString.getBytes(StandardCharsets.UTF_8));
            digest.update(targetRef.getBytes(StandardCharsets.UTF_8));
            digest.update(isDraft ? (byte)0 : (byte)1);

            var ret = Base64.getUrlEncoder().encodeToString(digest.digest());
            if (expiresIn != null) {
                ret += ":" + Instant.now().plus(expiresIn).getEpochSecond();
            }
            return ret;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    private boolean currentCheckValid(CensusInstance censusInstance, List<Comment> comments, List<Review> reviews, Set<String> labels) {
        var hash = pr.headHash();
        var metadata = getMetadata(censusInstance, pr.title(), pr.body(), comments, reviews, labels, pr.targetRef(), pr.isDraft(), null);
        var currentChecks = pr.checks(hash);

        if (currentChecks.containsKey("jcheck")) {
            var check = currentChecks.get("jcheck");
            if (check.completedAt().isPresent() && check.metadata().isPresent()) {
                var previousMetadata = check.metadata().get();
                if (previousMetadata.contains(":")) {
                    var splitIndex = previousMetadata.lastIndexOf(":");
                    var stableMetadata = previousMetadata.substring(0, splitIndex);
                    var expiresAt = Instant.ofEpochSecond(Long.parseLong(previousMetadata.substring(splitIndex + 1)));
                    if (stableMetadata.equals(metadata) && expiresAt.isAfter(Instant.now())) {
                        log.finer("Metadata with expiration time is still valid, not checking again");
                        return true;
                    } else {
                        log.finer("Metadata expiration time has expired - checking again");
                    }
                } else {
                    if (previousMetadata.equals(metadata)) {
                        log.fine("No activity since last check, not checking again.");
                        return true;
                    } else {
                        log.fine("Previous metadata: " + check.metadata().get() + " - current: " + metadata);
                    }
                }
            } else {
                log.info("Check in progress was never finished - checking again");
            }
        }

        return false;
    }

    /**
     * Return the matching group, or the empty string if no match is found
     */
    private String getMatchGroup(java.util.regex.Matcher m, String group) {
        var prefix = m.group(group);
        if (prefix == null) {
            return "";
        }
        return prefix;
    }

    /**
     * Help the user by fixing up an "almost correct" PR title
     * @return true if the PR was modified
     */
    private boolean updateTitle() {
        var m = ISSUE_ID_PATTERN.matcher(pr.title());
        var project = bot.issueProject();

        if (m.matches() && project != null) {
            var prefix = getMatchGroup(m, "prefix");
            var id = getMatchGroup(m,"id");
            var title = getMatchGroup(m,"title");

            if (!prefix.isEmpty() && !prefix.equalsIgnoreCase(project.name())) {
                // If [project-] prefix does not match our project, something is odd;
                // don't touch the PR title in that case
                return false;
            }

            var issue = project.issue(id);
            if (issue.isPresent()) {
                var issueTitle = issue.get().title();
                if (title.isEmpty()) {
                    // If the title is in the form of "[project-]<bugid>" only
                    // we add the title from JBS
                    var newPrTitle = id + ": " + issueTitle;
                    pr.setTitle(newPrTitle);
                    return true;
                } else {
                    // If it is "[project-]<bugid>: <title-pre>", where <title-pre>
                    // is a cut-off version of the title, we restore the full title
                    if (title.endsWith(ELLIPSIS)) {
                        title = title.substring(0, title.length() - 1);
                    }
                    if (issueTitle.startsWith(title) && issueTitle.length() > title.length()) {
                        var newPrTitle = id + ": " + issueTitle;
                        pr.setTitle(newPrTitle);
                        var remainingTitle = issueTitle.substring(title.length());
                        if (pr.body().startsWith(ELLIPSIS + remainingTitle + "\n\n")) {
                            // Remove remaning title, plus decorations
                            var newPrBody = pr.body().substring(remainingTitle.length() + 3);
                            pr.setBody(newPrBody);
                        }
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return "CheckWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        // First determine if the current state of the PR has already been checked
        var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
        var hostedRepositoryPool = new HostedRepositoryPool(seedPath);

        var census = CensusInstance.create(hostedRepositoryPool, bot.censusRepo(), bot.censusRef(), scratchPath.resolve("census"), pr,
                                           bot.confOverrideRepository().orElse(null), bot.confOverrideName(), bot.confOverrideRef()).orElseThrow();
        var comments = pr.comments();
        var allReviews = pr.reviews();
        var labels = new HashSet<>(pr.labelNames());

        // Filter out the active reviews
        var activeReviews = CheckablePullRequest.filterActiveReviews(allReviews);
        if (!currentCheckValid(census, comments, activeReviews, labels)) {
            if (labels.contains("integrated")) {
                log.info("Skipping check of integrated PR");
                return List.of();
            }

            var m = BACKPORT_TITLE_PATTERN.matcher(pr.title());
            if (m.matches()) {
                var hash = new Hash(m.group(1));
                var metadata = pr.repository().forge().search(hash);
                if (metadata.isPresent()) {
                    var message = CommitMessageParsers.v1.parse(metadata.get().message());
                    var issues = message.issues();
                    var comment = new ArrayList<String>();
                    if (issues.isEmpty()) {
                        var text = "<!-- backport error -->\n" +
                                   ":warning: @" + pr.author().username() + " the commit `" + hash.hex() + "`" +
                                   " does not refer to an issue in project [" +
                                   bot.issueProject().name() + "](" + bot.issueProject().webUrl() + ").";
                        pr.addComment(text);
                        return List.of();
                    }

                    var id = issues.get(0).id();
                    var issue = bot.issueProject().issue(id);
                    if (!issue.isPresent()) {
                        var text = "<!-- backport error -->\n" +
                                   ":warning: @" + pr.author().username() + " the issue with id `" + id + "` from commit " +
                                   "`" + hash.hex() + "` does not exist in project [" +
                                   bot.issueProject().name() + "](" + bot.issueProject().webUrl() + ").";
                        pr.addComment(text);
                        return List.of();
                    }
                    pr.setTitle(id + ": " + issue.get().title());
                    comment.add("<!-- backport " + hash.hex() + " -->\n");
                    for (var additionalIssue : issues.subList(1, issues.size())) {
                        comment.add(SolvesTracker.setSolvesMarker(additionalIssue));
                    }
                    var summary = message.summaries();
                    if (!summary.isEmpty()) {
                        comment.add(Summary.setSummaryMarker(String.join("\n", summary)));
                    }

                    var text = "This backport pull request has now been updated with issue";
                    if (issues.size() > 1) {
                        text += "s";
                    }
                    if (!summary.isEmpty()) {
                        text += " and summary";
                    }
                    text += " from the original [commit](" + metadata.get().url() + ").";
                    comment.add(text);
                    pr.addComment(String.join("\n", comment));
                    pr.addLabel("backport");
                    return List.of(new CheckWorkItem(bot, pr.repository().pullRequest(pr.id()), errorHandler));
                } else {
                    var botUser = pr.repository().forge().currentUser();
                    var isErrorPresent = pr.comments()
                                           .stream()
                                           .filter(c -> c.author().equals(botUser))
                                           .flatMap(c -> Stream.of(c.body().split("\n")))
                                           .anyMatch(l -> l.equals("<!-- backport error -->"));
                    if (!isErrorPresent) {
                        var text = "<!-- backport error -->\n" +
                                   ":warning: @" + pr.author().username() + " could not find any commit with hash `" +
                                   hash.hex() + "`. Please update the title with the hash for an existing commit.";
                        pr.addComment(text);
                    }
                    return List.of();
                }
            }

            // If the title needs updating, we run the check again
            if (updateTitle()) {
                return List.of(new CheckWorkItem(bot, pr.repository().pullRequest(pr.id()), errorHandler));
            }

            try {
                var localRepoPath = scratchPath.resolve("pr").resolve("check").resolve(pr.repository().name());
                var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, localRepoPath);

                var expiresAt = CheckRun.execute(this, pr, localRepo, comments, allReviews, activeReviews, labels, census, bot.ignoreStaleReviews(), bot.integrators());
                if (expiresAt.isPresent()) {
                    bot.scheduleRecheckAt(pr, expiresAt.get());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (pr.labelNames().contains("auto") && pr.labelNames().contains("ready") && !pr.labelNames().contains("sponsor")) {
            pr.addComment("/integrate\n" + PullRequestCommandWorkItem.VALID_BOT_COMMAND_MARKER);
        }

        // Must re-fetch PR after executing CheckRun
        var updatedPR = pr.repository().pullRequest(pr.id());
        return List.of(new PullRequestCommandWorkItem(bot, updatedPR, errorHandler));
    }

    @Override
    public String workItemName() {
        return "check";
    }
}
