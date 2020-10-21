/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;

class CheckRun {
    private final CheckWorkItem workItem;
    private final PullRequest pr;
    private final Repository localRepo;
    private final List<Comment> comments;
    private final List<Review> allReviews;
    private final List<Review> activeReviews;
    private final Set<String> labels;
    private final CensusInstance censusInstance;
    private final boolean ignoreStaleReviews;

    private final Hash baseHash;
    private final CheckablePullRequest checkablePullRequest;

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private static final String progressMarker = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";
    private static final String mergeReadyMarker = "<!-- PullRequestBot merge is ready comment -->";
    private static final String outdatedHelpMarker = "<!-- PullRequestBot outdated help comment -->";
    private static final String sourceBranchWarningMarker = "<!-- PullRequestBot source branch warning comment -->";
    private static final String mergeCommitWarningMarker = "<!-- PullRequestBot merge commit warning comment -->";
    private static final String emptyPrBodyMarker = "<!--\nReplace this text with a description of your pull request (also remove the surrounding HTML comment markers).\n" +
            "If in doubt, feel free to delete everything in this edit box first, the bot will restore the progress section as needed.\n-->";
    private static final String fullNameWarningMarker = "<!-- PullRequestBot full name warning comment -->";
    private static final Pattern BACKPORT_PATTERN = Pattern.compile("<!-- backport ([0-9a-z]{40}) -->");
    private final Set<String> newLabels;

    private Duration expiresIn;

    private CheckRun(CheckWorkItem workItem, PullRequest pr, Repository localRepo, List<Comment> comments,
                     List<Review> allReviews, List<Review> activeReviews, Set<String> labels,
                     CensusInstance censusInstance, boolean ignoreStaleReviews) throws IOException {
        this.workItem = workItem;
        this.pr = pr;
        this.localRepo = localRepo;
        this.comments = comments;
        this.allReviews = allReviews;
        this.activeReviews = activeReviews;
        this.labels = new HashSet<>(labels);
        this.newLabels = new HashSet<>(labels);
        this.censusInstance = censusInstance;
        this.ignoreStaleReviews = ignoreStaleReviews;

        baseHash = PullRequestUtils.baseHash(pr, localRepo);
        checkablePullRequest = new CheckablePullRequest(pr, localRepo, ignoreStaleReviews,
                                                        workItem.bot.confOverrideRepository().orElse(null),
                                                        workItem.bot.confOverrideName(),
                                                        workItem.bot.confOverrideRef());
    }

    static Optional<Instant> execute(CheckWorkItem workItem, PullRequest pr, Repository localRepo, List<Comment> comments,
                        List<Review> allReviews, List<Review> activeReviews, Set<String> labels, CensusInstance censusInstance,
                        boolean ignoreStaleReviews) throws IOException {
        var run = new CheckRun(workItem, pr, localRepo, comments, allReviews, activeReviews, labels, censusInstance, ignoreStaleReviews);
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

    private Set<String> allowedIssueTypes() {
        return workItem.bot.allowedIssueTypes();
    }

    private List<Issue> issues() {
        var issue = Issue.fromStringRelaxed(pr.title());
        if (issue.isPresent()) {
            var issues = new ArrayList<Issue>();
            issues.add(issue.get());
            issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), comments));
            return issues;
        }
        return List.of();
    }

    private IssueProject issueProject() {
        return workItem.bot.issueProject();
    }

    private List<org.openjdk.skara.issuetracker.Issue> issuesOfDisallowedType() {
        var issueProject = issueProject();
        var allowed = allowedIssueTypes();
        if (issueProject != null && allowed != null) {
            return issues().stream()
                           .filter(i -> i.project().equals(Optional.of(issueProject.name())))
                           .map(i -> issueProject.issue(i.shortId()))
                           .filter(Optional::isPresent)
                           .map(Optional::get)
                           .filter(i -> i.properties().containsKey("issuetype"))
                           .filter(i -> !allowed.contains(i.properties().get("issuetype").asString()))
                           .collect(Collectors.toList());
        }
        return List.of();
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
    private List<String> botSpecificChecks(Hash finalHash) throws IOException {
        var ret = new ArrayList<String>();

        if (bodyWithoutStatus().isBlank()) {
            var error = "The pull request body must not be empty.";
            ret.add(error);
        }

        if (!isTargetBranchAllowed()) {
            var error = "The branch `" + pr.targetRef() + "` is not allowed as target branch. The allowed target branches are:\n" +
                    allowedTargetBranches().stream()
                    .map(name -> "   - " + name)
                    .collect(Collectors.joining("\n"));
            ret.add(error);
        }

        var disallowedIssues = issuesOfDisallowedType();
        if (!disallowedIssues.isEmpty()) {
            var s = disallowedIssues.size() > 1 ? "s " : " ";
            var are = disallowedIssues.size() > 1 ? "are" : "is";
            var links = disallowedIssues.stream()
                                        .map(i -> "[" + i.id() + "](" + i.webUrl() + ")")
                                        .collect(Collectors.toList());
            var error = "The issue" + s + String.join(",", links) + " " + are + " not of the expected type. The allowed issue types are:\n" +
                allowedIssueTypes().stream()
                .map(name -> "   - " + name)
                .collect(Collectors.joining("\n"));
            ret.add(error);
        }

        for (var blocker : workItem.bot.blockingCheckLabels().entrySet()) {
            if (labels.contains(blocker.getKey())) {
                ret.add(blocker.getValue());
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
        return Map.of("rejected", "The change is currently blocked from integration by a rejection.",
                      "csr", "The change requires a CSR request to be approved.");
    }

    private List<String> botSpecificIntegrationBlockers() {
        var ret = new ArrayList<String>();

        var issues = issues();
        var issueProject = issueProject();
        if (issueProject != null) {
            for (var currentIssue : issues) {
                try {
                    var iss = issueProject.issue(currentIssue.shortId());
                    if (iss.isPresent()) {
                        if (!relaxedEquals(iss.get().title(), currentIssue.description())) {
                            var issueString = "[" + iss.get().id() + "](" + iss.get().webUrl() + ")";
                            ret.add("Title mismatch between PR and JBS for issue " + issueString);
                            setExpiration(Duration.ofMinutes(10));
                        }
                    } else {
                        log.warning("Failed to retrieve information on issue " + currentIssue.id());
                        setExpiration(Duration.ofMinutes(10));
                    }
                } catch (RuntimeException e) {
                    log.warning("Temporary failure when trying to retrieve information on issue " + currentIssue.id());
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
        } else {
            checkBuilder.title("Required");
            var summary = Stream.concat(visitor.messages().stream(), additionalErrors.stream())
                                .sorted()
                                .map(m -> "- " + m)
                                .collect(Collectors.joining("\n"));
            checkBuilder.summary(summary);
            for (var annotation : visitor.getAnnotations()) {
                checkBuilder.annotation(annotation);
            }
            checkBuilder.complete(false);
        }
    }

    private void updateReadyForReview(PullRequestCheckIssueVisitor visitor, List<String> additionalErrors) {
        // Additional errors are not allowed
        if (!additionalErrors.isEmpty()) {
            newLabels.remove("rfr");
            return;
        }

        // Draft requests are not for review
        if (pr.isDraft()) {
            newLabels.remove("rfr");
            return;
        }

        // Check if the visitor found any issues that should be resolved before reviewing
        if (visitor.isReadyForReview()) {
            newLabels.add("rfr");
        } else {
            newLabels.remove("rfr");
        }
    }

    private boolean updateClean(Hash hash) {
        var result = pr.repository().forge().search(hash);
        if (result.isEmpty()) {
            throw new IllegalStateException("Backport comment for PR " + pr.id() + " contains bad hash: " + hash.hex());
        }

        var hasCleanLabel = labels.contains("clean");

        var commit = result.get();
        var originalPatches = new HashMap<String, Patch>();
        for (var patch : commit.parentDiffs().get(0).patches()) {
            originalPatches.put(patch.toString(), patch);
        }
        var prPatches = new HashMap<String, Patch>();
        for (var patch : pr.diff().patches()) {
            prPatches.put(patch.toString(), patch);
        }

        if (originalPatches.size() != prPatches.size()) {
            if (hasCleanLabel) {
                pr.removeLabel("clean");
            }
            return false;
        }

        var descriptions = new HashSet<>(originalPatches.keySet());
        descriptions.removeAll(prPatches.keySet());
        if (!descriptions.isEmpty()) {
            if (hasCleanLabel) {
                pr.removeLabel("clean");
            }
            return false;
        }

        for (var desc : originalPatches.keySet()) {
            var original = originalPatches.get(desc).asTextualPatch();
            var backport = prPatches.get(desc).asTextualPatch();
            if (original.hunks().size() != backport.hunks().size()) {
                if (hasCleanLabel) {
                    pr.removeLabel("clean");
                }
                return false;
            }
            if (original.additions() != backport.additions()) {
                if (hasCleanLabel) {
                    pr.removeLabel("clean");
                }
                return false;
            }
            if (original.deletions() != backport.deletions()) {
                if (hasCleanLabel) {
                    pr.removeLabel("clean");
                }
                return false;
            }
            for (var i = 0; i < original.hunks().size(); i++) {
                var originalHunk = original.hunks().get(i);
                var backportHunk = backport.hunks().get(i);

                if (originalHunk.source().lines().size() != backportHunk.source().lines().size()) {
                    if (hasCleanLabel) {
                        pr.removeLabel("clean");
                    }
                    return false;
                }
                var sourceLines = new HashSet<>(originalHunk.source().lines());
                sourceLines.removeAll(backportHunk.source().lines());
                if (!sourceLines.isEmpty()) {
                    if (hasCleanLabel) {
                        pr.removeLabel("clean");
                    }
                    return false;
                }

                if (originalHunk.target().lines().size() != backportHunk.target().lines().size()) {
                    if (hasCleanLabel) {
                        pr.removeLabel("clean");
                    }
                    return false;
                }
                var targetLines = new HashSet<>(originalHunk.target().lines());
                targetLines.removeAll(backportHunk.target().lines());
                if (!targetLines.isEmpty()) {
                    if (hasCleanLabel) {
                        pr.removeLabel("clean");
                    }
                    return false;
                }
            }
        }

        if (!hasCleanLabel) {
            pr.addLabel("clean");
        }
        return true;
    }

    private Optional<Hash> backportedFrom() {
        var botUser = pr.repository().forge().currentUser();
        var backportLines = pr.comments()
                              .stream()
                              .filter(c -> c.author().equals(botUser))
                              .flatMap(c -> Stream.of(c.body().split("\n")))
                              .map(l -> BACKPORT_PATTERN.matcher(l))
                              .filter(Matcher::find)
                              .collect(Collectors.toList());
        return backportLines.isEmpty()?
            Optional.empty() : Optional.of(new Hash(backportLines.get(0).group(1)));
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
        var namespace = censusInstance.namespace();
        var contributor = namespace.get(reviewer.id());
        if (contributor == null) {
            return "@" + reviewer.username() + " (no known " + namespace.name() + " user name / role)";
        } else {
            var ret = new StringBuilder();
            var censusLink = workItem.bot.censusLink(contributor);
            if (censusLink.isPresent()) {
                ret.append("[");
            }
            ret.append(contributor.fullName().orElse(contributor.username()));
            if (censusLink.isPresent()) {
                ret.append("](");
                ret.append(censusLink.get().toString());
                ret.append(")");
            }
            ret.append(" (@");
            ret.append(reviewer.username());
            ret.append(" - ");
            ret.append(getRole(contributor.username()));
            ret.append(")");
            return ret.toString();
        }
    }

    private String getChecksList(PullRequestCheckIssueVisitor visitor) {
        return visitor.getChecks().entrySet().stream()
                      .map(entry -> "- [" + (entry.getValue() ? "x" : " ") + "] " + entry.getKey())
                      .collect(Collectors.joining("\n"));
    }

    private String warningListToText(List<String> additionalErrors) {
        return additionalErrors.stream()
                               .sorted()
                               .map(err -> "&nbsp;⚠️ " + err)
                               .collect(Collectors.joining("\n"));
    }

    private Optional<String> getReviewersList(List<Review> reviews) {
        var reviewers = reviews.stream()
                               .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                               .map(review -> {
                                   var entry = " * " + formatReviewer(review.reviewer());
                                   if (!review.hash().equals(pr.headHash())) {
                                       if (ignoreStaleReviews) {
                                           entry += " 🔄 Re-review required (review applies to " + review.hash() + ")";
                                       } else {
                                           entry += " ⚠️ Review applies to " + review.hash();
                                       }
                                   }
                                   return entry;
                               })
                               .collect(Collectors.joining("\n"));

        // Check for manually added reviewers
        if (!ignoreStaleReviews) {
            var namespace = censusInstance.namespace();
            var allReviewers = PullRequestUtils.reviewerNames(reviews, namespace);
            var additionalEntries = new ArrayList<String>();
            for (var additional : Reviewers.reviewers(pr.repository().forge().currentUser(), comments)) {
                if (!allReviewers.contains(additional)) {
                    additionalEntries.add(" * " + additional + " - " + getRole(additional) + " ⚠️ Added manually");
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

    private Optional<String> getContributorsList(List<Comment> comments) {
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

    private boolean relaxedEquals(String s1, String s2) {
        return s1.trim()
                 .replaceAll("\\s+", " ")
                 .equalsIgnoreCase(s2.trim()
                                     .replaceAll("\\s+", " "));
    }

    private String getStatusMessage(List<Comment> comments, List<Review> reviews, PullRequestCheckIssueVisitor visitor,
                                    List<String> additionalErrors, List<String> integrationBlockers) {
        var progressBody = new StringBuilder();
        progressBody.append("---------\n");
        progressBody.append("### Progress\n");
        progressBody.append(getChecksList(visitor));

        var allAdditionalErrors = Stream.concat(visitor.hiddenMessages().stream(), additionalErrors.stream())
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

        if (pr.sourceRepository().isPresent()) {
            var sourceRepo = pr.sourceRepository().get();
            var checks = sourceRepo.allChecks(pr.headHash());

            var resultSummary = TestResults.summarize(checks);
            if (resultSummary.isPresent()) {
                progressBody.append(resultSummary.get());
                var expiration = TestResults.expiresIn(checks);
                expiration.ifPresent(this::setExpiration);
            } else {
                try {
                    var headCommit = localRepo.commitMetadata(pr.headHash());
                    if (headCommit.isPresent()) {
                        // If the commit is recent, perhaps test results will appear soon
                        if (headCommit.get().committed().isAfter(ZonedDateTime.now().minus(Duration.ofDays(1)))) {
                            setExpiration(Duration.ofMinutes(10));
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        if (!integrationBlockers.isEmpty()) {
            progressBody.append("\n\n### Integration blocker");
            if (integrationBlockers.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            progressBody.append(warningListToText(integrationBlockers));
        }

        var issues = issues();
        var issueProject = issueProject();
        if (issueProject != null && !issues.isEmpty()) {
            progressBody.append("\n\n### Issue");
            if (issues.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            for (var currentIssue : issues) {
                progressBody.append(" * ");
                if (currentIssue.project().isPresent() && !currentIssue.project().get().equals(issueProject.name())) {
                    progressBody.append("⚠️ Issue `");
                    progressBody.append(currentIssue.id());
                    progressBody.append("` does not belong to the `");
                    progressBody.append(issueProject.name());
                    progressBody.append("` project.");
                } else {
                    try {
                        var iss = issueProject.issue(currentIssue.shortId());
                        if (iss.isPresent()) {
                            progressBody.append("[");
                            progressBody.append(iss.get().id());
                            progressBody.append("](");
                            progressBody.append(iss.get().webUrl());
                            progressBody.append("): ");
                            progressBody.append(iss.get().title());
                            if (!relaxedEquals(iss.get().title(), currentIssue.description())) {
                                progressBody.append(" ⚠️ Title mismatch between PR and JBS.");
                                setExpiration(Duration.ofMinutes(10));
                            }
                            progressBody.append("\n");
                        } else {
                            progressBody.append("⚠️ Failed to retrieve information on issue `");
                            progressBody.append(currentIssue.id());
                            progressBody.append("`.");
                            setExpiration(Duration.ofMinutes(10));
                            progressBody.append("\n");
                        }
                    } catch (RuntimeException e) {
                        progressBody.append("⚠️ Temporary failure when trying to retrieve information on issue `");
                        progressBody.append(currentIssue.id());
                        progressBody.append("`.");
                        setExpiration(Duration.ofMinutes(30));
                        progressBody.append("\n");
                    }
                }
            }
        }

        getReviewersList(reviews).ifPresent(reviewers -> {
            progressBody.append("\n\n### Reviewers\n");
            progressBody.append(reviewers);
        });

        getContributorsList(comments).ifPresent(contributors -> {
            progressBody.append("\n\n### Contributors\n");
            progressBody.append(contributors);
        });

        progressBody.append("\n\n### Download\n");
        progressBody.append(checkoutCommands());

        return progressBody.toString();
    }

    private String checkoutCommands() {
        var repoUrl = pr.repository().webUrl();
        return
           "`$ git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id() + "`\n" +
           "`$ git checkout pull/" + pr.id() + "`\n";
    }

    private String bodyWithoutStatus() {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(progressMarker);
        return (markerIndex < 0 ?
                description :
                description.substring(0, markerIndex)).trim();
    }

    private String updateStatusMessage(String message) {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(progressMarker);

        if (markerIndex >= 0 && description.substring(markerIndex).equals(message)) {
            log.info("Progress already up to date");
            return description;
        }
        var originalBody = bodyWithoutStatus();
        if (originalBody.isBlank()) {
            originalBody = emptyPrBodyMarker;
        }
        var newBody = originalBody + "\n\n" + progressMarker + "\n" + message;

        // TODO? Retrieve the body again here to lower the chance of concurrent updates
        pr.setBody(newBody);
        return newBody;
    }

    private String verdictToString(Review.Verdict verdict) {
        switch (verdict) {
            case APPROVED:
                return "changes are approved";
            case DISAPPROVED:
                return "more changes needed";
            case NONE:
                return "comment added";
            default:
                throw new RuntimeException("Unknown verdict: " + verdict);
        }
    }

    private void updateReviewedMessages(List<Comment> comments, List<Review> reviews) {
        var reviewTracker = new ReviewTracker(comments, reviews);

        for (var added : reviewTracker.newReviews().entrySet()) {
            var body = added.getValue() + "\n" +
                    "This PR has been reviewed by " +
                    formatReviewer(added.getKey().reviewer()) + " - " +
                    verdictToString(added.getKey().verdict()) + ".";
            pr.addComment(body);
        }
    }

    private Optional<Comment> findComment(List<Comment> comments, String marker) {
        var self = pr.repository().forge().currentUser();
        return comments.stream()
                       .filter(comment -> comment.author().equals(self))
                       .filter(comment -> comment.body().contains(marker))
                       .findAny();
    }

    private String getMergeReadyComment(String commitMessage, List<Review> reviews) {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" This change now passes all *automated* pre-integration checks.");

        try {
            var hasContributingFile =
                !localRepo.files(pr.targetHash(), Path.of("CONTRIBUTING.md")).isEmpty();
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

        message.append("You can use [pull request commands](https://wiki.openjdk.java.net/display/SKARA/Pull+Request+Commands) ");
        message.append("such as [/summary](https://wiki.openjdk.java.net/display/SKARA/Pull+Request+Commands#PullRequestCommands-/summary), ");
        message.append("[/contributor](https://wiki.openjdk.java.net/display/SKARA/Pull+Request+Commands#PullRequestCommands-/contributor) and ");
        message.append("[/issue](https://wiki.openjdk.java.net/display/SKARA/Pull+Request+Commands#PullRequestCommands-/issue) to adjust it as needed.");
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
                            .limit(10)
                            .forEach(c -> message.append(" * ").append(c.hash().hex()).append(": ").append(c.message().get(0)).append("\n"));
            if (divergingCommits.size() > 10) {
                message.append(" * ... and ").append(divergingCommits.size() - 10).append(" more: ")
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
        message.append("[/integrate](https://wiki.openjdk.java.net/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) ");
        message.append("command for further details.\n");

        if (!censusInstance.isCommitter(pr.author())) {
            message.append("\n");
            var contributor = censusInstance.namespace().get(pr.author().id());
            message.append("As you do not have [Committer](https://openjdk.java.net/bylaws#committer) status in ");
            message.append("[this project](https://openjdk.java.net/census#");
            message.append(censusInstance.project().name());
            message.append(") an existing Committer must agree to ");
            message.append("[sponsor](https://openjdk.java.net/sponsor/) your change. ");
            var candidates = reviews.stream()
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
            message.append("[/integrate](https://wiki.openjdk.java.net/display/SKARA/Pull+Request+Commands#PullRequestCommands-/integrate) ");
            message.append("in a new comment.\n");
        }
        message.append(mergeReadyMarker);
        return message.toString();
    }

    private String getMergeNoLongerReadyComment() {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" This change is no longer ready for integration - check the PR body for details.\n");
        message.append(mergeReadyMarker);
        return message.toString();
    }

    private void addFullNameWarningComment() {
        var existing = findComment(comments, fullNameWarningMarker);
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
        if (!pr.author().fullName().equals(pr.author().username()) &&
            !pr.author().fullName().equals(head.author().name())) {
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
                          "$ git commit -c user.name='Preferred Full Name' --allow-empty -m 'Update full name'\n" +
                          "$ git push\n" +
                          "```\n";
            pr.addComment(fullNameWarningMarker + "\n" + message);
        }
    }

    private void updateMergeReadyComment(boolean isReady, String commitMessage, List<Comment> comments, List<Review> reviews, boolean rebasePossible) {
        var existing = findComment(comments, mergeReadyMarker);
        if (isReady && rebasePossible) {
            addFullNameWarningComment();
            var message = getMergeReadyComment(commitMessage, reviews);
            if (existing.isEmpty()) {
                pr.addComment(message);
            } else {
                pr.updateComment(existing.get().id(), message);
            }
        } else {
            existing.ifPresent(comment -> pr.updateComment(comment.id(), getMergeNoLongerReadyComment()));
        }
    }

    private void addSourceBranchWarningComment(List<Comment> comments) throws IOException {
        var existing = findComment(comments, sourceBranchWarningMarker);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var branch = pr.sourceRef();
        var message = ":warning: @" + pr.author().username() + " " +
            "a branch with the same name as the source branch for this pull request (`" + branch + "`) " +
            "is present in the [target repository](" + pr.repository().nonTransformedWebUrl() + "). " +
            "If you eventually integrate this pull request then the branch `" + branch + "` " +
            "in your [personal fork](" + pr.sourceRepository().get().nonTransformedWebUrl() + ") will diverge once you sync " +
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
            sourceBranchWarningMarker;
        pr.addComment(message);
    }

    private void addOutdatedComment(List<Comment> comments) {
        var existing = findComment(comments, outdatedHelpMarker);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var message = "@" + pr.author().username() + " this pull request can not be integrated into " +
                "`" + pr.targetRef() + "` due to one or more merge conflicts. To resolve these merge conflicts " +
                "and update this pull request you can run the following commands in the local repository for your personal fork:\n" +
                "```bash\n" +
                "git checkout " + pr.sourceRef() + "\n" +
                "git fetch " + pr.repository().webUrl() + " " + pr.targetRef() + "\n" +
                "git merge FETCH_HEAD\n" +
                "# resolve conflicts and follow the instructions given by git merge\n" +
                "git commit -m \"Merge " + pr.targetRef() + "\"\n" +
                "git push\n" +
                "```\n" +
                outdatedHelpMarker;
        pr.addComment(message);
    }

    private void addMergeCommitWarningComment(List<Comment> comments) {
        var existing = findComment(comments, mergeCommitWarningMarker);
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
                      mergeCommitWarningMarker;
        pr.addComment(message);
    }

    private void checkStatus() {
        var checkBuilder = CheckBuilder.create("jcheck", pr.headHash());
        var censusDomain = censusInstance.configuration().census().domain();
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

            List<String> additionalErrors = List.of();
            Hash localHash;
            try {
                // Do not pass eventual original commit even for backports since it will cause
                // the reviewer check to be ignored.
                localHash = checkablePullRequest.commit(commitHash, censusInstance.namespace(), censusDomain, null, null);
            } catch (CommitFailure e) {
                additionalErrors = List.of(e.getMessage());
                localHash = baseHash;
            }
            PullRequestCheckIssueVisitor visitor = checkablePullRequest.createVisitor(localHash);
            if (!localHash.equals(baseHash)) {
                // Determine current status
                var additionalConfiguration = AdditionalConfiguration.get(localRepo, localHash, pr.repository().forge().currentUser(), comments);
                checkablePullRequest.executeChecks(localHash, censusInstance, visitor, additionalConfiguration);
                additionalErrors = botSpecificChecks(localHash);
            } else {
                if (additionalErrors.isEmpty()) {
                    additionalErrors = List.of("This PR contains no changes");
                }
            }
            updateCheckBuilder(checkBuilder, visitor, additionalErrors);
            updateReadyForReview(visitor, additionalErrors);
            var original = backportedFrom();
            var isCleanBackport = false;
            if (original.isPresent()) {
                isCleanBackport = updateClean(original.get());
            }

            var integrationBlockers = botSpecificIntegrationBlockers();

            // Calculate and update the status message if needed
            var statusMessage = getStatusMessage(comments, activeReviews, visitor, additionalErrors, integrationBlockers);
            var updatedBody = updateStatusMessage(statusMessage);
            var title = pr.title();

            // Post / update approval messages (only needed if the review itself can't contain a body)
            if (!pr.repository().forge().supportsReviewBody()) {
                updateReviewedMessages(comments, allReviews);
            }

            var amendedHash = checkablePullRequest.amendManualReviewers(localHash, censusInstance.namespace(), original.orElse(null));
            var commit = localRepo.lookup(amendedHash).orElseThrow();
            var commitMessage = String.join("\n", commit.message());
            var readyForIntegration = visitor.messages().isEmpty() &&
                                      additionalErrors.isEmpty() &&
                                      integrationBlockers.isEmpty();
            if (isCleanBackport) {
                // Reviews are not needed for clean backports
                readyForIntegration = visitor.isReadyForReview() &&
                                      additionalErrors.isEmpty() &&
                                      integrationBlockers.isEmpty();
            }

            updateMergeReadyComment(readyForIntegration, commitMessage, comments, activeReviews, rebasePossible);
            if (readyForIntegration && rebasePossible) {
                newLabels.add("ready");
            } else {
                newLabels.remove("ready");
            }
            if (!rebasePossible) {
                if (!labels.contains("failed-auto-merge")) {
                    addOutdatedComment(comments);
                }
                newLabels.add("merge-conflict");
            } else {
                newLabels.remove("merge-conflict");
            }

            if (pr.sourceRepository().isPresent()) {
                var branchNames = pr.repository().branches().stream().map(HostedBranch::name).collect(Collectors.toSet());
                if (!pr.repository().url().equals(pr.sourceRepository().get().url()) && branchNames.contains(pr.sourceRef())) {
                    addSourceBranchWarningComment(comments);
                }
            }

            if (!PullRequestUtils.isMerge(pr) && PullRequestUtils.containsForeignMerge(pr, localRepo)) {
                addMergeCommitWarningComment(comments);
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
            var metadata = workItem.getMetadata(censusInstance, title, updatedBody, pr.comments(), activeReviews,
                                                newLabels, pr.targetRef(), pr.isDraft(), expiresIn);
            checkBuilder.metadata(metadata);
        } catch (Exception e) {
            log.throwing("CommitChecker", "checkStatus", e);
            newLabels.remove("ready");
            checkBuilder.metadata("invalid");
            checkBuilder.title("Exception occurred during jcheck - the operation will be retried");
            checkBuilder.summary(e.getMessage());
            checkBuilder.complete(false);
            checkException = e;
        }
        var check = checkBuilder.build();
        pr.updateCheck(check);

        // Synchronize the wanted set of labels
        for (var newLabel : newLabels) {
            if (!labels.contains(newLabel)) {
                pr.addLabel(newLabel);
            }
        }
        for (var oldLabel : labels) {
            if (!newLabels.contains(oldLabel)) {
                pr.removeLabel(oldLabel);
            }
        }

        // After updating the PR, rethrow any exception to automatically retry on transient errors
        if (checkException != null) {
            throw new RuntimeException("Exception during jcheck", checkException);
        }
    }
}
