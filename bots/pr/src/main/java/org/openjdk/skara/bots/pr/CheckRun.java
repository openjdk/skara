/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.bots.common.SolvesTracker;
import org.openjdk.skara.census.Contributor;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jbs.Backports;
import org.openjdk.skara.jbs.JdkVersion;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.jcheck.TooFewReviewersIssue;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.*;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;

import static org.openjdk.skara.bots.common.PullRequestConstants.*;

class CheckRun {
    public static final String MSG_EMPTY_BODY = "The pull request body must not be empty.";

    private final CheckWorkItem workItem;
    private final PullRequest pr;
    private final Repository localRepo;
    private final List<Comment> comments;
    private final List<Review> allReviews;
    private final List<Review> activeReviews;
    private final Set<String> labels;
    private final CensusInstance censusInstance;
    private final boolean useStaleReviews;
    private final Set<String> integrators;

    private final Hash baseHash;
    private final CheckablePullRequest checkablePullRequest;

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    protected static final String MERGE_READY_MARKER = "<!-- PullRequestBot merge is ready comment -->";
    protected static final String PLACEHOLDER_MARKER = "<!-- PullRequestBot placeholder -->";
    private static final String OUTDATED_HELP_MARKER = "<!-- PullRequestBot outdated help comment -->";
    private static final String SOURCE_BRANCH_WARNING_MARKER = "<!-- PullRequestBot source branch warning comment -->";
    private static final String MERGE_COMMIT_WARNING_MARKER = "<!-- PullRequestBot merge commit warning comment -->";
    private static final String EMPTY_PR_BODY_MARKER = "<!--\nReplace this text with a description of your pull request (also remove the surrounding HTML comment markers).\n" +
            "If in doubt, feel free to delete everything in this edit box first, the bot will restore the progress section as needed.\n-->";
    private static final String FULL_NAME_WARNING_MARKER = "<!-- PullRequestBot full name warning comment -->";
    private static final String DIFF_TOO_LARGE_WARNING_MARKER = "<!-- PullRequestBot diff too large warning comment -->";
    private static final String APPROVAL_NEEDED_MARKER = "<!-- PullRequestBot approval needed comment -->";
    private static final String BACKPORT_CSR_MARKER = "<!-- PullRequestBot backport csr comment -->";
    private static final Set<String> PRIMARY_TYPES = Set.of("Bug", "New Feature", "Enhancement", "Task", "Sub-task");
    private static final Pattern LABEL_COMMIT_PATTERN = Pattern.compile("<!-- PullRequest Bot label commit '(.*?)' -->");
    protected static final String CSR_PROCESS_LINK = "https://wiki.openjdk.org/display/csr/Main";
    private static final Path JCHECK_CONF_PATH = Path.of(".jcheck", "conf");
    private static final int MESSAGE_LIMIT = 50;
    private final Set<String> newLabels;
    private final boolean reviewCleanBackport;
    private final Approval approval;
    private final boolean reviewersCommandIssued;
    private final ReviewCoverage reviewCoverage;

    private Duration expiresIn;
    // Only set if approval is configured for the repo
    private String realTargetRef;
    private boolean missingApprovalRequest = false;
    private boolean rfrPendingOnOtherWorkItems = false;

    private CheckRun(CheckWorkItem workItem, PullRequest pr, Repository localRepo, List<Comment> comments,
                     List<Review> allReviews, List<Review> activeReviews, Set<String> labels,
                     CensusInstance censusInstance, boolean useStaleReviews, Set<String> integrators, boolean reviewCleanBackport,
                     MergePullRequestReviewConfiguration reviewMerge, Approval approval) throws IOException {
        this.workItem = workItem;
        this.pr = pr;
        this.localRepo = localRepo;
        this.comments = comments;
        this.allReviews = allReviews;
        this.activeReviews = activeReviews;
        this.labels = new HashSet<>(labels);
        this.newLabels = new HashSet<>(labels);
        this.censusInstance = censusInstance;
        this.useStaleReviews = useStaleReviews;
        this.integrators = integrators;
        this.reviewCleanBackport = reviewCleanBackport;
        this.approval = approval;
        this.reviewersCommandIssued = ReviewersTracker.additionalRequiredReviewers(pr.repository().forge().currentUser(), comments).isPresent();

        // If reviewers command is issued, enable reviewers check for merge pull requests
        if (reviewersCommandIssued) {
            reviewMerge = MergePullRequestReviewConfiguration.ALWAYS;
        }

        reviewCoverage = new ReviewCoverage(workItem.bot.useStaleReviews(), workItem.bot.acceptSimpleMerges(), localRepo, pr);
        baseHash = PullRequestUtils.baseHash(pr, localRepo);
        checkablePullRequest = new CheckablePullRequest(pr, localRepo, useStaleReviews,
                workItem.bot.confOverrideRepository().orElse(null),
                workItem.bot.confOverrideName(),
                workItem.bot.confOverrideRef(),
                comments,
                reviewMerge,
                reviewCoverage);
    }

    static Optional<Instant> execute(CheckWorkItem workItem, PullRequest pr, Repository localRepo, List<Comment> comments,
                                     List<Review> allReviews, List<Review> activeReviews, Set<String> labels, CensusInstance censusInstance,
                                     boolean useStaleReviews, Set<String> integrators, boolean reviewCleanBackport, MergePullRequestReviewConfiguration reviewMerge,
                                     Approval approval) throws IOException {
        var run = new CheckRun(workItem, pr, localRepo, comments, allReviews, activeReviews, labels, censusInstance,
                useStaleReviews, integrators, reviewCleanBackport, reviewMerge, approval);
        run.checkStatus();
        if (run.expiresIn != null) {
            return Optional.of(Instant.now().plus(run.expiresIn));
        } else {
            return Optional.empty();
        }
    }

    private boolean isTargetBranchAllowed() {
        if (PreIntegrations.isPreintegrationBranch(pr.targetRef())) {
            return true;
        }
        var matcher = workItem.bot.allowedTargetBranches().matcher(pr.targetRef());
        return matcher.matches();
    }

    /**
     * Builds a map of all associated regular issues, from Issue to IssueTrackerIssue
     * if found. The map is ordered to support consistent presentation order.
     */
    private Map<Issue, Optional<IssueTrackerIssue>> regularIssuesMap() {
        var issue = Issue.fromStringRelaxed(pr.title());
        if (issue.isPresent()) {
            var issues = new ArrayList<Issue>();
            issues.add(issue.get());
            issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), comments, pr.title()));
            var map = new LinkedHashMap<Issue, Optional<IssueTrackerIssue>>();
            if (issueProject() != null) {
                issues.forEach(i -> {
                    var issueTrackerIssue = workItem.issueTrackerIssue(i.shortId());
                    if (issueTrackerIssue.isEmpty()) {
                        log.info("Failed to retrieve issue " + i.id());
                        setExpiration(Duration.ofMinutes(10));
                    }
                    map.put(i, issueTrackerIssue);
                });
            } else {
                issues.forEach(i -> {
                    map.put(i, Optional.empty());
                });
            }
            return map;
        }
        return Map.of();
    }

    /**
     * Constructs a map from main issue ID to CSR issue.
     */
    private Map<String, IssueTrackerIssue> issueToCsrMap(Map<String, List<IssueTrackerIssue>> issueToAllCsrsMap, JdkVersion version) {
        var csrIssueMap = new HashMap<String, IssueTrackerIssue>();
        if (version == null) {
            return Map.of();
        }
        for (var entry : issueToAllCsrsMap.entrySet()) {
            var csrList = entry.getValue();
            Backports.findClosestIssue(csrList, version).ifPresent(csr -> csrIssueMap.put(entry.getKey(), csr));
        }
        return csrIssueMap;
    }

    /**
     * Gets the JEP issue from the IssueProject if there is one
     */
    private Optional<IssueTrackerIssue> jepIssue() {
        if (issueProject() != null) {
            var comment = findJepComment();
            return comment.flatMap(c -> workItem.issueTrackerIssue(new Issue(c.group(2), "").shortId()));
        }
        return Optional.empty();
    }

    private Optional<Matcher> findJepComment() {
        var jepComment = comments.stream()
                .filter(comment -> comment.author().equals(pr.repository().forge().currentUser()))
                .flatMap(comment -> comment.body().lines())
                .map(JEP_MARKER_PATTERN::matcher)
                .filter(Matcher::find)
                .reduce((first, second) -> second);
        if (jepComment.isPresent()) {
            var issueId = jepComment.get().group(2);
            if ("unneeded".equals(issueId)) {
                return Optional.empty();
            }
        }
        return jepComment;
    }

    private IssueProject issueProject() {
        return workItem.bot.issueProject();
    }

    private List<String> allowedTargetBranches() {
        return pr.repository()
                 .branches()
                 .stream()
                 .map(HostedBranch::name)
                 .filter(name -> !PreIntegrations.isPreintegrationBranch(name))
                 .map(name -> workItem.bot.allowedTargetBranches().matcher(name))
                 .filter(Matcher::matches)
                 .map(Matcher::group)
                 .collect(Collectors.toList());
    }

    // Additional bot-specific checks that are not handled by JCheck
    private List<String> botSpecificChecks(boolean isCleanBackport) {
        var ret = new ArrayList<String>();

        var bodyWithoutStatus = bodyWithoutStatus();
        if ((bodyWithoutStatus.isBlank() || bodyWithoutStatus.equals(EMPTY_PR_BODY_MARKER)) && !isCleanBackport) {
            ret.add(MSG_EMPTY_BODY);
        }

        if (!isTargetBranchAllowed()) {
            var error = "The branch `" + pr.targetRef() + "` is not allowed as target branch. The allowed target branches are:\n" +
                    allowedTargetBranches().stream()
                    .map(name -> "   - " + name)
                    .collect(Collectors.joining("\n"));
            ret.add(error);
        }

        for (var blocker : workItem.bot.blockingCheckLabels().entrySet()) {
            if (labels.contains(blocker.getKey())) {
                ret.add(blocker.getValue());
            }
        }

        if (!integrators.isEmpty() && PullRequestUtils.isMerge(pr) && !integrators.contains(pr.author().username())) {
            var error = "Only the designated integrators for this repository are allowed to create merge-style pull requests.";
            ret.add(error);
        }

        // If the bot has label configuration
        if (!workItem.bot.labelConfiguration().allowed().isEmpty()) {
            // If the pr is already auto labelled, check if the pull request is associated with at least one component
            if (workItem.bot.isAutoLabelled(pr)) {
                var existingAllowed = new HashSet<>(pr.labelNames());
                existingAllowed.retainAll(workItem.bot.labelConfiguration().allowed());
                if (existingAllowed.isEmpty()) {
                    ret.add("This pull request must be associated with at least one component. " +
                            "Please use the [/label](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/label)" +
                            " pull request command.");
                }
            } else {
                rfrPendingOnOtherWorkItems = true;
            }
        }

        return ret;
    }

    public static boolean isWithdrawnCSR(IssueTrackerIssue csr) {
        if (csr.isClosed()) {
            var resolution = csr.resolution();
            if (resolution.isPresent()) {
                var name = resolution.get();
                if (name.equals("Withdrawn")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateCSRProgressMessage(IssueTrackerIssue issue) {
        return "Change requires CSR request [" + issue.id() + "](" + issue.webUrl() + ") to be approved";
    }

    // Additional bot-specific progresses that are not handled by JCheck
    private Map<String, Boolean> botSpecificProgresses(Map<Issue, Optional<IssueTrackerIssue>> regularIssuesMap,
                                                       List<IssueTrackerIssue> csrIssueTrackerIssues,
                                                       IssueTrackerIssue jepIssue, JdkVersion version) {
        var ret = new HashMap<String, Boolean>();

        if (approvalNeeded()) {
            for (var issueOpt : regularIssuesMap.values()) {
                if (issueOpt.isPresent()) {
                    var issue = issueOpt.get();
                    var labelNames = issue.labelNames();
                    if (labelNames.contains(approval.approvedLabel(pr.targetRef()))) {
                        ret.put("[" + issue.id() + "](" + issue.webUrl() + ") needs " + approval.approvalTerm(), true);
                    } else {
                        ret.put("[" + issue.id() + "](" + issue.webUrl() + ") needs " + approval.approvalTerm(), false);
                    }
                }
            }
        }

        var csrIssues = csrIssueTrackerIssues.stream()
                .filter(issue -> issue.properties().containsKey("issuetype"))
                .filter(issue -> issue.properties().get("issuetype").asString().equals("CSR"))
                .filter(issue -> !isWithdrawnCSR(issue))
                .toList();
        if (csrIssues.isEmpty() && newLabels.contains("csr")) {
            ret.put("Change requires a CSR request matching fixVersion " + (version != null ? version.raw() : "(No fixVersion in .jcheck/conf)")
                    + " to be approved (needs to be created)", false);
        }
        for (var csrIssue : csrIssues) {
            if (!csrIssue.isClosed()) {
                ret.put(generateCSRProgressMessage(csrIssue), false);
                continue;
            }
            var resolution = csrIssue.resolution();
            if (resolution.isEmpty()) {
                ret.put(generateCSRProgressMessage(csrIssue), false);
                continue;
            }
            if (!resolution.get().equals("Approved")) {
                ret.put(generateCSRProgressMessage(csrIssue), false);
                continue;
            }
            ret.put(generateCSRProgressMessage(csrIssue), true);
        }

        if (jepIssue != null) {
            var jepIssueStatus = jepIssue.status();
            var jepResolution = jepIssue.resolution();
            var jepHasTargeted = "Targeted".equals(jepIssueStatus) ||
                    "Integrated".equals(jepIssueStatus) ||
                    "Completed".equals(jepIssueStatus) ||
                    ("Closed".equals(jepIssueStatus) && jepResolution.isPresent() && "Delivered".equals(jepResolution.get()));
            ret.put("Change requires a JEP request to be targeted", jepHasTargeted);
            if (jepHasTargeted && newLabels.contains("jep")) {
                log.info("JEP issue " + jepIssue.id() + " found in state " + jepIssueStatus + ", removing JEP label from " + describe(pr));
                newLabels.remove(JEP_LABEL);
            } else if (!jepHasTargeted && !newLabels.contains("jep")) {
                log.info("JEP issue " + jepIssue.id() + " found in state " + jepIssueStatus + ", adding JEP label to " + describe(pr));
                newLabels.add(JEP_LABEL);
            }
        }
        return ret;
    }

    private void setExpiration(Duration expiresIn) {
        // Use the shortest expiration
        if (this.expiresIn == null || this.expiresIn.compareTo(expiresIn) > 0) {
            this.expiresIn = expiresIn;
        }
    }

    private Map<String, String> blockingIntegrationLabels() {
        return Map.of("rejected", "The change is currently blocked from integration by a rejection.");
    }

    private List<String> botSpecificIntegrationBlockers(Map<Issue, Optional<IssueTrackerIssue>> issues) {
        var ret = new ArrayList<String>();

        if (issueProject() != null) {
            for (var issueEntry : issues.entrySet()) {
                var issue = issueEntry.getKey();
                var issueTrackerIssue = issueEntry.getValue();
                try {
                    if (issueTrackerIssue.isPresent()) {
                        if (!relaxedEquals(issueTrackerIssue.get().title(), issue.description())) {
                            var issueString = "[" + issueTrackerIssue.get().id() + "](" + issueTrackerIssue.get().webUrl() + ")";
                            ret.add("Title mismatch between PR and JBS for issue " + issueString);
                            setExpiration(Duration.ofMinutes(10));
                        }

                        var properties = issueTrackerIssue.get().properties();
                        if (!properties.containsKey("issuetype")) {
                            var issueString = "[" + issueTrackerIssue.get().id() + "](" + issueTrackerIssue.get().webUrl() + ")";
                            ret.add("Issue " + issueString + " does not contain property `issuetype`");
                            setExpiration(Duration.ofMinutes(10));
                        } else {
                            var issueType = properties.get("issuetype").asString();
                            if (!PRIMARY_TYPES.contains(issueType)) {
                                ret.add("Issue of type `" + issueType + "` is not allowed for integrations");
                                setExpiration(Duration.ofMinutes(10));
                            }
                        }
                    } else {
                        ret.add("Failed to retrieve information on issue `" + issue.id() +
                                "`. Please make sure it exists and is accessible.");
                        setExpiration(Duration.ofMinutes(10));
                    }
                } catch (RuntimeException e) {
                    ret.add("Failed to retrieve information on issue `" + issue.id() +
                            "`. This may be a temporary failure and will be retried.");
                    setExpiration(Duration.ofMinutes(30));
                }
            }
        }

        labels.stream()
              .filter(l -> blockingIntegrationLabels().containsKey(l))
              .forEach(l -> ret.add(blockingIntegrationLabels().get(l)));

        var dep = PreIntegrations.dependentPullRequestId(pr);
        dep.ifPresent(s -> ret.add("Dependency #" + s + " must be integrated first"));

        return ret;
    }

    private void updateCheckBuilder(CheckBuilder checkBuilder, PullRequestCheckIssueVisitor visitor, List<String> additionalErrors) {
        if (visitor.isReadyForReview() && additionalErrors.isEmpty()) {
            checkBuilder.complete(true);
            // It means some jchecks failed as warnings
            if (!visitor.getAnnotations().isEmpty()) {
                checkBuilder.title("Optional");
                checkBuilder.summary("These warnings will not block integration.");
                for (var annotation : visitor.getAnnotations()) {
                    checkBuilder.annotation(annotation);
                }
            }
        } else {
            checkBuilder.title("Required");
            var summary = Stream.concat(visitor.errorFailedChecksMessages().stream().limit(MESSAGE_LIMIT), additionalErrors.stream().limit(MESSAGE_LIMIT))
                    .sorted()
                    .map(m -> "- " + m)
                    .collect(Collectors.joining("\n"));
            if (visitor.errorFailedChecksMessages().size() > MESSAGE_LIMIT || additionalErrors.size() > MESSAGE_LIMIT) {
                summary = summary + "\nThere are more errors that are not displayed due to the size limit.";
            }
            checkBuilder.summary(summary);
            for (var annotation : visitor.getAnnotations()) {
                checkBuilder.annotation(annotation);
            }
            checkBuilder.complete(false);
        }
    }

    private boolean updateReadyForReview(PullRequestCheckIssueVisitor visitor, List<String> additionalErrors, Map<Issue, Optional<IssueTrackerIssue>> regularIssuesMap) {
        // All the issues must be accessible
        if (issueProject() != null && regularIssuesMap.values().stream().anyMatch(Optional::isEmpty)) {
            return false;
        }

        // Additional errors are not allowed
        if (!additionalErrors.isEmpty()) {
            newLabels.remove("rfr");
            return false;
        }

        // Draft requests are not for review
        if (pr.isDraft()) {
            newLabels.remove("rfr");
            return false;
        }

        // Check if the visitor found any issues that should be resolved before reviewing
        if (!visitor.isReadyForReview()) {
            newLabels.remove("rfr");
            return false;
        }

        // If rfr is still pending on other workItems, so don't actively mark this pr as rfr, wait for another round of CheckWorkItem
        if (rfrPendingOnOtherWorkItems) {
            log.info("rfr is pending on other workItems for pr: " + pr.id());
            return newLabels.contains("rfr");
        }

        // No issues found, add rfr label now
        newLabels.add("rfr");
        return true;
    }

    private boolean updateClean(Commit commit) {
        var backportDiff = commit.parentDiffs().get(0);
        var prDiff = pr.diff();
        if (!backportDiff.complete() || !prDiff.complete()) {
            // Add diff too large warning comment
            addDiffTooLargeWarning();
            return false;
        }
        var isClean = DiffComparator.areFuzzyEqual(backportDiff, prDiff);
        var hasCleanLabel = labels.contains("clean");
        if (isClean && !hasCleanLabel) {
            log.info("Adding label clean");
            pr.addLabel("clean");
        }

        var botUser = pr.repository().forge().currentUser();
        var isCleanLabelManuallyAdded = comments
                .stream()
                .filter(c -> c.author().equals(botUser))
                .anyMatch(c -> c.body().contains("This backport pull request is now marked as clean"));

        if (!isCleanLabelManuallyAdded && !isClean && hasCleanLabel) {
            log.info("Removing label clean");
            pr.removeLabel("clean");
        }

        return isClean || isCleanLabelManuallyAdded;
    }

    private void updateMergeClean(Commit commit) {
        boolean isClean = !commit.isMerge() || localRepo.isEmptyCommit(commit.hash());
        if (isClean) {
            newLabels.add("clean");
        } else {
            newLabels.remove("clean");
        }
    }

    private Optional<HostedCommit> backportedFrom() {
        var hash = checkablePullRequest.findOriginalBackportHash();
        if (hash == null) {
            return Optional.empty();
        }
        var repoName = checkablePullRequest.findOriginalBackportRepo();
        if (repoName == null) {
            repoName = pr.repository().forge().search(hash).orElseThrow();
        }
        var repo = pr.repository().forge().repository(repoName);
        if (repo.isEmpty()) {
            throw new IllegalStateException("Backport comment for PR " + pr.id() + " contains bad repo name: " + repoName);
        }
        var commit = repo.get().commit(hash, true);
        if (commit.isEmpty()) {
            throw new IllegalStateException("Backport comment for PR " + pr.id() + " contains bad hash: " + hash.hex());
        }
        return commit;
    }

    private String getRole(String username) {
        var project = censusInstance.project();
        var version = censusInstance.census().version().format();
        if (project.isReviewer(username, version)) {
            return "**Reviewer**";
        } else if (project.isCommitter(username, version)) {
            return "Committer";
        } else if (project.isAuthor(username, version)) {
            return "Author";
        } else {
            return "no project role";
        }
    }

    private String formatReviewer(HostUser reviewer) {
        var contributor = censusInstance.namespace().get(reviewer.id());
        return formatUser(reviewer, contributor);
    }

    /**
     * Format the contributor user information.
     * If both the HostUser and the Contributor are not null, return `[FullName](Link) (@user - RoleName)`
     * If the HostUser is not null and the Contributor is null, return `@user (Unknown ProjectName username and role)`
     * If the HostUser is null and the Contributor is not null, return `[FullName](Link) - RoleName` or FullName - RoleName
     * If both the HostUser and the Contributor are null, return: null string
     */
    private String formatUser(HostUser user, Contributor contributor) {
        if (contributor == null && user == null) {
            return "";
        }
        var ret = new StringBuilder();
        if (contributor != null && user != null) {
            // Both the HostUser and the Contributor are not null
            ret.append(contributorLink(contributor));
            ret.append(" (@");
            ret.append(user.username());
            ret.append(" - ");
            ret.append(getRole(contributor.username()));
            ret.append(")");
            return ret.toString();
        } else if (contributor == null) {
            // The HostUser is not null and the Contributor is null
            ret.append("@");
            ret.append(user.username());
            ret.append(" (no known ");
            ret.append(censusInstance.configuration().census().domain());
            ret.append(" user name / role)");
        } else {
            // The HostUser is null and the Contributor is not null
            ret.append(contributorLink(contributor));
            ret.append(" - ");
            ret.append(getRole(contributor.username()));
        }
        return ret.toString();
    }

    private String contributorLink(Contributor contributor) {
        var ret = new StringBuilder();
        var censusLink = workItem.bot.censusLink(contributor);
        if (censusLink.isPresent()) {
            ret.append("[");
        }
        ret.append(contributor.fullName().orElse(contributor.username()));
        if (censusLink.isPresent()) {
            ret.append("](");
            ret.append(censusLink.get());
            ret.append(")");
        }
        return ret.toString();
    }

    private String getChecksList(PullRequestCheckIssueVisitor visitor, boolean reviewNeeded, Map<String, Boolean> additionalProgresses) {
        var checks = reviewNeeded ? visitor.getChecks() : visitor.getReadyForReviewChecks();
        checks.putAll(additionalProgresses);
        return checks.entrySet().stream()
                .map(entry -> "- [" + (entry.getValue() ? "x" : " ") + "] " + entry.getKey())
                .collect(Collectors.joining("\n"));
    }

    private String warningListToText(List<String> additionalErrors) {
        var text = additionalErrors.stream()
                .sorted()
                .limit(MESSAGE_LIMIT)
                .map(err -> "&nbsp;⚠️ " + err)
                .collect(Collectors.joining("\n"));
        if (additionalErrors.size() > MESSAGE_LIMIT) {
            text = text + "\n...";
        }
        return text;
    }

    private Optional<String> getReviewersList(List<Review> reviews, boolean tooFewReviewers) {
        var reviewers = reviews.stream()
                .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                .map(review -> {
                    var entry = " * " + formatReviewer(review.reviewer());
                    if (!review.targetRef().equals(pr.targetRef())) {
                        if (useStaleReviews || tooFewReviewers) {
                            entry += " 🔄 Re-review required (review was made when pull request targeted the [" + review.targetRef()
                                    + "](" + pr.repository().webUrl(new Branch(review.targetRef())) + ") branch)";
                        } else {
                            entry += " Review was made when pull request targeted the [" + review.targetRef()
                                    + "](" + pr.repository().webUrl(new Branch(review.targetRef())) + ") branch";
                        }
                    } else {
                        var hash = review.hash();
                        if (hash.isPresent()) {
                            if (!hash.get().equals(pr.headHash())) {
                                if (useStaleReviews) {
                                    entry += " ⚠️ Review applies to [" + hash.get().abbreviate()
                                            + "](" + pr.filesUrl(hash.get()) + ")";
                                } else if (!reviewCoverage.covers(review) && tooFewReviewers) {
                                    entry += " 🔄 Re-review required (review applies to [" + hash.get().abbreviate()
                                            + "](" + pr.filesUrl(hash.get()) + "))";
                                } else {
                                    entry += " Review applies to [" + hash.get().abbreviate()
                                            + "](" + pr.filesUrl(hash.get()) + ")";
                                }
                            }
                        } else {
                            if (useStaleReviews || tooFewReviewers) {
                                entry += " 🔄 Re-review required (review applies to a commit that is no longer present)";
                            } else {
                                entry += " Review applies to a commit that is no longer present";
                            }
                        }
                    }
                    return entry;
                })
                .collect(Collectors.joining("\n"));

        // Check for manually added reviewers
        if (useStaleReviews) {
            var namespace = censusInstance.namespace();
            var allReviewers = CheckablePullRequest.reviewerNames(activeReviews, namespace);
            var additionalEntries = new ArrayList<String>();
            for (var additional : Reviewers.reviewers(pr.repository().forge().currentUser(), comments)) {
                if (!allReviewers.contains(additional)) {
                    var userInfo = formatUser(null, censusInstance.census().contributor(additional));
                    additionalEntries.add(" * " + userInfo + " ⚠️ Added manually");
                }
            }
            if (!reviewers.isBlank()) {
                reviewers += "\n";
            }
            reviewers += String.join("\n", additionalEntries);
        }

        if (reviewers.length() > 0) {
            return Optional.of(reviewers);
        } else {
            return Optional.empty();
        }
    }

    private String formatContributor(EmailAddress contributor) {
        var name = contributor.fullName().orElseThrow();
        return name + " `<" + contributor.address() + ">`";
    }

    private Optional<String> getContributorsList() {
        var contributors = Contributors.contributors(pr.repository().forge().currentUser(), comments)
                                       .stream()
                                       .map(c -> " * " + formatContributor(c))
                                       .collect(Collectors.joining("\n"));
        if (contributors.length() > 0) {
            return Optional.of(contributors);
        } else {
            return Optional.empty();
        }
    }

    static boolean relaxedEquals(String s1, String s2) {
        return s1.trim()
                 .replaceAll("\\s+", " ")
                 .equalsIgnoreCase(s2.trim()
                                     .replaceAll("\\s+", " "));
    }

    private String getStatusMessage(PullRequestCheckIssueVisitor visitor,
            List<String> additionalErrors, Map<String, Boolean> additionalProgresses,
            List<String> integrationBlockers, List<String> warnings, boolean reviewNeeded,
            Map<Issue, Optional<IssueTrackerIssue>> regularIssuesMap,
            IssueTrackerIssue jepIssue, Collection<IssueTrackerIssue> csrIssues, JdkVersion version, boolean tooFewReviewers) {
        var progressBody = new StringBuilder();
        progressBody.append("---------\n");
        progressBody.append("### Progress\n");
        progressBody.append(getChecksList(visitor, reviewNeeded, additionalProgresses));

        var allAdditionalErrors = Stream.concat(visitor.hiddenErrorMessages().stream(), additionalErrors.stream())
                                        .sorted()
                                        .collect(Collectors.toList());
        if (!allAdditionalErrors.isEmpty()) {
            progressBody.append("\n\n### Error");
            if (allAdditionalErrors.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            progressBody.append(warningListToText(allAdditionalErrors));
        }

        if (!integrationBlockers.isEmpty()) {
            progressBody.append("\n\n### Integration blocker");
            if (integrationBlockers.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            progressBody.append(warningListToText(integrationBlockers));
        }

        var allWarnings = Stream.concat(visitor.hiddenWarningMessages().stream(), warnings.stream()).toList();
        if (!allWarnings.isEmpty()) {
            progressBody.append("\n\n### Warning");
            if (allWarnings.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            progressBody.append(warningListToText(allWarnings));
        }

        // All the issues this pr related(except CSR and JEP)
        var currentIssues = new HashSet<String>();
        var issueProject = issueProject();
        if (issueProject != null && !regularIssuesMap.isEmpty()) {
            progressBody.append("\n\n### Issue");
            if (regularIssuesMap.size() + csrIssues.size() > 1 || jepIssue != null) {
                progressBody.append("s");
            }
            progressBody.append("\n");

            var requestPresent = false;

            for (var issueEntry : regularIssuesMap.entrySet()) {
                var issue = issueEntry.getKey();
                progressBody.append(" * ");
                if (issue.project().isPresent() && !issue.project().get().equals(issueProject.name())) {
                    progressBody.append("⚠️ Issue `");
                    progressBody.append(issue.id());
                    progressBody.append("` does not belong to the `");
                    progressBody.append(issueProject.name());
                    progressBody.append("` project.");
                } else {
                    var issueTrackerIssue = issueEntry.getValue();
                    if (issueTrackerIssue.isPresent()) {
                        currentIssues.add(issueTrackerIssue.get().id());
                        formatIssue(progressBody, issueTrackerIssue.get());
                        var issueType = issueTrackerIssue.get().properties().get("issuetype");
                        if (issueType != null) {
                            progressBody.append(" (**").append(issueType.asString()).append("**");
                            var issuePriority = issueTrackerIssue.get().properties().get("priority");
                            if (issuePriority != null) {
                                progressBody.append(" - P").append(issuePriority.asString());
                            }
                            if (approvalNeeded()) {
                                String status = "";
                                var labels = issueTrackerIssue.get().labelNames();
                                if (labels.contains(approval.rejectedLabel(realTargetRef))) {
                                    status = "Rejected";
                                } else if (labels.contains(approval.approvedLabel(realTargetRef))) {
                                    status = "Approved";
                                } else if (labels.contains(approval.requestedLabel(realTargetRef))) {
                                    status = "Requested";
                                    requestPresent = true;
                                } else {
                                    missingApprovalRequest = true;
                                }
                                if (!status.isEmpty()) {
                                    progressBody.append(" - ").append(status);
                                }
                            }
                            progressBody.append(")");
                        }
                        if (workItem.bot.versionMismatchWarning() && issueTrackerIssue.get().isOpen()
                                && version != null && issueType != null && PRIMARY_TYPES.contains(issueType.asString())) {
                            var existing = Backports.findIssue(issueTrackerIssue.get(), version);
                            if (existing.isEmpty()) {
                                var fixVersions = Backports.fixVersions(issueTrackerIssue.get());
                                progressBody.append("(⚠️ The fixVersion in this issue is " + fixVersions +
                                        " but the fixVersion in .jcheck/conf is " + version.raw() + ", " +
                                        "a new backport will be created when this pr is integrated.)");
                            }
                        }
                        if (!relaxedEquals(issueTrackerIssue.get().title(), issue.description())) {
                            progressBody.append(" ⚠️ Title mismatch between PR and JBS.");
                            setExpiration(Duration.ofMinutes(10));
                        }
                        if (!issueTrackerIssue.get().isOpen()) {
                            if (!newLabels.contains("backport") &&
                                    (issueType == null || !List.of("CSR", "JEP").contains(issueType.asString()))) {
                                if (issueTrackerIssue.get().isFixed()) {
                                    progressBody.append(" ⚠️ Issue is already resolved. " +
                                            "Consider making this a \"backport pull request\" by setting " +
                                            "the PR title to `Backport <hash>` with the hash of the original commit. " +
                                            "See [Backports](https://wiki.openjdk.org/display/SKARA/Backports).");
                                } else {
                                    progressBody.append(" ⚠️ Issue is not open.");
                                }
                            }
                        }
                    } else {
                        progressBody.append("⚠️ Failed to retrieve information on issue `");
                        progressBody.append(issue.id());
                        progressBody.append("`.");
                    }
                }
                progressBody.append("\n");
            }

            if (requestPresent) {
                newLabels.add(APPROVAL_LABEL);
            } else {
                newLabels.remove(APPROVAL_LABEL);
            }
            if (jepIssue != null) {
                currentIssues.add(jepIssue.id());
                progressBody.append(" * ");
                formatIssue(progressBody, jepIssue);
                progressBody.append(" (**JEP**)");
                progressBody.append("\n");
            }
            for (var csrIssue : csrIssues) {
                currentIssues.add(csrIssue.id());
                progressBody.append(" * ");
                formatIssue(progressBody, csrIssue);
                progressBody.append(" (**CSR**)");
                if (isWithdrawnCSR(csrIssue)) {
                    progressBody.append(" (Withdrawn)");
                }
                progressBody.append("\n");
            }

            // Update the issuePRMap
            var prRecord = new PRRecord(pr.repository().name(), pr.id());

            // Need previousIssues to delete associations
            var previousIssues = BotUtils.parseAllIssues(pr.body());
            // Add associations
            for (String issueId : currentIssues) {
                if (!previousIssues.contains(issueId)) {
                    workItem.bot.addIssuePRMapping(issueId, prRecord);
                }
            }
            // Delete associations
            for (String oldIssueId : previousIssues) {
                if (!currentIssues.contains(oldIssueId)) {
                    workItem.bot.removeIssuePRMapping(oldIssueId, prRecord);
                }
            }
        }

        // Generate Reviewers list for recognized users
        var recognizedReviews = activeReviews.stream()
                .filter(review -> censusInstance.contributor(review.reviewer()).isPresent())
                .toList();
        getReviewersList(recognizedReviews, tooFewReviewers).ifPresent(reviewers -> {
            progressBody.append("\n\n### Reviewers\n");
            progressBody.append(reviewers);
        });

        // Generate Reviewers list for reviewers without OpenJDK IDs
        var nonRecognizedReviews = activeReviews.stream()
                .filter(review -> censusInstance.contributor(review.reviewer()).isEmpty())
                .toList();
        getReviewersList(nonRecognizedReviews, tooFewReviewers).ifPresent(reviewers -> {
            progressBody.append("\n\n### Reviewers without OpenJDK IDs\n");
            progressBody.append(reviewers);
        });

        getContributorsList().ifPresent(contributors -> {
            progressBody.append("\n\n### Contributors\n");
            progressBody.append(contributors);
        });

        progressBody.append("\n\n### Reviewing\n");
        progressBody.append(makeCollapsible("Using <code>git</code>", reviewUsingGitHelp()));
        progressBody.append(makeCollapsible("Using Skara CLI tools", reviewUsingSkaraHelp()));
        progressBody.append(makeCollapsible("Using diff file", reviewUsingDiffsHelp()));

        var webrevCommentLink = getWebrevCommentLink();
        if (webrevCommentLink.isPresent()) {
            progressBody.append(makeCollapsible("Using Webrev", webrevCommentLink.get()));
        }
        return progressBody.toString();
    }

    private static void formatIssue(StringBuilder progressBody, IssueTrackerIssue issueTrackerIssue) {
        progressBody.append("[");
        progressBody.append(issueTrackerIssue.id());
        progressBody.append("](");
        progressBody.append(issueTrackerIssue.webUrl());
        progressBody.append("): ");
        progressBody.append(BotUtils.escape(issueTrackerIssue.title()));
    }

    private Optional<String> getWebrevCommentLink() {
        var webrevComment = comments.stream()
                .filter(comment -> comment.author().username().equals(workItem.bot.mlbridgeBotName()))
                .filter(comment -> comment.body().contains(WEBREV_COMMENT_MARKER))
                .findFirst();
        return webrevComment.map(comment -> "[Link to Webrev Comment](" + pr.commentUrl(comment).toString() + ")");
    }

    private static String makeCollapsible(String summary, String content) {
        // The linebreaks are important in getting this properly parsed
        return "<details><summary>" + summary + "</summary>\n" +
                "\n" +
                content + "\n" +
                "</details>\n";
    }

    private String reviewUsingGitHelp() {
        var repoUrl = pr.repository().url();
        var firstTime =
           "`$ git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id() + "` \\\n" +
           "`$ git checkout pull/" + pr.id() + "`\n";
        var updating =
           "`$ git checkout pull/" + pr.id() + "` \\\n" +
           "`$ git pull " + repoUrl + " " + pr.fetchRef() + "`\n";

        return "Checkout this PR locally: \\\n" +
                firstTime +
                "\n" +
                "Update a local copy of the PR: \\\n" +
                updating;
    }

    private String reviewUsingSkaraHelp() {
        return "Checkout this PR locally: \\\n" +
                ("`$ git pr checkout " + pr.id() + "`\n") +
                "\n" +
                "View PR using the GUI difftool: \\\n" +
                ("`$ git pr show -t " + pr.id() + "`\n");
    }

    private String reviewUsingDiffsHelp() {
        var diffUrl = pr.repository().diffUrl(pr.id());
        return "Download this PR as a diff file: \\\n" +
                "<a href=\"" + diffUrl + "\">" + diffUrl + "</a>\n";
    }

    private String bodyWithoutStatus() {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(PROGRESS_MARKER);
        return (markerIndex < 0 ?
                description :
                description.substring(0, markerIndex)).trim();
    }

    private String updateStatusMessage(String message) {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(PROGRESS_MARKER);

        if (markerIndex >= 0 && description.substring(markerIndex).equals(message)) {
            log.info("Progress already up to date");
            return description;
        }
        var originalBody = bodyWithoutStatus();
        if (originalBody.isBlank()) {
            originalBody = EMPTY_PR_BODY_MARKER;
        }
        var newBody = originalBody + "\n\n" + PROGRESS_MARKER + "\n" + message;

        // Retrieve the body again here to lower the chance of concurrent updates
        var latestPR = pr.repository().pullRequest(pr.id());
        if (description.equals(latestPR.body())) {
            log.info("Updating PR body");
            pr.setBody(newBody);
        } else {
            // The modification should trigger another round of checks, so
            // no need to force a retry by throwing a RuntimeException.
            log.info("PR body has been modified, won't update PR body this time");
            return description;
        }
        return newBody;
    }

    private Optional<Comment> findComment(String marker) {
        var self = pr.repository().forge().currentUser();
        return comments.stream()
                       .filter(comment -> comment.author().equals(self))
                       .filter(comment -> comment.body().contains(marker))
                       .findAny();
    }

    private String getMergeReadyComment(String commitMessage) {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" This change now passes all *automated* pre-integration checks.");

        try {
            var hasContributingFile =
                !localRepo.files(checkablePullRequest.targetHash(), Path.of("CONTRIBUTING.md")).isEmpty();
            if (hasContributingFile) {
                message.append("\n\nℹ️ This project also has non-automated pre-integration requirements. Please see the file ");
                message.append("[CONTRIBUTING.md](https://github.com/");
                message.append(pr.repository().name());
                message.append("/blob/");
                message.append(pr.targetRef());
                message.append("/CONTRIBUTING.md) for details.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (labels.stream().anyMatch(label -> workItem.bot.twoReviewersLabels().contains(label))) {
            message.append("\n\n");
            message.append(":mag: One or more changes in this pull request modifies files in areas of ");
            message.append("the source code that often require two reviewers. Please consider if this is ");
            message.append("the case for this pull request, and if so, await a second reviewer to approve ");
            message.append("this pull request before you integrate it.");
        }

        if (labels.stream().anyMatch(label -> workItem.bot.twentyFourHoursLabels().contains(label))) {
            var rfrAt = pr.labelAddedAt("rfr");
            if (rfrAt.isPresent() && ZonedDateTime.now().minusHours(24).isBefore(rfrAt.get())) {
                message.append("\n\n");
                message.append(":earth_americas: Applicable reviewers for one or more changes in this pull request are spread across ");
                message.append("multiple different time zones. Please consider waiting with integrating this pull request until it has ");
                message.append("been out for review for at least 24 hours to give all reviewers a chance to review the pull request.");
            }
        }

        message.append("\n\n");
        message.append("After integration, the commit message for the final commit will be:\n");
        message.append("```\n");
        message.append(commitMessage);
        message.append("\n```\n");

        message.append("You can use [pull request commands](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands) ");
        message.append("such as [/summary](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/summary), ");
        message.append("[/contributor](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/contributor) and ");
        message.append("[/issue](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/issue) to adjust it as needed.");
        message.append("\n\n");

        var divergingCommits = checkablePullRequest.divergingCommits();
        if (divergingCommits.size() > 0) {
            message.append("\n");
            message.append("At the time when this comment was updated there had been ");
            if (divergingCommits.size() == 1) {
                message.append("1 new commit ");
            } else {
                message.append(divergingCommits.size());
                message.append(" new commits ");
            }
            message.append("pushed to the `");
            message.append(pr.targetRef());
            message.append("` branch:\n\n");
            divergingCommits.stream()
                            .limit(CheckablePullRequest.COMMIT_LIST_LIMIT)
                            .forEach(c -> message.append(" * ").append(c.hash().hex()).append(": ").append(c.message().get(0)).append("\n"));
            if (divergingCommits.size() > CheckablePullRequest.COMMIT_LIST_LIMIT) {
                message.append(" * ... and ").append(divergingCommits.size() -CheckablePullRequest. COMMIT_LIST_LIMIT).append(" more: ")
                       .append(pr.repository().webUrl(baseHash.hex(), pr.targetRef())).append("\n");
            } else {
                message.append("\n");
                message.append("Please see [this link](");
                message.append(pr.repository().webUrl(baseHash.hex(), pr.targetRef()));
                message.append(") for an up-to-date comparison between the source branch of this pull request and the `");
                message.append(pr.targetRef());
                message.append("` branch.");
            }

            message.append("\n");
            message.append("As there are no conflicts, your changes will automatically be rebased on top of ");
            message.append("these commits when integrating. If you prefer to avoid this automatic rebasing");
        } else {
            message.append("\n");
            message.append("At the time when this comment was updated there had been no new commits pushed to the `");
            message.append(pr.targetRef());
            message.append("` branch. If another commit should be pushed before ");
            message.append("you perform the `/integrate` command, your PR will be automatically rebased. If you prefer to avoid ");
            message.append("any potential automatic rebasing");
        }
        message.append(", please check the documentation for the ");
        message.append("[/integrate](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) ");
        message.append("command for further details.\n");

        if (!censusInstance.isCommitter(pr.author())) {
            message.append("\n");
            message.append("As you do not have [Committer](https://openjdk.org/bylaws#committer) status in ");
            message.append("[this project](https://openjdk.org/census#");
            message.append(censusInstance.project().name());
            message.append(") an existing Committer must agree to ");
            message.append("[sponsor](https://openjdk.org/sponsor/) your change. ");
            var candidates = activeReviews.stream()
                                    .filter(review -> censusInstance.isCommitter(review.reviewer()))
                                    .map(review -> "@" + review.reviewer().username())
                                    .collect(Collectors.joining(", "));
            if (candidates.length() > 0) {
                message.append("Possible candidates are the reviewers of this PR (");
                message.append(candidates);
                message.append(") but any other Committer may sponsor as well. ");
            }
            message.append("\n\n");
            message.append("➡️ To flag this PR as ready for integration with the above commit message, type ");
            message.append("`/integrate` in a new comment. (Afterwards, your sponsor types ");
            message.append("`/sponsor` in a new comment to perform the integration).\n");
        } else {
            message.append("\n");
            message.append("➡️ To integrate this PR with the above commit message to the `").append(pr.targetRef()).append("` branch, type ");
            message.append("[/integrate](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) ");
            message.append("in a new comment.\n");
        }
        message.append(MERGE_READY_MARKER);
        return message.toString();
    }

    private String getMergeNoLongerReadyComment() {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" This change is no longer ready for integration - check the PR body for details.\n");
        message.append(MERGE_READY_MARKER);
        return message.toString();
    }

    private void addFullNameWarningComment() {
        var existing = findComment(FULL_NAME_WARNING_MARKER);
        if (existing.isPresent()) {
            // Only warn once
            return;
        }

        if (censusInstance.namespace().get(pr.author().id()) != null) {
            // Known OpenJDK user
            return;
        }

        var head = pr.repository().commit(pr.headHash()).orElseThrow(
            () -> new IllegalStateException("Cannot lookup HEAD hash for PR " + pr.id())
        );
        // Normalize the strings before comparison to ensure unicode characters are encoded in the same way
        var prAuthorFullName = Normalizer.normalize(pr.author().fullName(), Normalizer.Form.NFC);
        var prAuthorUserName = Normalizer.normalize(pr.author().username(), Normalizer.Form.NFC);
        var headAuthorName = Normalizer.normalize(head.author().name(), Normalizer.Form.NFC);
        if (!prAuthorFullName.equals(prAuthorUserName) && !prAuthorFullName.equals(headAuthorName)) {
            var headUrl = pr.headUrl().toString();
            var message = ":warning: @" + pr.author().username() + " the full name on your profile does not match " +
                "the author name in this pull requests' [HEAD](" + headUrl + ") commit. " +
                          "If this pull request gets integrated then the author name from this pull requests' " +
                          "[HEAD](" + headUrl + ") commit will be used for the resulting commit. " +
                          "If you wish to push a new commit with a different author name, " +
                          "then please run the following commands in a local repository of your personal fork:" +
                          "\n\n" +
                          "```\n" +
                          "$ git checkout " + pr.sourceRef() + "\n" +
                          "$ git commit --author='Preferred Full Name <you@example.com>' --allow-empty -m 'Update full name'\n" +
                          "$ git push\n" +
                          "```\n";
            pr.addComment(FULL_NAME_WARNING_MARKER + "\n" + message);
        }
    }

    private void updateMergeReadyComment(boolean isReady, String commitMessage, boolean rebasePossible) {
        var existing = findComment(MERGE_READY_MARKER);
        if (isReady && rebasePossible) {
            addFullNameWarningComment();
            var message = getMergeReadyComment(commitMessage);
            if (existing.isEmpty()) {
                log.info("Adding merge ready comment");
                pr.addComment(message);
            } else {
                if (!existing.get().body().equals(message)) {
                    log.info("Updating merge ready comment");
                    pr.updateComment(existing.get().id(), message);
                } else {
                    log.info("Merge ready comment already exists, no need to update");
                }
            }
        } else if (existing.isPresent() && !existing.get().body().contains(PLACEHOLDER_MARKER)) {
            var message = getMergeNoLongerReadyComment();
            if (!existing.get().body().equals(message)) {
                log.info("Updating no longer ready comment");
                pr.updateComment(existing.get().id(), message);
            } else {
                log.info("No longer ready comment already exists, no need to update");
            }
        }
    }

    private void addSourceBranchWarningComment() {
        var existing = findComment(SOURCE_BRANCH_WARNING_MARKER);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var branch = pr.sourceRef();
        var message = ":warning: @" + pr.author().username() + " " +
            "a branch with the same name as the source branch for this pull request (`" + branch + "`) " +
            "is present in the [target repository](" + pr.repository().nonTransformedWebUrl() + "). " +
            "If you eventually integrate this pull request then the branch `" + branch + "` " +
            "in your [personal fork](" + pr.sourceRepository().orElseThrow().nonTransformedWebUrl() + ") will diverge once you sync " +
            "your personal fork with the upstream repository.\n" +
            "\n" +
            "To avoid this situation, create a new branch for your changes and reset the `" + branch + "` branch. " +
            "You can do this by running the following commands in a local repository for your personal fork. " +
            "_Note_: you do *not* have to name the new branch `NEW-BRANCH-NAME`." +
            "\n" +
            "```" +
            "$ git checkout " + branch + "\n" +
            "$ git checkout -b NEW-BRANCH-NAME\n" +
            "$ git branch -f " + branch + " " + baseHash.hex() + "\n" +
            "$ git push -f origin " + branch + "\n" +
            "```\n" +
            "\n" +
            "Then proceed to create a new pull request with `NEW-BRANCH-NAME` as the source branch and " +
            "close this one.\n" +
            SOURCE_BRANCH_WARNING_MARKER;
        log.info("Adding source branch warning comment");
        pr.addComment(message);
    }

    private void addOutdatedComment() {
        var existing = findComment(OUTDATED_HELP_MARKER);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var message = "@" + pr.author().username() + " this pull request can not be integrated into " +
                "`" + pr.targetRef() + "` due to one or more merge conflicts. To resolve these merge conflicts " +
                "and update this pull request you can run the following commands in the local repository for your personal fork:\n" +
                "```bash\n" +
                "git checkout " + pr.sourceRef() + "\n" +
                "git fetch " + pr.repository().url() + " " + pr.targetRef() + "\n" +
                "git merge FETCH_HEAD\n" +
                "# resolve conflicts and follow the instructions given by git merge\n" +
                "git commit -m \"Merge " + pr.targetRef() + "\"\n" +
                "git push\n" +
                "```\n" +
                OUTDATED_HELP_MARKER;
        log.info("Adding merge conflict comment");
        pr.addComment(message);
    }

    private void addMergeCommitWarningComment() {
        var existing = findComment(MERGE_COMMIT_WARNING_MARKER);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }

        var defaultBranch = Branch.defaultFor(VCS.GIT);
        var message = "⚠️  @" + pr.author().username() +
                      " This pull request contains merges that bring in commits not present in the target repository." +
                      " Since this is not a \"merge style\" pull request, these changes will be squashed when this pull request in integrated." +
                      " If this is your intention, then please ignore this message. If you want to preserve the commit structure, you must change" +
                      " the title of this pull request to `Merge <project>:<branch>` where `<project>` is the name of another project in the" +
                      " [OpenJDK organization](https://github.com/openjdk) (for example `Merge jdk:" + defaultBranch + "`).\n" +
                      MERGE_COMMIT_WARNING_MARKER;
        log.info("Adding merge commit warning comment");
        pr.addComment(message);
    }

    private void addDiffTooLargeWarning() {
        var existing = findComment(DIFF_TOO_LARGE_WARNING_MARKER);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var message = "⚠️  @" + pr.author().username() +
                " This backport pull request is too large to be automatically evaluated as clean. " +
                DIFF_TOO_LARGE_WARNING_MARKER;
        log.info("Adding diff too large warning comment");
        pr.addComment(message);
    }

    static String getJcheckName(PullRequest pr) {
        return pr.repository().forge().name().equals("GitHub") ? "jcheck-" + pr.repository().name() + "-" + pr.id() : "jcheck";
    }

    private void checkStatus() {
        var checkBuilder = CheckBuilder.create(getJcheckName(pr), pr.headHash());
        var censusDomain = censusInstance.configuration().census().domain();
        var jcheckType = "jcheck";
        Exception checkException = null;

        try {
            // Post check in-progress
            log.info("Starting to run jcheck on PR head");
            pr.createCheck(checkBuilder.build());

            var ignored = new PrintWriter(new StringWriter());
            var rebasePossible = true;
            var commitHash = pr.headHash();
            var mergedHash = checkablePullRequest.mergeTarget(ignored);
            if (mergedHash.isPresent()) {
                commitHash = mergedHash.get();
            } else {
                rebasePossible = false;
            }

            var warnings = new ArrayList<String>();
            var mergeJCheckMessageWithTargetConf = new ArrayList<String>();
            var mergeJCheckMessageWithCommitConf = new ArrayList<String>();
            var targetHash = checkablePullRequest.targetHash();
            var targetJCheckConf = checkablePullRequest.parseJCheckConfiguration(targetHash);
            var isJCheckConfUpdatedInMergePR = false;
            var hasOverridingJCheckConf = workItem.bot.confOverrideRepository().isPresent();
            if (PullRequestUtils.isMerge(pr)) {
                if (rebasePossible) {
                    localRepo.lookup(pr.headHash()).ifPresent(this::updateMergeClean);
                }

                var mergeBaseHash = localRepo.mergeBase(targetHash, pr.headHash());
                var commits = localRepo.commitMetadata(mergeBaseHash, pr.headHash(), true);
                isJCheckConfUpdatedInMergePR = isFileUpdated(JCHECK_CONF_PATH, mergeBaseHash, pr.headHash());

                // JCheck all commits in "Merge PR"
                if (workItem.bot.jcheckMerge()) {
                    for (var commit : commits) {
                        var hash = commit.hash();
                        jcheckType = "merge jcheck with target conf in commit " + hash.hex();
                        var targetVisitor = checkablePullRequest.createVisitor(targetJCheckConf);
                        checkablePullRequest.executeChecks(hash, censusInstance, targetVisitor, targetJCheckConf);
                        mergeJCheckMessageWithTargetConf.addAll(targetVisitor.errorFailedChecksMessages().stream()
                                .map(StringBuilder::new)
                                .map(e -> e.append(" (in commit `").append(hash.hex()).append("` with target configuration)"))
                                .map(StringBuilder::toString)
                                .toList());
                        warnings.addAll(targetVisitor.warningFailedChecksMessages().stream()
                                .map(StringBuilder::new)
                                .map(e -> e.append(" (in commit `").append(hash.hex()).append("` with target configuration)"))
                                .map(StringBuilder::toString)
                                .toList());

                        if (!hasOverridingJCheckConf && isJCheckConfUpdatedInMergePR) {
                            var commitJCheckConf = checkablePullRequest.parseJCheckConfiguration(hash);
                            var commitVisitor = checkablePullRequest.createVisitor(commitJCheckConf);
                            jcheckType = "merge jcheck with commit conf in commit " + hash.hex();
                            checkablePullRequest.executeChecks(hash, censusInstance, commitVisitor, commitJCheckConf);
                            mergeJCheckMessageWithCommitConf.addAll(commitVisitor.errorFailedChecksMessages().stream()
                                    .map(StringBuilder::new)
                                    .map(e -> e.append(" (in commit `").append(hash.hex()).append("` with commit configuration)"))
                                    .map(StringBuilder::toString)
                                    .toList());
                            warnings.addAll(commitVisitor.warningFailedChecksMessages().stream()
                                    .map(StringBuilder::new)
                                    .map(e -> e.append(" (in commit `").append(hash.hex()).append("` with commit configuration)"))
                                    .map(StringBuilder::toString)
                                    .toList());
                        }

                    }
                }
            }

            var original = backportedFrom();
            var isCleanBackport = false;
            if (original.isPresent()) {
                isCleanBackport = updateClean(original.get());
            }

            List<String> additionalErrors = List.of();
            Map<String, Boolean> additionalProgresses = Map.of();
            List<String> secondJCheckMessage = new ArrayList<>();
            Hash localHash;
            try {
                // Do not pass eventual original commit even for backports since it will cause
                // the reviewer check to be ignored.
                localHash = checkablePullRequest.commit(commitHash, censusInstance.namespace(), censusDomain, null, null);
            } catch (CommitFailure e) {
                additionalErrors = List.of(e.getMessage());
                localHash = baseHash;
            }

            var visitor = checkablePullRequest.createVisitor(targetJCheckConf);
            boolean tooFewReviewers = false;
            var needUpdateAdditionalProgresses = false;
            if (localHash.equals(baseHash)) {
                if (additionalErrors.isEmpty()) {
                    additionalErrors = List.of("This PR contains no changes");
                }
            } else if (localHash.equals(checkablePullRequest.targetHash())) {
                additionalErrors = List.of("This PR only contains changes already present in the target");
            } else {
                // Determine current status
                jcheckType = "target jcheck";
                var jcheckIssues = checkablePullRequest.executeChecks(localHash, censusInstance, visitor, targetJCheckConf);
                tooFewReviewers = jcheckIssues.stream().anyMatch(TooFewReviewersIssue.class::isInstance);

                // If the PR updates .jcheck/conf then Need to run JCheck again using the configuration
                // from the resulting commit. Not needed if we are overriding the JCheck configuration since
                // then we won't use the one in the repo anyway.
                if (!hasOverridingJCheckConf &&
                        (isFileUpdated(JCHECK_CONF_PATH, localRepo.mergeBase(pr.headHash(), targetHash), pr.headHash()) || isJCheckConfUpdatedInMergePR)) {
                    jcheckType = "source jcheck";
                    var localJCheckConf = checkablePullRequest.parseJCheckConfiguration(localHash);
                    var localVisitor = checkablePullRequest.createVisitor(localJCheckConf);
                    log.info("Run JCheck against localHash with configuration from localHash");
                    checkablePullRequest.executeChecks(localHash, censusInstance, localVisitor, localJCheckConf);
                    secondJCheckMessage.addAll(localVisitor.errorFailedChecksMessages().stream()
                            .map(StringBuilder::new)
                            .map(e -> e.append(" (failed with updated jcheck configuration in pull request)"))
                            .map(StringBuilder::toString)
                            .toList());
                    warnings.addAll(localVisitor.warningFailedChecksMessages().stream()
                            .map(StringBuilder::new)
                            .map(e -> e.append(" (failed with updated jcheck configuration in pull request)"))
                            .map(StringBuilder::toString)
                            .toList());
                }
                additionalErrors = botSpecificChecks(isCleanBackport);
                needUpdateAdditionalProgresses = true;
            }

            var confFile = localRepo.lines(JCHECK_CONF_PATH, localHash);
            JdkVersion version = null;
            if (confFile.isPresent()) {
                var configuration = JCheckConfiguration.parse(confFile.get());
                var versionString = configuration.general().version().orElse(null);

                if (versionString != null && !"".equals(versionString)) {
                    version = JdkVersion.parse(versionString).orElse(null);
                }
            }
            // issues without CSR issues and JEP issues
            var regularIssuesMap = regularIssuesMap();
            var jepIssue = jepIssue().orElse(null);
            var issueToAllCsrsMap = issueToAllCsrsMap(regularIssuesMap);
            var issueToCsrMap = issueToCsrMap(issueToAllCsrsMap, version);
            var csrIssues = issueToCsrMap.values().stream().toList();

            // Check the status of csr issues and determine whether to add or remove csr label here
            updateCSRLabel(version, issueToCsrMap);

            // In a backport PR, Check if one of associated issues has a resolved CSR for a different fixVersion
            updateBackportCSRLabel(issueToAllCsrsMap, issueToCsrMap);

            if (needUpdateAdditionalProgresses) {
                additionalProgresses = botSpecificProgresses(regularIssuesMap, csrIssues, jepIssue, version);
            }

            updateCheckBuilder(checkBuilder, visitor, additionalErrors);


            if (!workItem.bot.labelConfiguration().allowed().isEmpty() && workItem.bot.isAutoLabelled(pr)) {
                var labelComment = findComment(LabelerWorkItem.INITIAL_LABEL_MESSAGE);
                if (labelComment.isPresent()) {
                    var line = labelComment.get().body().lines()
                            .map(LABEL_COMMIT_PATTERN::matcher)
                            .filter(Matcher::find)
                            .findFirst();
                    if (line.isPresent()) {
                        var evaluatedCommitHash = line.get().group(1);
                        var changedFiles = PullRequestUtils.changedFiles(pr, localRepo, new Hash(evaluatedCommitHash));
                        var newLabelsNeedToBeAdded = workItem.bot.labelConfiguration().label(changedFiles);
                        newLabels.addAll(newLabelsNeedToBeAdded);
                        var upgradedLabels = workItem.bot.labelConfiguration().upgradeLabelsToGroups(newLabels);
                        newLabels.addAll(upgradedLabels);
                        newLabels.removeIf(label -> !upgradedLabels.contains(label));
                    }
                    pr.updateComment(labelComment.get().id(), labelComment.get().body().replaceAll(
                            "(<!-- PullRequest Bot label commit ')[^']*(' -->)",
                            "$1" + pr.headHash().toString() + "$2"
                    ));
                }
            }

            var readyForReview = updateReadyForReview(visitor, additionalErrors, regularIssuesMap);

            var integrationBlockers = botSpecificIntegrationBlockers(regularIssuesMap);
            integrationBlockers.addAll(secondJCheckMessage);
            integrationBlockers.addAll(mergeJCheckMessageWithTargetConf);
            integrationBlockers.addAll(mergeJCheckMessageWithCommitConf);

            var reviewNeeded = !isCleanBackport || reviewCleanBackport || reviewersCommandIssued;

            // Calculate and update the status message if needed
            var statusMessage = getStatusMessage(visitor, additionalErrors, additionalProgresses, integrationBlockers, warnings,
                    reviewNeeded, regularIssuesMap, jepIssue, issueToCsrMap.values(), version, tooFewReviewers);
            var updatedBody = updateStatusMessage(statusMessage);
            var title = pr.title();

            var amendedHash = checkablePullRequest.amendManualReviewersAndStaleReviewers(localHash, censusInstance.namespace(), original.map(Commit::hash).orElse(null));
            var commit = localRepo.lookup(amendedHash).orElseThrow();
            var commitMessage = String.join("\n", commit.message());

            var readyToPostApprovalNeededComment = readyForReview &&
                    !visitor.hasErrors(reviewNeeded) &&
                    integrationBlockers.isEmpty() &&
                    !statusMessage.contains(TEMPORARY_ISSUE_FAILURE_MARKER);

            var readyForIntegration = readyToPostApprovalNeededComment &&
                    !additionalProgresses.containsValue(false);

            updateMergeReadyComment(readyForIntegration, commitMessage, rebasePossible);
            if (readyForIntegration && rebasePossible) {
                newLabels.add("ready");
            } else {
                newLabels.remove("ready");
            }
            if (!rebasePossible) {
                if (!labels.contains("failed-auto-merge")) {
                    addOutdatedComment();
                }
                newLabels.add("merge-conflict");
            } else {
                newLabels.remove("merge-conflict");
            }

            if (!PullRequestUtils.isMerge(pr) && !newLabels.contains("ready") && missingApprovalRequest
                    && approvalNeeded() && approval.approvalComment() && readyToPostApprovalNeededComment) {
                for (var entry : additionalProgresses.entrySet()) {
                    if (!entry.getKey().endsWith("needs " + approval.approvalTerm()) && !entry.getValue()) {
                        readyToPostApprovalNeededComment = false;
                        break;
                    }
                }
                if (readyToPostApprovalNeededComment) {
                    postApprovalNeededComment();
                }
            }

            if (pr.sourceRepository().isPresent()) {
                var branchNames = pr.repository().branches().stream().map(HostedBranch::name).collect(Collectors.toSet());
                if (!pr.repository().url().equals(pr.sourceRepository().get().url()) && branchNames.contains(pr.sourceRef())) {
                    addSourceBranchWarningComment();
                }
            }

            if (!PullRequestUtils.isMerge(pr) && PullRequestUtils.containsForeignMerge(pr, localRepo)) {
                addMergeCommitWarningComment();
            }

            // Ensure that the ready for sponsor label is up to date
            newLabels.remove("sponsor");
            var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().forge().currentUser(), comments);
            if (readyHash.isPresent() && readyForIntegration) {
                var acceptedHash = readyHash.get();
                if (pr.headHash().equals(acceptedHash)) {
                    newLabels.add("sponsor");
                }
            }

            // Calculate current metadata to avoid unnecessary future checks
            var metadata = workItem.getMetadata(workItem.getPRMetadata(censusInstance, title, updatedBody, comments, activeReviews,
                    newLabels, pr.targetRef(), pr.isDraft()), workItem.getIssueMetadata(updatedBody), expiresIn);
            checkBuilder.metadata(metadata);
        } catch (Exception e) {
            log.throwing("CommitChecker", "checkStatus", e);
            newLabels.remove("ready");
            checkBuilder.metadata("invalid");
            checkBuilder.title("Exception occurred during " + jcheckType + " - the operation will be retried");
            checkBuilder.summary(e.getMessage());
            checkBuilder.complete(false);
            checkException = e;
        }
        var check = checkBuilder.build();
        pr.updateCheck(check);

        // Synchronize the wanted set of labels
        for (var newLabel : newLabels) {
            if (!labels.contains(newLabel)) {
                log.info("Adding label " + newLabel);
                pr.addLabel(newLabel);
            }
        }
        for (var oldLabel : labels) {
            if (!newLabels.contains(oldLabel)) {
                log.info("Removing label " + oldLabel);
                pr.removeLabel(oldLabel);
            }
        }

        // After updating the PR, rethrow any exception to automatically retry on transient errors
        if (checkException != null) {
            throw new RuntimeException("Exception during jcheck", checkException);
        }
    }

    private boolean isFileUpdated(Path filename, Hash from, Hash to) throws IOException {
        return !localRepo.diff(from, to, List.of(filename)).patches().isEmpty();
    }

    private void updateCSRLabel(JdkVersion version, Map<String, IssueTrackerIssue> csrIssueTrackerIssueMap) {
        if (csrIssueTrackerIssueMap.isEmpty()) {
            return;
        }

        if (version == null) {
            log.info("No fix version found in `.jcheck/conf` for " + describe(pr));
            return;
        }
        boolean notExistingUnresolvedCSR = true;
        boolean existingApprovedCSR = false;

        if (issueProject() == null) {
            log.info("No issue project found for " + describe(pr));
            return;
        }

        for (var csrEntry : csrIssueTrackerIssueMap.entrySet()) {
            var mainIssueId = csrEntry.getKey();
            var csr = csrEntry.getValue();

            log.info("Found CSR " + csr.id() + " for issue " + mainIssueId + " for " + describe(pr));

            var resolutionOpt = csr.resolution();
            if (resolutionOpt.isEmpty()) {
                notExistingUnresolvedCSR = false;
                if (!newLabels.contains(CSR_LABEL)) {
                    log.info("CSR issue resolution is null for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    newLabels.add(CSR_LABEL);
                } else {
                    log.info("CSR issue resolution is null for csr issue " + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            var resolution = resolutionOpt.get();

            if (csr.state() != org.openjdk.skara.issuetracker.Issue.State.CLOSED) {
                notExistingUnresolvedCSR = false;
                if (!newLabels.contains(CSR_LABEL)) {
                    log.info("CSR issue state is not closed for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    newLabels.add(CSR_LABEL);
                } else {
                    log.info("CSR issue state is not closed for csr issue" + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
                continue;
            }

            if (!resolution.equals("Approved")) {
                if (resolution.equals("Withdrawn")) {
                    // This condition is necessary to prevent the bot from adding the CSR label again.
                    // And the bot can't remove the CSR label automatically here.
                    // Because the PR author with the role of Committer may withdraw a CSR that
                    // a Reviewer had requested and integrate it without satisfying that requirement.
                    log.info("CSR closed and withdrawn for csr issue " + csr.id() + " for " + describe(pr));
                } else if (!newLabels.contains(CSR_LABEL)) {
                    notExistingUnresolvedCSR = false;
                    log.info("CSR issue resolution is not 'Approved' for csr issue " + csr.id() + " for " + describe(pr) + ", adding the CSR label");
                    newLabels.add(CSR_LABEL);
                } else {
                    notExistingUnresolvedCSR = false;
                    log.info("CSR issue resolution is not 'Approved' for csr issue " + csr.id() + " for " + describe(pr) + ", not removing the CSR label");
                }
            } else {
                existingApprovedCSR = true;
            }
        }
        if (notExistingUnresolvedCSR && (!isCSRNeeded(comments) || existingApprovedCSR) && newLabels.contains(CSR_LABEL)) {
            log.info("All CSR issues closed and approved for " + describe(pr) + ", removing CSR label");
            newLabels.remove(CSR_LABEL);
        }
    }

    private void updateBackportCSRLabel(Map<String, List<IssueTrackerIssue>> issueToAllCsrsMap, Map<String, IssueTrackerIssue> issueToCsrMap) {
        // Ignore withdrawn CSRs
        boolean associatedWithValidCSR = issueToCsrMap.values().stream()
                .anyMatch(value -> !isWithdrawnCSR(value));

        if (newLabels.contains("backport") && !newLabels.contains("csr") && !associatedWithValidCSR && !isCSRManuallyUnneeded(comments)) {
            boolean hasResolvedCSR = issueToAllCsrsMap.values().stream()
                    .flatMap(List::stream)
                    .anyMatch(csrIssue -> csrIssue.state() == org.openjdk.skara.issuetracker.Issue.State.CLOSED &&
                            csrIssue.resolution().map(res -> res.equals("Approved")).orElse(false));

            if (hasResolvedCSR) {
                newLabels.add("csr");
                var existing = findComment(BACKPORT_CSR_MARKER);
                if (existing.isPresent()) {
                    return;
                }
                pr.addComment("At least one of the issues associated with this backport has a resolved " +
                        "[CSR](" + CSR_PROCESS_LINK + ") for a different version. As this means that this " +
                        "backport may also need a CSR, the `csr` label is being added to this pull request " +
                        "to signal this potential requirement. The command `/csr unneeded` can be used to " +
                        "remove the label in case a CSR is not needed." +
                        BACKPORT_CSR_MARKER);
            }
        }
    }

    private boolean isCSRNeeded(List<Comment> comments) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            var comment = comments.get(i);
            if (comment.body().contains(CSR_NEEDED_MARKER)) {
                return true;
            }
            if (comment.body().contains(CSR_UNNEEDED_MARKER)) {
                return false;
            }
        }
        return false;
    }

    private boolean isCSRManuallyUnneeded(List<Comment> comments) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            var comment = comments.get(i);
            if (comment.body().contains(CSR_NEEDED_MARKER)) {
                return false;
            }
            if (comment.body().contains(CSR_UNNEEDED_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private String describe(PullRequest pr) {
        return pr.repository().name() + "#" + pr.id();
    }

    private boolean approvalNeeded() {
        if (approval != null) {
            if (realTargetRef == null) {
                realTargetRef = PreIntegrations.realTargetRef(pr);
            }
            return approval.needsApproval(realTargetRef);
        }
        return false;
    }

    private void postApprovalNeededComment() {
        var existing = findComment(APPROVAL_NEEDED_MARKER);
        if (existing.isPresent()) {
            return;
        }
        String message = "⚠️  @" + pr.author().username() +
                " This change is now ready for you to apply for [" + approval.approvalTerm() + "](" + approval.documentLink() + "). " +
                "This can be done directly in each associated issue or by using the " +
                "[/approval](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/approval) " +
                "command." +
                APPROVAL_NEEDED_MARKER;
        pr.addComment(message);
    }

    /**
     * Creates a map from issue ID to a list of all CSRs linked from the issue or any backport of the issue.
     */
    private Map<String, List<IssueTrackerIssue>> issueToAllCsrsMap(Map<Issue, Optional<IssueTrackerIssue>> regularIssuesMap) {
        Map<String, List<IssueTrackerIssue>> issueToAllCsrsMap = new HashMap<>();
        regularIssuesMap.values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(issue -> {
                    Backports.csrLink(issue)
                            .flatMap(Link::issue)
                            .ifPresent(csr -> issueToAllCsrsMap.computeIfAbsent(issue.id(), k -> new ArrayList<>()).add(csr));

                    Backports.findBackports(issue, false).stream()
                            .map(Backports::csrLink)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(Link::issue)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .forEach(backportCsr -> issueToAllCsrsMap.computeIfAbsent(issue.id(), k -> new ArrayList<>()).add(backportCsr));

                });
        return issueToAllCsrsMap;
    }
}
