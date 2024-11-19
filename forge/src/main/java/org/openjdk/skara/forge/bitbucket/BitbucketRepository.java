/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.bitbucket;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.openjdk.skara.forge.Check;
import org.openjdk.skara.forge.Collaborator;
import org.openjdk.skara.forge.CommitComment;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.HostedBranch;
import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.forge.WebHook;
import org.openjdk.skara.forge.WorkflowStatus;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Label;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.Tag;
import org.openjdk.skara.vcs.VCS;

public class BitbucketRepository implements HostedRepository {
    private final BitbucketHost host;
    private final String name;

    public BitbucketRepository(BitbucketHost host, String name) {
        this.host = host;
        this.name = name;
    }

    @Override
    public Forge forge() {
        return host;
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target, String targetRef, String sourceRef, String title, List<String> body, boolean draft) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PullRequest pullRequest(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PullRequest> pullRequests() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PullRequest> openPullRequests() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PullRequest> pullRequestsAfter(ZonedDateTime updatedAfter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PullRequest> openPullRequestsAfter(ZonedDateTime updatedAfter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String group() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HostedRepository> parent() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI authenticatedUrl() {
        if (host.useSsh()) {
            return URI.create("ssh://git@" + host.sshHostString() + "/" + name + ".git");
        } else {
            var builder = URIBuilder
                    .base(host.getUri())
                    .setPath("/" + name + ".git");
            host.getCredential().ifPresent(cred -> builder.setAuthentication(cred.username() + ":" + cred.password()));
            return builder.build();
        }
    }

    @Override
    public URI webUrl() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI nonTransformedWebUrl() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI webUrl(Hash hash) {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI webUrl(Branch branch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI webUrl(Tag tag) {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI diffUrl(String prId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VCS repositoryType() {
        return VCS.GIT;
    }

    @Override
    public URI url() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<String> fileContents(String filename, String ref) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFileContents(String filename, String content, Branch branch, String message, String authorName, String authorEmail, boolean createNewFile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String namespace() {
        return URIBuilder.base(host.getUri()).build().getHost();
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HostedRepository fork() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long id() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Hash> branchHash(String ref) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<HostedBranch> branches() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String defaultBranchName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void protectBranchPattern(String pattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unprotectBranchPattern(String pattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteBranch(String ref) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CommitComment> commitComments(Hash hash) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<CommitComment> recentCommitComments(ReadOnlyRepository localRepo, Set<Integer> excludeAuthors, List<Branch> Branches, ZonedDateTime updatedAfter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CommitComment addCommitComment(Hash hash, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCommitComment(String id, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HostedCommit> commit(Hash hash, boolean includeDiffs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Check> allChecks(Hash hash) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WorkflowStatus workflowStatus() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI createPullRequestUrl(HostedRepository target, String targetRef, String sourceRef) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Collaborator> collaborators() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addCollaborator(HostUser user, boolean canPush) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeCollaborator(HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canPush(HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void restrictPushAccess(Branch branch, HostUser users) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Label> labels() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addLabel(Label label) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateLabel(Label label) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteLabel(Label label) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int deleteDeployKeys(Duration age) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canCreatePullRequest(HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PullRequest> openPullRequestsWithTargetRef(String targetRef) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> deployKeyTitles(Duration age) {
        throw new UnsupportedOperationException();
    }
}
