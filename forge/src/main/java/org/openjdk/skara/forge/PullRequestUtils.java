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
package org.openjdk.skara.forge;

import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PullRequestUtils {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.forge");

    private static Hash commitSquashed(Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException {
        return localRepo.commit(commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                                committer.name(), committer.email(), ZonedDateTime.now(), List.of(targetHash(localRepo)), localRepo.tree(finalHead));
    }

    public final static Pattern mergeSourcePattern = Pattern.compile("^Merge ([-/.\\w:+]+)$");
    private final static Pattern hashSourcePattern = Pattern.compile("[0-9a-fA-F]{6,40}");

    private static Optional<Hash> fetchRef(Repository localRepo, URI uri, String ref) {
        // Just a plain name - is this a branch?
        try {
            return localRepo.fetch(uri, "+" + ref + ":refs/heads/merge_source", false);
        } catch (IOException e) {
            // Ignored
        }

        // Perhaps it is an actual tag object - it cannot be fetched to a branch ref
        try {
            return localRepo.fetch(uri, "+" + ref + ":refs/tags/merge_source_tag", false);
        } catch (IOException e) {
            // Ignored
        }

        return Optional.empty();
    }

    private static Hash fetchMergeSource(PullRequest pr, Repository localRepo) throws IOException, CommitFailure {
        var sourceMatcher = mergeSourcePattern.matcher(pr.title());
        if (!sourceMatcher.matches()) {
            throw new CommitFailure("Could not determine the source for this merge. A Merge PR title must be specified in the format: `" +
                                            mergeSourcePattern + "` to allow verification of the merge contents.");
        }
        var source = sourceMatcher.group(1);

        // A hash in the PRs local history can also be a valid source
        var hashSourceMatcher = hashSourcePattern.matcher(source);
        if (hashSourceMatcher.matches()) {
            var hash = localRepo.resolve(source);
            if (hash.isPresent()) {
                // A valid merge source hash cannot be an ancestor of the target branch (if so it would not need to be merged)
                var prTargetHash = PullRequestUtils.targetHash(localRepo);
                if (!localRepo.isAncestor(hash.get(), prTargetHash)) {
                    return hash.get();
                }
            }
        }

        String repoName;
        String ref;
        if (!source.contains(":")) {
            // Try to fetch the source as a name of a ref (branch or tag)
            var hash = fetchRef(localRepo, pr.repository().authenticatedUrl(), source);
            if (hash.isPresent()) {
                return hash.get();
            }

            // Only valid option now is a repository - use default ref
            repoName = source;
            ref = Branch.defaultFor(VCS.GIT).name();
        } else {
            repoName = source.split(":", 2)[0];
            ref = source.split(":", 2)[1];
        }

        // If the repository name is unqualified we assume it is a sibling
        if (!repoName.contains("/")) {
            repoName = Path.of(pr.repository().name()).resolveSibling(repoName).toString();
        }

        // Validate the repository
        var sourceRepo = pr.repository().forge().repository(repoName);
        if (sourceRepo.isEmpty()) {
            throw new CommitFailure("Could not find project `" + repoName + "` - check that it is correct.");
        }

        var hash = fetchRef(localRepo, sourceRepo.get().authenticatedUrl(), ref);
        if (hash.isPresent()) {
            return hash.get();
        } else {
            throw new CommitFailure("Could not find the branch or tag `" + ref + "` in the project `" + repoName + "` - check that it is correct.");
        }
    }

    private static Hash findSourceHash(PullRequest pr, Repository localRepo, List<CommitMetadata> commits) throws IOException, CommitFailure {
        if (commits.size() < 1) {
            throw new CommitFailure("A merge PR must contain at least one commit that is not already present in the target.");
        }

        // Fetch the source
        var sourceHead = fetchMergeSource(pr, localRepo);

        // Ensure that the source and the target are related
        localRepo.mergeBaseOptional(targetHash(localRepo), sourceHead)
                .orElseThrow(() -> new CommitFailure("The target and the source branches do not share common history - cannot merge them."));

        // Find the most recent commit from the merge source not present in the target
        var sourceHash = localRepo.mergeBase(pr.headHash(), sourceHead);
        var commitHashes = commits.stream()
                                  .map(CommitMetadata::hash)
                                  .collect(Collectors.toSet());
        if (!commitHashes.contains(sourceHash)) {
            throw new CommitFailure("A merge PR must contain at least one commit from the source branch that is not already present in the target.");
        }

        return sourceHash;
    }

    private static Hash commitMerge(PullRequest pr, Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException, CommitFailure {
        var commits = localRepo.commitMetadata(baseHash(pr, localRepo), finalHead);
        var sourceHash = findSourceHash(pr, localRepo, commits);
        var parents = List.of(localRepo.mergeBase(targetHash(localRepo), finalHead), sourceHash);

        return localRepo.commit(commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                committer.name(), committer.email(), ZonedDateTime.now(), parents, localRepo.tree(finalHead));
    }

    public static Hash targetHash(Repository localRepo) throws IOException {
        return localRepo.resolve("prutils_targetref").orElseThrow(() -> new IllegalStateException("Must materialize PR first"));
    }

    public static Repository materialize(HostedRepositoryPool hostedRepositoryPool, PullRequest pr, Path path) throws IOException {
        var localRepo = hostedRepositoryPool.checkout(pr.repository(), pr.headHash().hex(), path);
        localRepo.fetch(pr.repository().authenticatedUrl(), "+" + pr.targetRef() + ":prutils_targetref", false).orElseThrow();
        return localRepo;
    }

    public static boolean isAncestorOfTarget(Repository localRepo, Hash hash) throws IOException {
        Optional<Hash> targetHash = localRepo.resolve("prutils_targetref");
        return localRepo.isAncestor(hash, targetHash.orElseThrow());
    }

    public static boolean isMerge(PullRequest pr) {
        return pr.title().startsWith("Merge");
    }

    public static Hash createCommit(PullRequest pr, Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException, CommitFailure {
        Hash commit;
        if (!isMerge(pr)) {
            commit = commitSquashed(localRepo, finalHead, author, committer, commitMessage);
        } else {
            commit = commitMerge(pr, localRepo, finalHead, author, committer, commitMessage);
        }
        localRepo.checkout(commit, true);
        return commit;
    }

    public static Hash baseHash(PullRequest pr, Repository localRepo) throws IOException {
        return localRepo.mergeBase(targetHash(localRepo), pr.headHash());
    }

    /**
     * Returns the set of files changed in the pull request with respect to the base hash.
     */
    public static Set<Path> changedFiles(PullRequest pr, Repository localRepo) throws IOException {
        return changedFilesBetween(localRepo, baseHash(pr, localRepo), pr.headHash());
    }

    /**
     * Returns the set of files changed in the pull request since a given commit.
     */
    public static Set<Path> changedFiles(PullRequest pr, Repository localRepo, Hash commitHash) throws IOException {
        // If commitHash is not the ancestor of pr.headHash(), it means the user did force push.
        if (!localRepo.isAncestor(commitHash, pr.headHash())) {
            return changedFiles(pr, localRepo);
        }
        return changedFilesBetween(localRepo, commitHash, pr.headHash());
    }

    private static Set<Path> changedFilesBetween(Repository localRepo, Hash from, Hash to) throws IOException {
        Set<Path> changedFiles = new HashSet<>();

        Set<Path> mergeBaseToFromChangedFiles = getChangedFilesSinceMergeBase(localRepo, from);
        Set<Path> mergeBaseToToChangedFiles = getChangedFilesSinceMergeBase(localRepo, to);

        for (Path file : mergeBaseToToChangedFiles) {
            if (!mergeBaseToFromChangedFiles.contains(file)) {
                changedFiles.add(file);
            }
        }
        return changedFiles;
    }

    private static Set<Path> getChangedFilesSinceMergeBase(Repository localRepo, Hash commit) throws IOException {
        Set<Path> changedFiles = new HashSet<>();
        Hash mergeBase = localRepo.mergeBase(targetHash(localRepo), commit);
        var diff = localRepo.diff(mergeBase, commit);
        for (var patch : diff.patches()) {
            if (patch.status().isDeleted() || patch.status().isRenamed()) {
                patch.source().path().ifPresent(changedFiles::add);
            }
            if (!patch.status().isDeleted()) {
                patch.target().path().ifPresent(changedFiles::add);
            }
        }
        return changedFiles;
    }

    public static boolean containsForeignMerge(PullRequest pr, Repository localRepo) throws IOException {
        var baseHash = baseHash(pr, localRepo);
        var commits = localRepo.commitMetadata(baseHash, pr.headHash());
        var mergeParents = commits.stream()
                                  .filter(CommitMetadata::isMerge)
                                  .flatMap(commit -> commit.parents().stream().skip(1))
                                  .collect(Collectors.toList());
        for (var mergeParent : mergeParents) {
            var mergeBase = localRepo.mergeBaseOptional(targetHash(localRepo), mergeParent);
            if (mergeBase.isEmpty() || !mergeBase.get().equals(mergeParent)) {
                return true;
            }
        }
        return false;
    }

    private static final String pullRequestMessage = "A pull request was submitted for review.";

    /**
     * Adds a link to a pull request as a formatted comment to an issue.
     * @param issue Issue to add comment to
     * @param pr PR to link to
     */
    public static void postPullRequestLinkComment(Issue issue, PullRequest pr) {
        var alreadyPostedComment = issue.comments().stream()
                .filter(comment -> comment.author().equals(issue.project().issueTracker().currentUser()))
                .filter(comment -> comment.body().contains(pullRequestMessage) && comment.body().contains(pr.webUrl().toString()))
                .findFirst();
        String message = pullRequestMessage + "\n" +
                "Branch: " + pr.targetRef() + "\n" +
                "URL: " + pr.webUrl().toString() + "\n" +
                "Date: " + pr.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000"));
        if (alreadyPostedComment.isEmpty()) {
            issue.addComment(message);
        } else if (!alreadyPostedComment.get().body().equals(message)) {
            issue.updateComment(alreadyPostedComment.get().id(), message);
        }
    }

    /**
     * Removes a previously added comment with a link to a pull request from an issue.
     * @param issue Issue to remove comment from
     * @param pr PR that the comment linked to
     */
    public static void removePullRequestLinkComment(Issue issue, PullRequest pr) {
        var postedComment = issue.comments().stream()
                .filter(comment -> comment.author().equals(issue.project().issueTracker().currentUser()))
                .filter(comment -> comment.body().contains(pullRequestMessage) && comment.body().contains(pr.webUrl().toString()))
                .findAny();
        postedComment.ifPresent(issue::removeComment);
    }

    /**
     * Searches the comments of an issue for a pull request link.
     * @param issue Issue to search
     * @return List of all Web URI links to pull requests found in all the comments
     */
    public static List<URI> pullRequestCommentLink(Issue issue) {
        return issue.comments().stream()
                .filter(comment -> comment.author().equals(issue.project().issueTracker().currentUser()))
                .map(PullRequestUtils::parsePullRequestComment)
                .flatMap(Optional::stream)
                .toList();

    }

    private static final Pattern PR_URL_PATTERN = Pattern.compile("^URL: (.*)");

    private static Optional<URI> parsePullRequestComment(Comment comment) {
        var lines = comment.body().lines().toList();
        if (!lines.get(0).equals(pullRequestMessage)) {
            return Optional.empty();
        }
        var urlMatcher = PR_URL_PATTERN.matcher(lines.get(2));
        if (urlMatcher.matches()) {
            var url = urlMatcher.group(1);
            try {
                return Optional.of(URI.create(url));
            } catch (IllegalArgumentException e) {
                log.log(Level.WARNING, "Invalid link in pull request link comment: " + url, e);
            }
        }
        return Optional.empty();
    }
}
