/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

public interface HostedRepository {
    Forge forge();
    PullRequest createPullRequest(HostedRepository target,
                                  String targetRef,
                                  String sourceRef,
                                  String title,
                                  List<String> body,
                                  boolean draft);
    PullRequest pullRequest(String id);

    /**
     * Returns a list of all the pull requests (included open and closed).
     */
    List<PullRequest> pullRequests();

    /**
     * Returns a list of all open pull requests.
     */
    List<PullRequest> openPullRequests();

    /**
     * Returns a list of all pull requests (both open and closed) that have
     * been updated after or on the given time, with a resolution given by
     * Host::timeStampQueryPrecision, ordered by latest updated first. If there
     * are many pull requests that match, the list may have been truncated.
     */
    List<PullRequest> pullRequestsAfter(ZonedDateTime updatedAfter);

    /**
     * Returns a list of all open pull requests that have been updated after or on
     * the given time, with a resolution given by Host::timeStampQueryPrecision.
     */
    List<PullRequest> openPullRequestsAfter(ZonedDateTime updatedAfter);
    List<PullRequest> findPullRequestsWithComment(String author, String body);
    Optional<PullRequest> parsePullRequestUrl(String url);

    /**
     * The full name of the repository, including any namespace/group/organization prefix
     */
    String name();

    /**
     * The group/org name where this repo belongs
     */
    String group();
    Optional<HostedRepository> parent();
    URI authenticatedUrl();
    URI webUrl();
    URI nonTransformedWebUrl();
    URI webUrl(Hash hash);
    URI webUrl(Branch branch);
    URI webUrl(Tag tag);
    URI webUrl(String baseRef, String headRef);
    URI diffUrl(String prId);
    VCS repositoryType();
    /**
     * Returns a URL suitable for CLI interactions with the repository
     */
    URI url();

    /**
     * Returns contents of the file, if the file does not exist, returns Optional.empty(),
     * if the ref does not exist, throws exception.
     */
    Optional<String> fileContents(String filename, String ref);

    /**
     * Writes new contents to a file in the repo by creating a new commit.
     *
     * @param filename    Name of file inside repository to write to
     * @param content     New file content to write, always replacing existing content
     * @param branch      Branch to add commit on top of
     * @param message     Commit message
     * @param authorName  Name of author and committer for commit
     * @param authorEmail Email of author and committer for commit
     * @param createNewFile Determines the file operation mode
     *                      If set to `true`, the operation attempts to create a new file and write contents to it.
     *                      The operation will fail if the file already exists.
     *                      If set to `false`, the operation attempts to update an existing file.
     *                      The operation will fail if the file does not exist.
     */
    void writeFileContents(String filename, String content, Branch branch, String message, String authorName, String authorEmail, boolean createNewFile);
    String namespace();
    Optional<WebHook> parseWebHook(JSONValue body);
    HostedRepository fork();
    long id();
    Optional<Hash> branchHash(String ref);
    List<HostedBranch> branches();
    String defaultBranchName();

    /**
     * Adds a branch protection rule based on a branch pattern. The rule prevents
     * normal users from pushing to the branch, but still allows admins to force
     * push.
     * @param pattern Pattern for branches
     */
    void protectBranchPattern(String pattern);

    /**
     * Removes a branch protection rule based on the branch pattern.
     * @param pattern Pattern for branches
     */
    void unprotectBranchPattern(String pattern);
    void deleteBranch(String ref);
    List<CommitComment> commitComments(Hash hash);
    default List<CommitComment> recentCommitComments() {
        return recentCommitComments(null, Set.of(), null, ZonedDateTime.now().minus(Duration.ofDays(4)));
    }

    /**
     * Fetch recent commit comments from the forge.
     * @param localRepo Only needed for certain implementations. Needs to be a
     *                  reasonably up-to-date clone of this repository
     * @param excludeAuthors Set of authors to exclude from the results
     * @param Branches Optional list of branches to limit the search to if
     *                 supported by the implementation.
     * @param updatedAfter Filter out comments older than this
     * @return A list of CommitComments
     */
    List<CommitComment> recentCommitComments(ReadOnlyRepository localRepo, Set<Integer> excludeAuthors,
            List<Branch> Branches, ZonedDateTime updatedAfter);
    CommitComment addCommitComment(Hash hash, String body);
    void updateCommitComment(String id, String body);

    /**
     * Gets a Commit instance for a given hash, if present.
     * @param hash Hash to get Commit for
     * @param includeDiffs Set to true to include parent diffs in Commit, default false
     * @return Commit instance for the hash in this repository, empty if not
     * found.
     */
    Optional<HostedCommit> commit(Hash hash, boolean includeDiffs);
    default Optional<HostedCommit> commit(Hash hash) {
        return commit(hash, false);
    }
    List<Check> allChecks(Hash hash);
    WorkflowStatus workflowStatus();
    URI createPullRequestUrl(HostedRepository target,
                             String targetRef,
                             String sourceRef);
    List<Collaborator> collaborators();
    void addCollaborator(HostUser user, boolean canPush);
    void removeCollaborator(HostUser user);
    boolean canPush(HostUser user);
    void restrictPushAccess(Branch branch, HostUser users);
    List<Label> labels();
    void addLabel(Label label);
    void updateLabel(Label label);
    void deleteLabel(Label label);

    default PullRequest createPullRequest(HostedRepository target,
                                          String targetRef,
                                          String sourceRef,
                                          String title,
                                          List<String> body) {
        return createPullRequest(target, targetRef, sourceRef, title, body, false);
    }

    default URI reviewUrl(Hash hash) {
        var comments = this.commitComments(hash);
        var reviewComment = comments.stream().filter(
                c -> c.body().startsWith("<!-- COMMIT COMMENT NOTIFICATION -->")).findFirst();

        if (reviewComment.isEmpty()) {
            return null;
        }

        /** The review comment looks like this:
         * <!-- COMMIT COMMENT NOTIFICATION -->
         * ### Review
         *
         * - [openjdk/skara/123](https://git.openjdk.org/skara/pull/123)
         */

        var pattern = Pattern.compile("### Review[^]]*]\\((.*)\\)");
        var matcher = pattern.matcher(reviewComment.get().body());
        if (matcher.find()) {
            return URI.create(matcher.group(1));
        }

        return null;
    }

    /**
     * Returns true if this HostedRepository represents the same repo as the other.
     */
    default boolean isSame(HostedRepository other) {
        return name().equals(other.name()) && forge().name().equals(other.forge().name());
    }

    /**
     * Delete deploy keys which are older than 'age' in this repository
     * The return value is the count of deleted keys
     */
    int deleteDeployKeys(Duration age);

    /**
     * Check whether the user is allowed to create pull request in this repository
     */
    boolean canCreatePullRequest(HostUser user);

    /**
     * Returns a list of open pull requests which targets at the specific ref
     */
    List<PullRequest> openPullRequestsWithTargetRef(String targetRef);

    /**
     * Return the titles of expired deploy keys which are older than 'age' in this repository
     */
    List<String> deployKeyTitles(Duration age);
}
