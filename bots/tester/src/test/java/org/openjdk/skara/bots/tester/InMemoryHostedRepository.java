/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;

class InMemoryHostedRepository implements HostedRepository {
    Forge host;
    URI webUrl;
    URI url;
    long id;

    @Override
    public Forge forge() {
        return host;
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target,
                                         String targetRef,
                                         String sourceRef,
                                         String title,
                                         List<String> body,
                                         boolean draft) {
        return null;
    }

    @Override
    public PullRequest pullRequest(String id) {
        return null;
    }

    @Override
    public List<PullRequest> pullRequests() {
        return null;
    }

    @Override
    public List<PullRequest> openPullRequests() {
        return null;
    }

    @Override
    public List<PullRequest> pullRequestsAfter(ZonedDateTime updatedAfter) {
        return null;
    }

    @Override
    public List<PullRequest> openPullRequestsAfter(ZonedDateTime updatedAfter) {
        return null;
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        return null;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public String group() {
        return null;
    }

    @Override
    public Optional<HostedRepository> parent() {
        return null;
    }

    @Override
    public URI authenticatedUrl() {
        return url;
    }

    @Override
    public URI webUrl() {
        return webUrl;
    }

    @Override
    public URI nonTransformedWebUrl() {
        return webUrl();
    }

    @Override
    public URI webUrl(Hash hash) {
        return null;
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        return null;
    }

    @Override
    public URI diffUrl(String prId) {
        return webUrl();
    }

    @Override
    public VCS repositoryType() {
        return null;
    }

    @Override
    public Optional<String> fileContents(String filename, String ref) {
        return Optional.empty();
    }

    @Override
    public void writeFileContents(String filename, String content, Branch branch, String message, String authorName, String authorEmail, boolean createNewFile) {
    }

    @Override
    public String namespace() {
        return null;
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        return null;
    }

    @Override
    public HostedRepository fork() {
        return null;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public Optional<Hash> branchHash(String ref) {
        return Optional.empty();
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        return null;
    }

    @Override
    public List<HostedBranch> branches() {
        return List.of();
    }

    @Override
    public String defaultBranchName() {
        return null;
    }

    @Override
    public void protectBranchPattern(String ref) {
    }

    @Override
    public void unprotectBranchPattern(String ref) {
    }

    @Override
    public void deleteBranch(String ref) {
    }

    @Override
    public List<CommitComment> commitComments(Hash commit) {
        return List.of();
    }

    @Override
    public CommitComment addCommitComment(Hash commit, String body) {
        return null;
    }

    @Override
    public void updateCommitComment(String id, String body) {
    }

    @Override
    public Optional<HostedCommit> commit(Hash commit, boolean includeDiffs) {
        return Optional.empty();
    }

    @Override
    public List<Check> allChecks(Hash hash) {
        return List.of();
    }

    @Override
    public WorkflowStatus workflowStatus() {
        return WorkflowStatus.DISABLED;
    }

    @Override
    public List<CommitComment> recentCommitComments(ReadOnlyRepository unused, Set<Integer> excludeAuthors,
            List<Branch> branches, ZonedDateTime updatedAfter) {
        return List.of();
    }

    @Override
    public URI createPullRequestUrl(HostedRepository target, String sourceRef, String targetRef) {
        return null;
    }

    @Override
    public URI webUrl(Branch branch) {
        return null;
    }

    @Override
    public URI webUrl(Tag tag) {
        return null;
    }

    @Override
    public URI url() {
        return url;
    }

    @Override
    public List<Collaborator> collaborators() {
        return List.of();
    }

    @Override
    public void addCollaborator(HostUser user, boolean canPush) {
    }

    @Override
    public void removeCollaborator(HostUser user) {
    }

    @Override
    public boolean canPush(HostUser user) {
        return false;
    }

    @Override
    public void restrictPushAccess(Branch branch, HostUser user) {
    }

    @Override
    public List<Label> labels() {
        return List.of();
    }

    @Override
    public void addLabel(Label label) {
    }

    @Override
    public void updateLabel(Label label) {
    }

    @Override
    public void deleteLabel(Label label) {
    }

    @Override
    public int deleteDeployKeys(Duration age) {
        return 0;
    }

    @Override
    public boolean canCreatePullRequest(HostUser user) {
        return false;
    }

    @Override
    public List<PullRequest> openPullRequestsWithTargetRef(String targetRef) {
        return null;
    }

    @Override
    public List<String> deployKeyTitles(Duration age) {
        return List.of();
    }
}
