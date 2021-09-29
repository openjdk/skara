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
package org.openjdk.skara.forge;

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
     * Returns a list of all open pull requests.
     */
    List<PullRequest> pullRequests();

    /**
     * Returns a list of all pull requests (both open and closed) that have been updated after the
     * provided time, ordered by latest updated first. If there are many pull requests that
     * match, the list may have been truncated.
     */
    List<PullRequest> pullRequests(ZonedDateTime updatedAfter);
    List<PullRequest> findPullRequestsWithComment(String author, String body);
    Optional<PullRequest> parsePullRequestUrl(String url);

    /**
     * The full name of the repository, including any namespace/group/organization prefix
     */
    String name();
    Optional<HostedRepository> parent();
    URI url();
    URI webUrl();
    URI nonTransformedWebUrl();
    URI webUrl(Hash hash);
    URI webUrl(Branch branch);
    URI webUrl(Tag tag);
    URI webUrl(String baseRef, String headRef);
    URI diffUrl(String prId);
    VCS repositoryType();
    String fileContents(String filename, String ref);
    String namespace();
    Optional<WebHook> parseWebHook(JSONValue body);
    HostedRepository fork();
    long id();
    Hash branchHash(String ref);
    List<HostedBranch> branches();
    void deleteBranch(String ref);
    List<CommitComment> commitComments(Hash hash);
    default List<CommitComment> recentCommitComments() {
        return recentCommitComments(Map.of(), Set.of());
    }
    List<CommitComment> recentCommitComments(Map<String, Set<Hash>> commitTitleToCommits, Set<Integer> excludeAuthors);
    CommitComment addCommitComment(Hash hash, String body);
    void updateCommitComment(String id, String body);
    Optional<HostedCommit> commit(Hash hash);
    List<Check> allChecks(Hash hash);
    WorkflowStatus workflowStatus();
    URI createPullRequestUrl(HostedRepository target,
                             String targetRef,
                             String sourceRef);
    void addCollaborator(HostUser user, boolean canPush);
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
         * - [openjdk/skara/123](https://git.openjdk.java.net/skara/pull/123)
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
}
