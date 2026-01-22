/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.util.logging.Level;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.bots.common.SolvesTracker;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.IssueTrackerIssue;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.vcs.openjdk.Issue;

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

import static org.openjdk.skara.bots.common.PullRequestConstants.*;
import static org.openjdk.skara.bots.pr.CheckRun.MERGE_READY_MARKER;
import static org.openjdk.skara.bots.pr.CheckRun.PLACEHOLDER_MARKER;
import static org.openjdk.skara.forge.PullRequestUtils.mergeSourcePattern;

class CheckWorkItem extends PullRequestWorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    static final Pattern ISSUE_ID_PATTERN = Pattern.compile("^(?:(?<prefix>[A-Za-z][A-Za-z0-9]+)-)?(?<id>[0-9]+)"
            + "(?:(?:\\s*:)?(?<space>[\\s\u00A0\u2007\u202F]+)(?<title>.+))?$");
    private static final Pattern BACKPORT_HASH_TITLE_PATTERN = Pattern.compile("^Backport\\s*([0-9a-z]{40})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BACKPORT_ISSUE_TITLE_PATTERN = Pattern.compile("^Backport\\s*(?:(?<prefix>[A-Za-z][A-Za-z0-9]+)-)?(?<id>[0-9]+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_COMMENTS_PATTERN = Pattern.compile("<!-- (?:backport)|(?:(add|remove) (?:contributor|reviewer))|(?:summary: ')|(?:solves: ')|(?:additional required reviewers)|(?:jep: ')|(?:csr: ')");
    private static final String ELLIPSIS = "…";
    protected static final String FORCE_PUSH_MARKER = "<!-- force-push suggestion -->";
    protected static final String FORCE_PUSH_SUGGESTION= """
            Please do not rebase or force-push to an active PR as it invalidates existing review comments. \
            Note for future reference, the bots always squash all changes into a single commit automatically as part of the integration. \
            See [OpenJDK Developers’ Guide](https://openjdk.org/guide/#working-with-pull-requests) for more information.
            """;

    private final boolean forceUpdate;
    private final boolean spawnedFromIssueBot;
    private final boolean initialRun;
    private final Map<String, Optional<IssueTrackerIssue>> issues = new HashMap<>();

    @Override
    public boolean replaces(WorkItem other) {
        if (!other.getClass().equals(this.getClass())) {
            return false;
        }
        var otherCheckWorkItem = (CheckWorkItem) other;
        return !concurrentWith(other) && this.forceUpdate == otherCheckWorkItem.forceUpdate
                && this.initialRun == otherCheckWorkItem.initialRun
                && this.spawnedFromIssueBot == otherCheckWorkItem.spawnedFromIssueBot;
    }

    private CheckWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt,
                          boolean needsReadyCheck, boolean forceUpdate, boolean spawnedFromIssueBot, boolean initialRun) {
        super(bot, prId, errorHandler, triggerUpdatedAt, needsReadyCheck);
        this.forceUpdate = forceUpdate;
        this.spawnedFromIssueBot = spawnedFromIssueBot;
        this.initialRun = initialRun;
    }

    /**
     * Create CheckWorkItem spawned from CSRIssueWorkItem
     */
    public static CheckWorkItem fromCSRIssue(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt, boolean forceUpdate) {
        return new CheckWorkItem(bot, prId, errorHandler, triggerUpdatedAt, true, forceUpdate, true, false);
    }

    /**
     * Create CheckWorkItem spawned from initial run of PullRequestBot
     */
    public static CheckWorkItem fromInitialRunOfPRBot(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt) {
        return new CheckWorkItem(bot, prId, errorHandler, triggerUpdatedAt, true, false, false, true);
    }

    /**
     * Create CheckWorkItem spawned from PullRequestBot
     */
    public static CheckWorkItem fromPRBot(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt) {
        return new CheckWorkItem(bot, prId, errorHandler, triggerUpdatedAt, true, false, false, false);
    }

    /**
     * Create CheckWorkItem spawned from IssueBot
     */
    public static CheckWorkItem fromIssueBot(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt) {
        return new CheckWorkItem(bot, prId, errorHandler, triggerUpdatedAt, true, false, true, false);
    }

    /**
     * Create Normal CheckWorkItem
     */
    public static CheckWorkItem fromWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt) {
        return new CheckWorkItem(bot, prId, errorHandler, triggerUpdatedAt, false, false, false, false);
    }

    /**
     * Create Normal CheckWorkItem with force update
     */
    public static CheckWorkItem fromWorkItemWithForceUpdate(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler, ZonedDateTime triggerUpdatedAt) {
        return new CheckWorkItem(bot, prId, errorHandler, triggerUpdatedAt, false, true, false, false);
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

    /**
     * Provides cached fetching of issues from the IssueTracker.
     * @param shortId Short id of issue to fetch, e.g. the id of an issue is TEST-123, then the short id of the issue is 123
     * @return The issue if found, otherwise empty.
     */
    Optional<IssueTrackerIssue> issueTrackerIssue(String shortId) {
        if (!issues.containsKey(shortId)) {
            issues.put(shortId, bot.issueProject().issue(shortId));
        }
        return issues.get(shortId);
    }

    String getPRMetadata(CensusInstance censusInstance, String title, String body, List<Comment> comments,
                       List<Review> reviews, Set<String> labels, String targetRef, boolean isDraft) {
        try {
            var approverString = reviews.stream()
                                        .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                        .filter(review -> review.hash().isPresent())
                                        .map(review -> encodeReviewer(review.reviewer(), censusInstance) + review.targetRef()
                                                + review.hash().orElseThrow().hex())
                                        .sorted()
                                        .collect(Collectors.joining());
            var commentString = comments.stream()
                                        .filter(comment -> comment.author().id().equals(pr.repository().forge().currentUser().id()))
                                        .flatMap(comment -> comment.body().lines())
                                        .filter(line -> METADATA_COMMENTS_PATTERN.matcher(line).find())
                                        .collect(Collectors.joining());

            // Webrev comment should trigger the update
            commentString = commentString + comments.stream()
                    .filter(comment -> comment.author().username().equals(bot.mlbridgeBotName()))
                    .flatMap(comment -> comment.body().lines())
                    .filter(line -> line.equals(WEBREV_COMMENT_MARKER))
                    .findFirst().orElse("");

            // Touch command should trigger the update
            commentString = commentString + comments.stream()
                    .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
                    .flatMap(comment -> comment.body().lines())
                    .filter(line -> line.contains(TOUCH_COMMAND_RESPONSE_MARKER))
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
            digest.update(censusInstance.configuration().rawJCheckConf().getBytes(StandardCharsets.UTF_8));
            digest.update(isDraft ? (byte) 0 : (byte) 1);

            return Base64.getUrlEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    String getIssueMetadata(String prBody) {
        try {
            var issueProject = bot.issueProject();
            if (issueProject == null) {
                return "";
            }
            var issueIds = BotUtils.parseAllIssues(prBody);
            var issuesData = issueIds.stream()
                    .map(i -> new Issue(i, "").shortId())
                    .sorted()
                    .map(this::issueTrackerIssue)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(issue -> {
                        var issueData = new StringBuilder();
                        issueData.append(issue.id());
                        issueData.append(issue.title());
                        issueData.append(issue.status());
                        issue.resolution().ifPresent(issueData::append);
                        var properties = issue.properties();
                        if (properties != null) {
                            issueData.append(properties.get("priority").asString());
                            issueData.append(properties.get("issuetype").asString());
                            if (properties.get("fixVersions") != null) {
                                issueData.append(properties.get("fixVersions").stream()
                                        .map(JSONValue::asString)
                                        .sorted()
                                        .toList());
                            }
                        }
                        if (bot.approval() != null && bot.approval().needsApproval(PreIntegrations.realTargetRef(pr))) {
                            // Add a static sting to the metadata if the PR needs approval to force
                            // update if this configuration has changed for the target branch.
                            issueData.append("approval");
                            issueData.append(String.join("", issue.labelNames()));
                        }
                        return issueData;
                    })
                    .collect(Collectors.joining());

            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(issuesData.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    String getMetadata(String PRMetadata, String issueMetadata, Duration expiresIn) {
        var ret = PRMetadata + "#" + issueMetadata;
        if (expiresIn != null) {
            ret += ":" + Instant.now().plus(expiresIn).getEpochSecond();
        }
        return ret;
    }

    private boolean currentCheckValid(CensusInstance censusInstance, List<Comment> comments, List<Review> reviews, Set<String> labels) {
        var hash = pr.headHash();
        var currentChecks = pr.checks(hash);
        var jcheckName = CheckRun.getJcheckName(pr);

        if (currentChecks.containsKey(jcheckName)) {
            var check = currentChecks.get(jcheckName);
            if (check.completedAt().isPresent() && check.metadata().isPresent()) {
                var previousMetadata = check.metadata().get();
                Instant expiresAt = null;

                if (previousMetadata.contains(":")) {
                    var splitIndex = previousMetadata.lastIndexOf(":");
                    expiresAt = Instant.ofEpochSecond(Long.parseLong(previousMetadata.substring(splitIndex + 1)));
                    previousMetadata = previousMetadata.substring(0, splitIndex);
                }

                String[] substrings = previousMetadata.split("#");
                String previousPRMetadata = substrings[0];
                String previousIssueMetadata = (substrings.length > 1) ? substrings[1] : "";

                // triggered by issue update or initial run when bot restarts
                if (initialRun || spawnedFromIssueBot) {
                    var currIssueMetadata = getIssueMetadata(pr.body());
                    if (expiresAt != null) {
                        if (previousIssueMetadata.equals(currIssueMetadata) && expiresAt.isAfter(Instant.now())) {
                            log.finer("[Issue]Metadata with expiration time is still valid, not checking again");
                        } else {
                            log.finer("[Issue]Metadata expiration time has expired - checking again");
                            return false;
                        }
                    } else {
                        if (previousIssueMetadata.equals(currIssueMetadata)) {
                            log.fine("[Issue]No activity since last check, not checking again.");
                        } else {
                            log.fine("[Issue]Previous metadata: " + previousIssueMetadata + " - current: " + currIssueMetadata);
                            return false;
                        }
                    }
                }
                // triggered by pr updates
                if (!spawnedFromIssueBot) {
                    var currPRMetadata = getPRMetadata(censusInstance, pr.title(), pr.body(), comments, reviews,
                            labels, pr.targetRef(), pr.isDraft());
                    if (expiresAt != null) {
                        if (previousPRMetadata.equals(currPRMetadata) && expiresAt.isAfter(Instant.now())) {
                            log.finer("[PR]Metadata with expiration time is still valid, not checking again");
                        } else {
                            log.finer("[PR]Metadata expiration time has expired - checking again");
                            return false;
                        }
                    } else {
                        if (previousPRMetadata.equals(currPRMetadata)) {
                            log.fine("[PR]No activity since last check, not checking again.");
                        } else {
                            log.fine("[PR]Previous metadata: " + previousPRMetadata + " - current: " + currPRMetadata);
                            return false;
                        }
                    }
                }
            } else {
                log.info("Check in progress was never finished - checking again");
                return false;
            }
        } else {
            return false;
        }

        return true;
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
        var oldPrTitle = pr.title();
        var m = ISSUE_ID_PATTERN.matcher(oldPrTitle.trim());
        var project = bot.issueProject();

        if (m.matches() && project != null) {
            var prefix = getMatchGroup(m, "prefix");
            var id = getMatchGroup(m,"id");
            var space = getMatchGroup(m, "space");
            var title = getMatchGroup(m,"title");

            if (!prefix.isEmpty() && !prefix.equalsIgnoreCase(project.name())) {
                // If [project-] prefix does not match our project, something is odd;
                // don't touch the PR title in that case
                return false;
            }

            var issue = issueTrackerIssue(id);
            if (issue.isPresent()) {
                var issueTitle = issue.get().title();
                var newPrTitle = id + ": " + issueTitle;
                if (title.isEmpty()) {
                    // If the title is in the form of "[project-]<bugid>" only
                    // we add the title from JBS
                    pr.setTitle(newPrTitle);
                    return true;
                } else {
                    // If it is "[project-]<bugid>: <title-pre>", where <title-pre>
                    // is a cut-off version of the title, we restore the full title
                    if (title.endsWith(ELLIPSIS)) {
                        title = title.substring(0, title.length() - 1);
                    }
                    if (issueTitle.startsWith(title) && issueTitle.length() > title.length()) {
                        pr.setTitle(newPrTitle);
                        var remainingTitle = issueTitle.substring(title.length());
                        if (pr.body().startsWith(ELLIPSIS + remainingTitle + "\n\n")) {
                            // Remove remaining title, plus decorations
                            var newPrBody = pr.body().substring(remainingTitle.length() + 3);
                            pr.setBody(newPrBody);
                        }
                        return true;
                    }
                    // Automatically update PR title if it's not in canonical form
                    if (title.equals(issueTitle) && !oldPrTitle.equals(newPrTitle)) {
                        pr.setTitle(newPrTitle);
                        return true;
                    }
                    // Automatically update PR title if titles are a relaxed match but not an exact match
                    if (!title.equals(issueTitle) && CheckRun.relaxedEquals(title, issueTitle)) {
                        pr.setTitle(newPrTitle);
                        return true;
                    }
                }
            }

            if (!space.equals(" ")) {
                // If the space separating the issue and the title is not a single space, rewrite it
                var newPrTitle = id + ": " + title;
                pr.setTitle(newPrTitle);
                return true;
            }
        }

        return false;
    }

    private boolean updateAdditionalIssuesTitle(List<Comment> comments) {
        if (bot.issueProject() == null) {
            return false;
        }
        var issueTitleUpdated = false;
        var botUser = pr.repository().forge().currentUser();
        var issues = SolvesTracker.currentSolved(botUser, comments, pr.title());
        for (var issue : issues) {
            var solvesComment = SolvesTracker.getLatestSolvesActionComment(botUser, comments, issue);
            if (solvesComment.isPresent()) {
                var issueTrackerIssue = issueTrackerIssue(issue.shortId());
                if (issueTrackerIssue.isPresent() && !issue.description().equals(issueTrackerIssue.get().title())) {
                    pr.updateComment(solvesComment.get().id(),
                            solvesComment.get().body().replace(SolvesTracker.setSolvesMarker(issue),
                                    SolvesTracker.setSolvesMarker(new Issue(issue.shortId(), issueTrackerIssue.get().title()))));
                    issueTitleUpdated = true;
                }
            }
        }
        return issueTitleUpdated;
    }

    private void initializeIssuePRMap() {
        // When bot restarts, the issuePRMap needs to get updated with this pr
        if (!bot.initializedPRs().containsKey(prId)) {
            var prRecord = new PRRecord(pr.repository().name(), prId);
            var issueIds = BotUtils.parseAllIssues(pr.body());
            for (String issueId : issueIds) {
                bot.addIssuePRMapping(issueId, prRecord);
            }
            bot.initializedPRs().put(prId, true);
        }
    }

    @Override
    public String toString() {
        return "CheckWorkItem@" + bot.repo().name() + "#" + prId;
    }

    @Override
    public Collection<WorkItem> prRun(ScratchArea scratchArea) {
        var seedPath = bot.seedStorage().orElse(scratchArea.getSeeds());
        var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
        CensusInstance census;
        var comments = prComments();
        comments = postPlaceholderForReadyComment(comments);

        if (pr.headHash().hex() == null) {
            String text = "The head hash of this pull request is missing. " +
                    "Until this is resolved, this pull request cannot be processed." +
                    "This is likely caused by a caching problem in the server " +
                    "and can usually be worked around by pushing another commit to the pull request branch. " +
                    "The commit can be empty. Example:\n" +
                    "```bash\n" +
                    "$ git commit --allow-empty -m \"Empty commit\"\n" +
                    "$ git push\n" +
                    "```\n" +
                    "If the issue still exists, please notify Skara admins.";
            addErrorComment(text, comments);
            return List.of();
        }

        try {
            census = CensusInstance.createCensusInstance(hostedRepositoryPool, bot.censusRepo(), bot.censusRef(), scratchArea.getCensus(), pr,
                    bot.confOverrideRepository().orElse(null), bot.confOverrideName(), bot.confOverrideRef());
        } catch (MissingJCheckConfException e) {
            if (bot.confOverrideRepository().isEmpty()) {
                log.log(Level.SEVERE, "No .jcheck/conf found in repo " + bot.repo().name(), e);
                var text = " ⚠️ @" + pr.author().username() + " No `.jcheck/conf` found in the target branch of this pull request. "
                        + "Until that is resolved, this pull request cannot be processed. Please notify the repository owner.";
                addErrorComment(text, comments);
            } else {
                log.log(Level.SEVERE, "Jcheck configuration file " + bot.confOverrideName()
                        + " not found in external repo " + bot.confOverrideRepository().get().name(), e);
                var text = " ⚠️ @" + pr.author().username() + " The external jcheck configuration for this repository could not be found. "
                        + "Until that is resolved, this pull request cannot be processed. Please notify a Skara admin.";
                addErrorComment(text, comments);
            }
            return List.of();
        } catch (InvalidJCheckConfException e) {
            if (bot.confOverrideRepository().isEmpty()) {
                log.log(Level.SEVERE, "Invalid .jcheck/conf found in repo " + bot.repo().name(), e);
                var text = " ⚠️ @" + pr.author().username() + " The `.jcheck/conf` in the target branch of this pull request is invalid. "
                        + "Until that is resolved, this pull request cannot be processed. Please notify the repository owner.";
                addErrorComment(text, comments);
            } else {
                log.log(Level.SEVERE, "Invalid Jcheck configuration file " + bot.confOverrideName()
                        + " in external repo " + bot.confOverrideRepository().get().name(), e);
                var text = " ⚠️ @" + pr.author().username() + " The external jcheck configuration for this repository is invalid. "
                        + "Until that is resolved, this pull request cannot be processed. Please notify a Skara admin.";
                addErrorComment(text, comments);
            }
            return List.of();
        }

        var allReviews = pr.reviews();
        var labels = new HashSet<>(pr.labelNames());
        // Filter out the active reviews
        var activeReviews = CheckablePullRequest.filterActiveReviews(allReviews, pr.targetRef());
        // initialize issue associations for this pr
        initializeIssuePRMap();
        // Determine if the current state of the PR has already been checked
        if (forceUpdate || !currentCheckValid(census, comments, activeReviews, labels)) {
            var backportHashMatcher = BACKPORT_HASH_TITLE_PATTERN.matcher(pr.title());
            var backportIssueMatcher = BACKPORT_ISSUE_TITLE_PATTERN.matcher(pr.title());

            // If backport pr is not allowed, reply warning to the user and return
            if (!bot.enableBackport() && (backportHashMatcher.matches() || backportIssueMatcher.matches())) {
                var backportDisabledText = "<!-- backport error -->\n" +
                        ":warning: @" + pr.author().username() + " backports are not allowed in this repository." +
                        " If it was unintentional, please modify the title of this pull request.";
                addErrorComment(backportDisabledText, comments);
                return List.of();
            }

            // If merge pr is not allowed, reply warning to the user and return
            if (!bot.enableMerge() && PullRequestUtils.isMerge(pr)) {
                var mergeDisabledText = "<!-- merge error -->\n" +
                        ":warning: @" + pr.author().username() + " Merge-style pull requests are not allowed in this repository." +
                        " If it was unintentional, please modify the title of this PR.";
                addErrorComment(mergeDisabledText, comments);
                return List.of();
            }

            // If source repo of Merge-style pr is not allowed, reply warning to the user and return
            if (PullRequestUtils.isMerge(pr)) {
                var sourceMatcher = mergeSourcePattern.matcher(pr.title());
                if (sourceMatcher.matches()) {
                    var source = sourceMatcher.group(1);
                    if (source.contains(":")) {
                        var repoName = source.split(":", 2)[0];
                        if (!repoName.contains("/")) {
                            repoName = Path.of(pr.repository().name()).resolveSibling(repoName).toString();
                        }
                        // Check repo name
                        var mergeSources = bot.mergeSources();
                        if (!mergeSources.isEmpty() && !mergeSources.contains(repoName) && !pr.repository().name().equals(repoName)) {
                            var mergeSourceInvalidText = "<!-- merge error -->\n" +
                                    ":warning: @" + pr.author().username() + " " + repoName +
                                    " can not be source repo for merge-style pull requests in this repository.\n" +
                                    "List of valid source repositories: \n" +
                                    String.join(", ", bot.mergeSources().stream().sorted().toList()) + ".";
                            addErrorComment(mergeSourceInvalidText, comments);
                            return List.of();
                        }
                    }
                }
            }

            if (labels.contains("integrated")) {
                log.info("Skipping check of integrated PR");
                // We still need to make sure any commands get run or are able to finish a
                // previously interrupted run
                return List.of(new PullRequestCommandWorkItem(bot, prId, errorHandler, triggerUpdatedAt, false));
            }

            if (backportHashMatcher.matches()) {
                var hash = new Hash(backportHashMatcher.group(1));
                try {
                    var localRepo = materializeLocalRepo(scratchArea, hostedRepositoryPool);
                    if (localRepo.isAncestor(hash, pr.headHash())) {
                        var text = "<!-- backport error -->\n" +
                                ":warning: @" + pr.author().username() + " the given backport hash `" + hash.hex() +
                                "` is an ancestor of your proposed change. Please update the title with the hash for" +
                                " the change you are backporting.";
                        addErrorComment(text, comments);
                        return List.of();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                var forge = pr.repository().forge();
                var repoName = forge.search(hash);
                if (repoName.isPresent()) {
                    var commit = forge.repository(repoName.get()).flatMap(repository -> repository.commit(hash));
                    var message = CommitMessageParsers.v1.parse(commit.orElseThrow().message());
                    var issues = message.issues();
                    var comment = new ArrayList<String>();
                    if (issues.isEmpty()) {
                        var text = "<!-- backport error -->\n" +
                                   ":warning: @" + pr.author().username() + " the commit `" + hash.hex() + "`" +
                                   " does not refer to an issue in project [" +
                                   bot.issueProject().name() + "](" + bot.issueProject().webUrl() + ").";
                        addErrorComment(text, comments);
                        return List.of();
                    }

                    var id = issues.get(0).shortId();
                    var issue = issueTrackerIssue(id);
                    if (!issue.isPresent()) {
                        var text = "<!-- backport error -->\n" +
                                   ":warning: @" + pr.author().username() + " the issue with id `" + id + "` from commit " +
                                   "`" + hash.hex() + "` does not exist in project [" +
                                   bot.issueProject().name() + "](" + bot.issueProject().webUrl() + ").";
                        addErrorComment(text, comments);
                        return List.of();
                    }
                    pr.setTitle(id + ": " + issue.get().title());
                    comment.add("<!-- backport " + hash.hex() + " -->\n");
                    comment.add("<!-- repo " + repoName.get() + " -->\n");
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
                    text += " from the original [commit](" + commit.get().url() + ").";
                    comment.add(text);
                    pr.addComment(String.join("\n", comment));
                    pr.addLabel("backport");
                    return List.of(CheckWorkItem.fromWorkItem(bot, prId, errorHandler, triggerUpdatedAt));
                } else {
                    var text = "<!-- backport error -->\n" +
                            ":warning: @" + pr.author().username() + " could not find any commit with hash `" +
                            hash.hex() + "`. Please update the title with the hash for an existing commit.";
                    addErrorComment(text, comments);
                    return List.of();
                }
            } else if (pr.title().equals("Merge")) {
                // Update the PR title with the hash of the commit that will become the second parent
                // of the final merge commit (the first parent is always the HEAD of the target branch).
                var targetBranch = new Branch(pr.targetRef());
                var targetBranchWebUrl = pr.repository().webUrl(targetBranch);
                var secondParent = pr.headHash();
                pr.setTitle("Merge " + secondParent.hex());
                var comment = List.of(
                    "<!-- merge parent " + secondParent.hex() + "-->\n",
                    "The first parent of the resulting merge commit from this pull request will be set to the " +
                    "upon integration current `HEAD` of the (" + targetBranch.name() + ")[" + targetBranchWebUrl + "] " +
                    "branch. The second parent of the resulting merge commit from this pull request will be " +
                    "set to `" + secondParent.hex() + "`."
                );
                pr.addComment(String.join("\n", comment));
                return List.of(CheckWorkItem.fromWorkItem(bot, prId, errorHandler, triggerUpdatedAt));
            }

            // Check for a title of the form Backport <issueid>
            if (backportIssueMatcher.matches()) {
                var prefix = getMatchGroup(backportIssueMatcher, "prefix");
                var id = getMatchGroup(backportIssueMatcher, "id");
                var project = bot.issueProject();

                if (!prefix.isEmpty() && !prefix.equalsIgnoreCase(project.name())) {
                    var text = "<!-- backport error -->\n" +
                            ":warning: @" + pr.author().username() + " the issue prefix `" + prefix + "` does not" +
                            " match project [" + project.name() + "](" + project.webUrl() + ").";
                    addErrorComment(text, comments);
                    return List.of();
                }
                var issue = issueTrackerIssue(id);
                if (issue.isEmpty()) {
                    var text = "<!-- backport error -->\n" +
                            ":warning: @" + pr.author().username() + " the issue with id `" + id + "` " +
                            "does not exist in project [" + project.name() + "](" + project.webUrl() + ").";
                    addErrorComment(text, comments);
                    return List.of();
                }
                pr.setTitle(id + ": " + issue.get().title());
                var text = "This backport pull request has now been updated with the original issue," +
                        " but not the original commit. If you have the original commit hash, please update" +
                        " the pull request title with `Backport <hash>`.";
                var comment = pr.addComment(text);
                pr.addLabel("backport");
                logLatency("Time from PR updated to backport comment posted ", comment.createdAt(), log);
                return List.of(CheckWorkItem.fromWorkItem(bot, prId, errorHandler, triggerUpdatedAt));
            }

            // If the title needs updating, we run the check again
            if (updateTitle()) {
                var updatedPr = bot.repo().pullRequest(prId);
                logLatency("Time from PR updated to title corrected ", updatedPr.updatedAt(), log);
                return List.of(CheckWorkItem.fromWorkItem(bot, prId, errorHandler, triggerUpdatedAt));
            }

            if (updateAdditionalIssuesTitle(comments)) {
                var updatedPr = bot.repo().pullRequest(prId);
                logLatency("Time from PR updated to additional issue's title corrected ", updatedPr.updatedAt(), log);
                return List.of(CheckWorkItem.fromWorkItem(bot, prId, errorHandler, triggerUpdatedAt));
            }

            // Check force push
            if (!pr.isDraft()) {
                var lastForcePushTime = pr.lastForcePushTime();
                if (lastForcePushTime.isPresent()) {
                    var lastForcePushSuggestion = comments.stream()
                            .filter(comment -> comment.body().contains(FORCE_PUSH_MARKER))
                            .reduce((a, b) -> b);
                    if (lastForcePushSuggestion.isEmpty() || lastForcePushSuggestion.get().createdAt().isBefore(lastForcePushTime.get())) {
                        log.info("Found force-push for " + pr.repository().name() + "#" + pr.id() + ", adding force-push suggestion");
                        pr.addComment("@" + pr.author().username() + " " + FORCE_PUSH_SUGGESTION + FORCE_PUSH_MARKER);
                    }
                }
            }

            try {
                Repository localRepo = materializeLocalRepo(scratchArea, hostedRepositoryPool);

                var expiresAt = CheckRun.execute(this, pr, localRepo, comments, allReviews,
                        activeReviews, labels, census, bot.useStaleReviews(), bot.integrators(), bot.reviewCleanBackport(),
                        bot.reviewMerge(), bot.approval());
                if (log.isLoggable(Level.INFO)) {
                    // Log latency from the original updatedAt of the PR when this WorkItem
                    // was triggered to when it was just updated by the CheckRun.execute above.
                    // Both timestamps are taken from the PR data so they originate from the
                    // same clock (on the forge). Guard this with isLoggable since we need to
                    // re-fetch the PR data from the forge.
                    var updatedPr = bot.repo().pullRequest(prId);
                    logLatency("Time from PR updated to CheckRun done ", updatedPr.updatedAt(), log);
                }
                if (expiresAt.isPresent()) {
                    bot.scheduleRecheckAt(pr, expiresAt.get());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (pr.isOpen() && pr.labelNames().contains("auto") && pr.labelNames().contains("ready")
                && !pr.labelNames().contains("sponsor") && !unhandledIntegrateCommand(comments)) {
            var comment = pr.addComment("/integrate\n" + PullRequestCommandWorkItem.VALID_BOT_COMMAND_MARKER);
            var autoAdded = pr.labelAddedAt("auto").orElseThrow();
            var readyAdded = pr.labelAddedAt("ready").orElseThrow();
            var latency = Duration.between(autoAdded.isBefore(readyAdded) ? autoAdded : readyAdded, comment.createdAt());
            log.log(Level.INFO, "Time from labels added to /integrate posted " + latency, latency);
        }

        return List.of(new PullRequestCommandWorkItem(bot, prId, errorHandler, triggerUpdatedAt, false));
    }

    /**
     * Only adds comment if not already present
     */
    private void addErrorComment(String text, List<Comment> comments) {
        var botUser = pr.repository().forge().currentUser();
        if (comments.stream()
                .filter(c -> c.author().equals(botUser))
                .noneMatch((c -> c.body().equals(text)))) {
            var comment = pr.addComment(text);
            logLatency("Time from PR updated to check error posted ", comment.createdAt(), log);
        }
    }

    // Lazily initiated
    private Repository localRepo;

    private Repository materializeLocalRepo(ScratchArea scratchArea, HostedRepositoryPool hostedRepositoryPool) throws IOException {
        if (localRepo == null) {
            var localRepoPath = scratchArea.get(pr.repository());
            localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, localRepoPath);
        }
        return localRepo;
    }

    /**
     * Looks through comments for any /integrate command that has not yet been handled.
     * Used to avoid double posting /integrate
     */
    private boolean unhandledIntegrateCommand(List<Comment> comments) {
        var allCommands = PullRequestCommandWorkItem.findAllCommands(pr, comments);
        var handled = PullRequestCommandWorkItem.findHandledCommands(pr, comments);
        return allCommands.stream()
                .filter(ci -> ci.name().equals("integrate"))
                .anyMatch(ci -> !handled.contains(ci.id()));
    }

    private List<Comment> postPlaceholderForReadyComment(List<Comment> comments) {
        var existing = comments.stream()
                .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
                .filter(comment -> comment.body().contains(MERGE_READY_MARKER))
                .findAny();
        if (existing.isPresent()) {
            return comments;
        }
        log.info("Posting placeholder comment");
        String message = "❗ This change is not yet ready to be integrated.\n" +
                "See the **Progress** checklist in the description for automated requirements.\n" +
                MERGE_READY_MARKER + "\n" + PLACEHOLDER_MARKER;
        // If the bot posted a placeholder comment, we should update comments otherwise the bot will not be able to find
        // comment with MERGE_READY_MARKER later and post merge ready comment again
        return Stream.concat(comments.stream(), Stream.of(pr.addComment(message))).toList();
    }

    @Override
    public String workItemName() {
        return "check";
    }
}
