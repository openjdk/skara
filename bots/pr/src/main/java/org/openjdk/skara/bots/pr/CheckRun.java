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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;
import org.openjdk.skara.email.EmailAddress;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.*;
import java.util.stream.*;

class CheckRun {
    private final CheckWorkItem workItem;
    private final PullRequest pr;
    private final PullRequestInstance prInstance;
    private final List<Comment> comments;
    private final List<Review> allReviews;
    private final List<Review> activeReviews;
    private final Set<String> labels;
    private final CensusInstance censusInstance;
    private final boolean ignoreStaleReviews;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private final String progressMarker = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";
    private final String mergeReadyMarker = "<!-- PullRequestBot merge is ready comment -->";
    private final Pattern mergeSourcePattern = Pattern.compile("^Merge ([-/\\w]+):([-\\w]+$)");
    private final Set<String> newLabels;

    private CheckRun(CheckWorkItem workItem, PullRequest pr, PullRequestInstance prInstance, List<Comment> comments,
                     List<Review> allReviews, List<Review> activeReviews, Set<String> labels,
                     CensusInstance censusInstance, boolean ignoreStaleReviews) {
        this.workItem = workItem;
        this.pr = pr;
        this.prInstance = prInstance;
        this.comments = comments;
        this.allReviews = allReviews;
        this.activeReviews = activeReviews;
        this.labels = new HashSet<>(labels);
        this.newLabels = new HashSet<>(labels);
        this.censusInstance = censusInstance;
        this.ignoreStaleReviews = ignoreStaleReviews;
    }

    static void execute(CheckWorkItem workItem, PullRequest pr, PullRequestInstance prInstance, List<Comment> comments,
                        List<Review> allReviews, List<Review> activeReviews, Set<String> labels, CensusInstance censusInstance,
                        boolean ignoreStaleReviews) {
        var run = new CheckRun(workItem, pr, prInstance, comments, allReviews, activeReviews, labels, censusInstance, ignoreStaleReviews);
        run.checkStatus();
    }

    private boolean isTargetBranchAllowed() {
        var matcher = workItem.bot.allowedTargetBranches().matcher(pr.targetRef());
        return matcher.matches();
    }

    private List<String> allowedTargetBranches() {
        var remoteBranches = prInstance.remoteBranches().stream()
                                       .map(Reference::name)
                                       .map(name -> workItem.bot.allowedTargetBranches().matcher(name))
                                       .filter(Matcher::matches)
                                       .map(Matcher::group)
                                       .collect(Collectors.toList());
        return remoteBranches;
    }

    // For unknown contributors, check that all commits have the same name and email
    private boolean checkCommitAuthor(List<Commit> commits) throws IOException {
        var author = censusInstance.namespace().get(pr.author().id());
        if (author != null) {
            return true;
        }

        var names = new HashSet<String>();
        var emails = new HashSet<String>();

        for (var commit : commits) {
            names.add(commit.author().name());
            emails.add(commit.author().email());
        }

        return ((names.size() == 1) && emails.size() == 1);
    }

    private Optional<String> mergeSourceRepository() {
        var repoMatcher = mergeSourcePattern.matcher(pr.title());
        if (!repoMatcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(repoMatcher.group(1));
    }

    private Optional<String> mergeSourceBranch() {
        var branchMatcher = mergeSourcePattern.matcher(pr.title());
        if (!branchMatcher.matches()) {
            return Optional.empty();
        }
        var mergeSourceBranch = branchMatcher.group(2);
        return Optional.of(mergeSourceBranch);
    }

    // Additional bot-specific checks that are not handled by JCheck
    private List<String> botSpecificChecks() throws IOException {
        var ret = new ArrayList<String>();

        if (!isTargetBranchAllowed()) {
            var error = "The branch `" + pr.targetRef() + "` is not allowed as target branch. The allowed target branches are:\n" +
                    allowedTargetBranches().stream()
                    .map(name -> "   - " + name)
                    .collect(Collectors.joining("\n"));
            ret.add(error);
        }

        var baseHash = prInstance.baseHash();
        var headHash = pr.headHash();
        var commits = prInstance.localRepo().commits(baseHash + ".." + headHash).asList();

        if (!checkCommitAuthor(commits)) {
            var error = "For contributors who are not existing OpenJDK Authors, commit attribution will be taken from " +
                    "the commits in the PR. However, the commits in this PR have inconsistent user names and/or " +
                    "email addresses. Please amend the commits.";
            ret.add(error);
        }

        if (pr.title().startsWith("Merge")) {
            if (commits.size() < 2) {
                ret.add("A Merge PR must contain at least two commits that are not already present in the target.");
            } else {
                if (!commits.get(0).isMerge()) {
                    ret.add("The top commit must be a merge commit.");
                }

                var sourceRepo = mergeSourceRepository();
                var sourceBranch = mergeSourceBranch();
                if (sourceBranch.isPresent() && sourceRepo.isPresent()) {
                    try {
                        var mergeSourceRepo = pr.repository().forge().repository(sourceRepo.get()).orElseThrow(() ->
                                new RuntimeException("Could not find repository " + sourceRepo.get())
                        );
                        try {
                            var sourceHash = prInstance.localRepo().fetch(mergeSourceRepo.url(), sourceBranch.get());
                            if (!prInstance.localRepo().isAncestor(commits.get(1).hash(), sourceHash)) {
                                ret.add("The merge contains commits that are not ancestors of the source");
                            }
                        } catch (IOException e) {
                            ret.add("Could not fetch branch `" + sourceBranch.get() + "` from project `" +
                                            sourceRepo.get() + "` - check that they are correct.");
                        }
                    } catch (RuntimeException e) {
                        ret.add("Could not find project `" +
                                        sourceRepo.get() + "` - check that it is correct.");
                    }
                } else {
                    ret.add("Could not determine the source for this merge. A Merge PR title must be specified on the format: " +
                            "Merge `project`:`branch` to allow verification of the merge contents.");
                }
            }
        }

        for (var blocker : workItem.bot.blockingLabels().entrySet()) {
            if (labels.contains(blocker.getKey())) {
                ret.add(blocker.getValue());
            }
        }

        return ret;
    }

    private void updateCheckBuilder(CheckBuilder checkBuilder, PullRequestCheckIssueVisitor visitor, List<String> additionalErrors) {
        if (visitor.isReadyForReview() && additionalErrors.isEmpty()) {
            checkBuilder.complete(true);
        } else {
            checkBuilder.title("Required");
            var summary = Stream.concat(visitor.getMessages().stream(), additionalErrors.stream())
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
            return reviewer.userName() + " (no known " + namespace.name() + " user name / role)";
        } else {
            var userNameLink = "[" + contributor.username() + "](@" + reviewer.userName() + ")";
            return contributor.fullName().orElse(contributor.username()) + " (" + userNameLink + " - " +
                    getRole(contributor.username()) + ")";
        }
    }

    private String getChecksList(PullRequestCheckIssueVisitor visitor) {
        return visitor.getChecks().entrySet().stream()
                      .map(entry -> "- [" + (entry.getValue() ? "x" : " ") + "] " + entry.getKey())
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

    private String getStatusMessage(List<Comment> comments, List<Review> reviews, PullRequestCheckIssueVisitor visitor) {
        var progressBody = new StringBuilder();
        progressBody.append("## Progress\n");
        progressBody.append(getChecksList(visitor));

        var issue = Issue.fromString(pr.title());
        var issueProject = workItem.bot.issueProject();
        if (issueProject != null && issue.isPresent()) {
            var allIssues = new ArrayList<Issue>();
            allIssues.add(issue.get());
            allIssues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), comments));
            progressBody.append("\n\n## Issue");
            if (allIssues.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            for (var currentIssue : allIssues) {
                var iss = issueProject.issue(currentIssue.id());
                if (iss.isPresent()) {
                    progressBody.append("[");
                    progressBody.append(iss.get().id());
                    progressBody.append("](");
                    progressBody.append(iss.get().webUrl());
                    progressBody.append("): ");
                    progressBody.append(iss.get().title());
                    progressBody.append("\n");
                } else {
                    progressBody.append("⚠️ Failed to retrieve information on issue `");
                    progressBody.append(currentIssue.id());
                    progressBody.append("`.\n");
                }
            }
        }

        getReviewersList(reviews).ifPresent(reviewers -> {
            progressBody.append("\n\n## Reviewers\n");
            progressBody.append(reviewers);
        });

        getContributorsList(comments).ifPresent(contributors -> {
            progressBody.append("\n\n## Contributors\n");
            progressBody.append(contributors);
        });

        return progressBody.toString();
    }

    private String updateStatusMessage(String message) {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(progressMarker);

        if (markerIndex >= 0 && description.substring(markerIndex).equals(message)) {
            log.info("Progress already up to date");
            return description;
        }
        var newBody = (markerIndex < 0 ?
                description :
                description.substring(0, markerIndex)).trim() + "\n" + progressMarker + "\n" + message;

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

    private String getMergeReadyComment(String commitMessage, List<Review> reviews, boolean rebasePossible) {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().userName());
        message.append(" This change now passes all automated pre-integration checks. When the change also ");
        message.append("fulfills all [project specific requirements](https://github.com/");
        message.append(pr.repository().name());
        message.append("/blob/");
        message.append(pr.targetRef());
        message.append("/CONTRIBUTING.md), type `/integrate` in a new comment to proceed. After integration, ");
        message.append("the commit message will be:\n");
        message.append("```\n");
        message.append(commitMessage);
        message.append("\n```\n");

        message.append("- If you would like to add a summary, use the `/summary` command.\n");
        message.append("- To credit additional contributors, use the `/contributor` command.\n");
        message.append("- To add additional solved issues, use the `/solves` command.\n");

        var divergingCommits = prInstance.divergingCommits();
        if (divergingCommits.size() > 0) {
            message.append("\n");
            message.append("Since the source branch of this PR was last updated there ");
            if (divergingCommits.size() == 1) {
                message.append("has been 1 commit ");
            } else {
                message.append("have been ");
                message.append(divergingCommits.size());
                message.append(" commits ");
            }
            message.append("pushed to the `");
            message.append(pr.targetRef());
            message.append("` branch. ");
            if (rebasePossible) {
                message.append("Since there are no conflicts, your changes will automatically be rebased on top of ");
                message.append("these commits when integrating. If you prefer to avoid automatic rebasing, please merge `");
                message.append(pr.targetRef());
                message.append("` into your branch, and then specify the current head hash when integrating, like this: ");
                message.append("`/integrate ");
                message.append(prInstance.targetHash());
                message.append("`.\n");
            } else {
                message.append("Your changes cannot be rebased automatically without conflicts, so you will need to ");
                message.append("merge `");
                message.append(pr.targetRef());
                message.append("` into your branch before integrating.\n");
            }
        }

        if (!ProjectPermissions.mayCommit(censusInstance, pr.author())) {
            message.append("\n");
            var contributor = censusInstance.namespace().get(pr.author().id());
            if (contributor == null) {
                message.append("As you are not a known OpenJDK [Author](https://openjdk.java.net/bylaws#author), ");
            } else {
                message.append("As you do not have Committer status in this project, ");
            }

            message.append("an existing [Committer](https://openjdk.java.net/bylaws#committer) must agree to ");
            message.append("[sponsor](https://openjdk.java.net/sponsor/) your change. ");
            var candidates = reviews.stream()
                                    .filter(review -> ProjectPermissions.mayCommit(censusInstance, review.reviewer()))
                                    .map(review -> "@" + review.reviewer().userName())
                                    .collect(Collectors.joining(", "));
            if (candidates.length() > 0) {
                message.append("Possible candidates are the reviewers of this PR (");
                message.append(candidates);
                message.append(") but any other Committer may sponsor as well. ");
            }
            if (rebasePossible) {
                message.append("\n\n");
                message.append("➡️ To flag this PR as ready for integration with the above commit message, type ");
                message.append("`/integrate` in a new comment. (Afterwards, your sponsor types ");
                message.append("`/sponsor` in a new comment to perform the integration).\n");
            }
        } else if (rebasePossible) {
            message.append("\n");
            message.append("➡️ To integrate this PR with the above commit message, type ");
            message.append("`/integrate` in a new comment.\n");
        }
        message.append(mergeReadyMarker);
        return message.toString();
    }

    private String getMergeNoLongerReadyComment() {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().userName());
        message.append(" This change is no longer ready for integration - check the PR body for details.\n");
        message.append(mergeReadyMarker);
        return message.toString();
    }

    private void updateMergeReadyComment(boolean isReady, String commitMessage, List<Comment> comments, List<Review> reviews, boolean rebasePossible) {
        var existing = findComment(comments, mergeReadyMarker);
        if (isReady) {
            var message = getMergeReadyComment(commitMessage, reviews, rebasePossible);
            if (existing.isEmpty()) {
                pr.addComment(message);
            } else {
                pr.updateComment(existing.get().id(), message);
            }
        } else {
            existing.ifPresent(comment -> pr.updateComment(comment.id(), getMergeNoLongerReadyComment()));
        }
    }

    private void checkStatus() {
        var checkBuilder = CheckBuilder.create("jcheck", pr.headHash());
        var censusDomain = censusInstance.configuration().census().domain();
        Exception checkException = null;

        try {
            // Post check in-progress
            log.info("Starting to run jcheck on PR head");
            pr.createCheck(checkBuilder.build());
            var localHash = prInstance.commit(censusInstance.namespace(), censusDomain, null);
            boolean rebasePossible = true;
            PullRequestCheckIssueVisitor visitor = prInstance.createVisitor(localHash, censusInstance);
            List<String> additionalErrors;
            if (!localHash.equals(prInstance.baseHash())) {
                // Try to rebase
                var ignored = new PrintWriter(new StringWriter());
                var rebasedHash = prInstance.rebase(localHash, ignored);
                if (rebasedHash.isEmpty()) {
                    rebasePossible = false;
                } else {
                    localHash = rebasedHash.get();
                }

                // Determine current status
                var additionalConfiguration = AdditionalConfiguration.get(prInstance.localRepo(), localHash, pr.repository().forge().currentUser(), comments);
                prInstance.executeChecks(localHash, censusInstance, visitor, additionalConfiguration);
                additionalErrors = botSpecificChecks();
            }
            else {
                additionalErrors = List.of("This PR contains no changes");
            }
            updateCheckBuilder(checkBuilder, visitor, additionalErrors);
            updateReadyForReview(visitor, additionalErrors);

            // Calculate and update the status message if needed
            var statusMessage = getStatusMessage(comments, activeReviews, visitor);
            var updatedBody = updateStatusMessage(statusMessage);

            // Post / update approval messages (only needed if the review itself can't contain a body)
            if (!pr.repository().forge().supportsReviewBody()) {
                updateReviewedMessages(comments, allReviews);
            }

            var commit = prInstance.localRepo().lookup(localHash).orElseThrow();
            var commitMessage = String.join("\n", commit.message());
            var readyForIntegration = visitor.getMessages().isEmpty() && additionalErrors.isEmpty();
            updateMergeReadyComment(readyForIntegration, commitMessage, comments, activeReviews, rebasePossible);
            if (readyForIntegration) {
                newLabels.add("ready");
            } else {
                newLabels.remove("ready");
            }
            if (!rebasePossible) {
                newLabels.add("outdated");
            } else {
                newLabels.remove("outdated");
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
            var metadata = workItem.getMetadata(pr.title(), updatedBody, pr.comments(), activeReviews, newLabels,
                                                censusInstance, pr.targetHash(), pr.isDraft());
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
