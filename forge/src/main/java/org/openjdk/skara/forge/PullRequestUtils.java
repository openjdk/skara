/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PullRequestUtils {
    private static Hash commitSquashed(PullRequest pr, Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException {
        return localRepo.commit(commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                                committer.name(), committer.email(), ZonedDateTime.now(), List.of(pr.targetHash()), localRepo.tree(finalHead));
    }

    private static class MergeSource {
        private final String repositoryName;
        private final String branchName;

        private MergeSource(String repositoryName, String branchName) {
            this.repositoryName = repositoryName;
            this.branchName = branchName;
        }
    }

    private final static Pattern mergeSourceFullPattern = Pattern.compile("^Merge ([-/\\w]+):([-\\w]+)$");
    private final static Pattern mergeSourceBranchOnlyPattern = Pattern.compile("^Merge ([-\\w]+)$");

    private static Optional<MergeSource> mergeSource(PullRequest pr, Repository localRepo) {
        var repoMatcher = mergeSourceFullPattern.matcher(pr.title());
        if (!repoMatcher.matches()) {
            var branchMatcher = mergeSourceBranchOnlyPattern.matcher(pr.title());
            if (!branchMatcher.matches()) {
                return Optional.empty();
            }

            // Verify that the branch exists
            var isValidBranch = remoteBranches(pr, localRepo).stream()
                                                             .map(Reference::name)
                                                             .anyMatch(branch -> branch.equals(branchMatcher.group(1)));
            if (!isValidBranch) {
                // Assume the name refers to a sibling repository
                var repoName = Path.of(pr.repository().name()).resolveSibling(branchMatcher.group(1)).toString();
                return Optional.of(new MergeSource(repoName, "master"));
            }

            return Optional.of(new MergeSource(pr.repository().name(), branchMatcher.group(1)));
        }

        return Optional.of(new MergeSource(repoMatcher.group(1), repoMatcher.group(2)));
    }

    private static Hash findSourceHash(PullRequest pr, Repository localRepo, List<CommitMetadata> commits) throws IOException, CommitFailure {
        if (commits.size() < 1) {
            throw new CommitFailure("A merge PR must contain at least one commit that is not already present in the target.");
        }

        var source = mergeSource(pr, localRepo);
        if (source.isEmpty()) {
            throw new CommitFailure("Could not determine the source for this merge. A Merge PR title must be specified on the format: " +
                    "Merge `project`:`branch` to allow verification of the merge contents.");
        }

        // Fetch the source
        Hash sourceHead;
        try {
            var mergeSourceRepo = pr.repository().forge().repository(source.get().repositoryName).orElseThrow(() ->
                    new RuntimeException("Could not find repository " + source.get().repositoryName)
            );
            try {
                sourceHead = localRepo.fetch(mergeSourceRepo.url(), source.get().branchName, false);
            } catch (IOException e) {
                throw new CommitFailure("Could not fetch branch `" + source.get().branchName + "` from project `" +
                        source.get().repositoryName + "` - check that they are correct.");
            }
        } catch (RuntimeException e) {
            throw new CommitFailure("Could not find project `" +
                    source.get().repositoryName + "` - check that it is correct.");
        }

        // Ensure that the source and the target are related
        try {
            localRepo.mergeBase(pr.targetHash(), sourceHead);
        } catch (IOException e) {
            throw new CommitFailure("The target and the source branches do not share common history - cannot merge them.");
        }

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
        var parents = List.of(localRepo.mergeBase(pr.targetHash(), finalHead), sourceHash);

        return localRepo.commit(commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                committer.name(), committer.email(), ZonedDateTime.now(), parents, localRepo.tree(finalHead));
    }

    public static Repository materialize(HostedRepositoryPool hostedRepositoryPool, PullRequest pr, Path path) throws IOException {
        var localRepo = hostedRepositoryPool.checkout(pr.repository(), pr.headHash().hex(), path);
        localRepo.fetch(pr.repository().url(), "+" + pr.targetRef() + ":prutils_targetref", false);
        return localRepo;
    }

    public static boolean isMerge(PullRequest pr) {
        return pr.title().startsWith("Merge");
    }

    public static Hash createCommit(PullRequest pr, Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException, CommitFailure {
        Hash commit;
        if (!isMerge(pr)) {
            commit = commitSquashed(pr, localRepo, finalHead, author, committer, commitMessage);
        } else {
            commit = commitMerge(pr, localRepo, finalHead, author, committer, commitMessage);
        }
        localRepo.checkout(commit, true);
        return commit;
    }

    public static Hash baseHash(PullRequest pr, Repository localRepo) throws IOException {
        return localRepo.mergeBase(pr.targetHash(), pr.headHash());
    }

    public static Set<Path> changedFiles(PullRequest pr, Repository localRepo) throws IOException {
        var ret = new HashSet<Path>();
        var changes = localRepo.diff(baseHash(pr, localRepo), pr.headHash());
        for (var patch : changes.patches()) {
            patch.target().path().ifPresent(ret::add);
            patch.source().path().ifPresent(ret::add);
        }
        return ret;
    }

    private static List<Reference> remoteBranches(PullRequest pr, Repository localRepo) {
        try {
            return localRepo.remoteBranches(pr.repository().url().toString());
        } catch (IOException e) {
            return List.of();
        }
    }
}
