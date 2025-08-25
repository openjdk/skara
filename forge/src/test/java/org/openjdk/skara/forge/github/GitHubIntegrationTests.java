/*
 * Copyright (c) 2022, 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.github;

import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.MemberState;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.TestProperties;
import org.openjdk.skara.test.EnabledIfTestProperties;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Diff;
import org.openjdk.skara.vcs.DiffComparator;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitHubIntegrationTests {
    private static final String GITHUB_REST_URI = "https://github.com";
    private static Forge githubHost;
    private static TestProperties props;

    @BeforeAll
    static void beforeAll() throws IOException {
        props = TestProperties.load();
        if (props.contains("github.user", "github.pat")) {
            HttpProxy.setup();
            // Here use the OAuth2 token. To use a GitHub App, please see ManualForgeTests#gitHubLabels.
            var username = props.get("github.user");
            var token = props.get("github.pat");
            var credential = new Credential(username, token);
            var uri = URIBuilder.base(GITHUB_REST_URI).build();
            githubHost = new GitHubForgeFactory().create(uri, credential, null);
        }
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testDiffEqual() throws IOException {
        var githubRepoOpt = githubHost.repository("openjdk/jfx");
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();

        // Test the files number of a PR
        var prDiffLittle = testDiffOfPullRequest(githubRepo, "756", 2);
        var prDiffMiddle = testDiffOfPullRequest(githubRepo, "764", 105);
        var prDiffLarge = testDiffOfPullRequest(githubRepo, "723", 3000); // Only 3000 files return

        // Test the file number of a commit
        var commitDiffLittle = testDiffOfCommit(githubRepo, new Hash("eb7fa5dd1c0911bca15576060691d884d29895a1"), 2);
        var commitDiffMiddle = testDiffOfCommit(githubRepo, new Hash("b0f2521219efc1b0d0c45088736d5105712bc2c9"), 105);
        var commitDiffLarge = testDiffOfCommit(githubRepo, new Hash("6f28d912024495278c4c35ab054bc2aab480b3e4"), 3000); // Only 3000 files return

        // Test whether the diff is equal.
        assertTrue(DiffComparator.areFuzzyEqual(commitDiffLittle, prDiffLittle));
        assertTrue(DiffComparator.areFuzzyEqual(commitDiffMiddle, prDiffMiddle));
        assertTrue(DiffComparator.areFuzzyEqual(commitDiffLarge, prDiffLarge));
    }

    Diff testDiffOfPullRequest(HostedRepository githubRepo, String prId, int expectedPatchesSize) {
        var pr = githubRepo.pullRequest(prId);
        var diff = pr.diff();
        assertEquals(expectedPatchesSize, diff.patches().size());
        return diff;
    }

    Diff testDiffOfCommit(HostedRepository githubRepo, Hash hash, int expectedPatchesSize) {
        var commit = githubRepo.commit(hash);
        assumeTrue(commit.isPresent());
        assertEquals(1, commit.get().parentDiffs().size());
        assertEquals(expectedPatchesSize, commit.get().parentDiffs().get(0).patches().size());
        return commit.get().parentDiffs().get(0);
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testLastForcePushTime() {
        var githubRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var pr = githubRepo.pullRequest("96");
        var lastForcePushTime = pr.lastForcePushTime();
        assertEquals("2022-05-29T10:32:43Z", lastForcePushTime.get().toString());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testFindIntegratedCommitHash() {
        var playgroundRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(playgroundRepoOpt.isPresent());
        var playgroundRepo = playgroundRepoOpt.get();
        var playgroundPr = playgroundRepo.pullRequest("96");
        var playgroundHashOpt = playgroundPr.findIntegratedCommitHash();
        assertTrue(playgroundHashOpt.isEmpty());
        // `43336822` is the id of the `openjdk` bot(a GitHub App).
        playgroundHashOpt = playgroundPr.findIntegratedCommitHash(List.of("43336822"));
        assertTrue(playgroundHashOpt.isEmpty());

        var jdkRepoOpt = githubHost.repository("openjdk/jdk");
        assumeTrue(jdkRepoOpt.isPresent());
        var jdkRepo = jdkRepoOpt.get();
        var jdkPr = jdkRepo.pullRequest("8648");
        var jdkHashOpt = jdkPr.findIntegratedCommitHash();
        assertTrue(jdkHashOpt.isEmpty());
        // `43336822` is the id of the `openjdk` bot(a GitHub App).
        jdkHashOpt = jdkPr.findIntegratedCommitHash(List.of("43336822"));
        assertTrue(jdkHashOpt.isPresent());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testOversizeComment() {
        var testRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(testRepoOpt.isPresent());
        var testRepo = testRepoOpt.get();
        var testPr = testRepo.pullRequest("99");

        // Test add comment
        Comment comment = testPr.addComment("1".repeat(1_000_000));
        assertTrue(comment.body().contains("..."));
        assertTrue(comment.body().contains("1"));

        // Test update comment
        Comment updateComment = testPr.updateComment(comment.id(), "2".repeat(2_000_000));
        assertTrue(updateComment.body().contains("..."));
        assertTrue(updateComment.body().contains("2"));
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testForcePushTimeWhenPRInDraft() {
        var testRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(testRepoOpt.isPresent());
        var testRepo = testRepoOpt.get();
        var testPr = (GitHubPullRequest) testRepo.pullRequest("107");

        // Won't get the force push time when if the force push is during draft period
        assertEquals(Optional.empty(), testPr.lastForcePushTime());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.repository", "github.repository.branch"})
    void fileContentsNonExisting() {
        var gitHubRepo = githubHost.repository(props.get("github.repository")).orElseThrow();
        var branch = new Branch(props.get("github.repository.branch"));
        var fileName = "testfile-that-does-not-exist.txt";
        var returnedContents = gitHubRepo.fileContents(fileName, branch.name());
        assertTrue(returnedContents.isEmpty());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.repository", "github.repository.branch"})
    void writeFileContents() {
        var gitHubRepo = githubHost.repository(props.get("github.repository")).orElseThrow();
        var branch = new Branch(props.get("github.repository.branch"));

        var fileName = "testfile.txt";

        // Create new file
        {
            var fileContent = "File content";
            gitHubRepo.writeFileContents(fileName, fileContent, branch,
                    "First commit message", "Duke", "duke@openjdk.org", false);
            var returnedContents = gitHubRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }

        // Update file
        {
            var fileContent = "New file content";
            gitHubRepo.writeFileContents(fileName, fileContent, branch,
                    "Second commit message", "Duke", "duke@openjdk.org", true);
            var returnedContents = gitHubRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }

        // Make the file huge
        {
            var fileContent = "a".repeat(1024 * 1024 * 10);
            gitHubRepo.writeFileContents(fileName, fileContent, branch,
                    "Third commit message", "Duke", "duke@openjdk.org", true);
            var returnedContents = gitHubRepo.fileContents(fileName, branch.name());
            assertTrue(returnedContents.isPresent());
            assertEquals(fileContent.length(), returnedContents.get().length());
            assertTrue(fileContent.equals(returnedContents.get()),
                    "Diff for huge file contents, printing first 50 chars of each '"
                    + fileContent.substring(0, 50) + "' '" + returnedContents.get().substring(0, 50) + "'");
        }
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testLastMarkedAsDraftTime() {
        var githubRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var pr = githubRepo.pullRequest("129");
        var lastMarkedAsDraftTime = pr.lastMarkedAsDraftTime();
        assertEquals("2023-02-11T11:51:12Z", lastMarkedAsDraftTime.get().toString());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testClosedBy() {
        var githubRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var pr = githubRepo.pullRequest("96");
        var user = pr.closedBy();
        assertEquals("lgxbslgx", user.get().username());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testGeneratingUrl() {
        var username = props.get("github.user");
        var token = props.get("github.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(GITHUB_REST_URI).build();
        var configuration = JSON.object().put("weburl", JSON.object().put("pattern", "^https://github.com/openjdk/(.*)$").put("replacement", "https://git.openjdk.org/$1"));
        var githubHost = new GitHubForgeFactory().create(uri, credential, configuration);

        var githubRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var pr = githubRepo.pullRequest("129");

        var labelComment = pr.comments().stream()
                .filter(comment -> comment.body().contains("The following label will be automatically applied to this pull request:"))
                .findFirst()
                .get();
        assertEquals("https://git.openjdk.org/playground/pull/129#issuecomment-1426703897", pr.commentUrl(labelComment).toString());

        var reviewComment = pr.reviewComments().get(0);
        assertEquals("https://git.openjdk.org/playground/pull/129#discussion_r1108931186", pr.reviewCommentUrl(reviewComment).toString());

        var review = pr.reviews().get(0);
        assertEquals("https://git.openjdk.org/playground/pull/129#pullrequestreview-1302142525", pr.reviewUrl(review).toString());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository"})
    void testDeleteDeployKey() {
        var githubRepoOpt = githubHost.repository(props.get("github.repository"));
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        githubRepo.deleteDeployKeys(Duration.ofHours(24));
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.repository", "github.repository.branch", "github.user2"})
    void restrictPushAccess() {
        var gitHubRepo = githubHost.repository(props.get("github.repository")).orElseThrow();
        var branch = new Branch(props.get("github.repository.branch"));
        var user = githubHost.user(props.get("github.user2")).orElseThrow();
        gitHubRepo.restrictPushAccess(branch, user);
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository"})
    void testDeployKeyTitles() {
        var githubRepoOpt = githubHost.repository(props.get("github.repository"));
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var expiredDeployKeys = githubRepo.deployKeyTitles(Duration.ofMinutes(5));
        assertTrue(expiredDeployKeys.contains("Test1"));
        assertTrue(expiredDeployKeys.contains("Test2"));
    }


    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.repository", "github.prId", "github.commitHash"})
    void testBackportCleanIgnoreCopyRight() {
        var gitHubRepo = githubHost.repository(props.get("github.repository")).orElseThrow();

        var pr = gitHubRepo.pullRequest(props.get("github.prId"));
        var hash = new Hash(props.get("github.commitHash"));
        var repoName = pr.repository().forge().search(hash, true);
        assertTrue(repoName.isPresent());
        var repository = pr.repository().forge().repository(repoName.get());
        assertTrue(repository.isPresent());
        var commit = repository.get().commit(hash, true);
        var backportDiff = commit.orElseThrow().parentDiffs().get(0);
        var prDiff = pr.diff();
        var isClean = DiffComparator.areFuzzyEqual(backportDiff, prDiff);
        assertTrue(isClean);
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.repository", "github.repository.branch"})
    void testDefaultBranchName() {
        var gitHubRepo = githubHost.repository(props.get("github.repository")).orElseThrow();
        assertEquals(props.get("github.repository.branch"), gitHubRepo.defaultBranchName());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository"})
    void testCollaborators() {
        var gitHubRepo = githubHost.repository(props.get("github.repository")).orElseThrow();
        var collaborators = gitHubRepo.collaborators();
        assertNotNull(collaborators);
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat"})
    void testGetUser() {
        var userName = props.get("github.user");
        var userByName = githubHost.user(userName).orElseThrow();
        var userById = githubHost.userById(userByName.id()).orElseThrow();
        assertEquals(userByName, userById);
    }

    /**
     * Expects
     * github.group: Name of GitHub organization with at least one member
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.group"})
    void testGroupMembers() {
        var groupName = props.get("github.group");
        var membersList = githubHost.groupMembers(groupName);
        assertNotNull(membersList);
        assertNotEquals(0, membersList.size());
    }

    /**
     * Expects:
     * github.group: Name of GitHub organization
     * github.group.member: Name of user which is a member of the organization
     * github.group.notmember: Name of user which is not a member of the organization
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.group", "github.group.member", "github.group.notmember"})
    void testGroupMemberState() {
        var groupName = props.get("github.group");
        var memberName = props.get("github.group.member");
        var notMemberName = props.get("github.group.notmember");
        var member = githubHost.user(memberName).orElseThrow();
        var notMember = githubHost.user(notMemberName).orElseThrow();
        assertEquals(MemberState.ACTIVE, githubHost.groupMemberState(groupName, member));
        assertEquals(MemberState.MISSING, githubHost.groupMemberState(groupName, notMember));
    }

    /**
     * Expects:
     * github.group: Name of GitHub organization
     * github.group.user: Name of user which may or may not be a member already,
     *                    but cannot be an owner
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.group", "github.group.user"})
    void testAddGroupMember() {
        var groupName = props.get("github.group");
        var userName = props.get("github.group.user");
        var user = githubHost.user(userName).orElseThrow();
        githubHost.addGroupMember(groupName, user);
        assertNotEquals(MemberState.MISSING, githubHost.groupMemberState(groupName, user));
    }

    /**
     * Expects:
     * github.collaborators.repository: Github repository where user has admin access
     * github.collaborators.user: User not currently a collaborator in repository
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat",
                              "github.collaborators.repository", "github.collaborators.user"})
    void addRemoveCollaborator() {
        var gitHubRepo = githubHost.repository(props.get("github.collaborators.repository")).orElseThrow();
        var userName = props.get("github.collaborators.user");
        var user = gitHubRepo.forge().user(userName).orElseThrow();
        gitHubRepo.addCollaborator(user, false);
        // On Github, the user has to accept an invitation before becoming a collaborator
        // so we cannot verify automatically here.
        gitHubRepo.removeCollaborator(user);
    }

    /**
     * Expects:
     * github.repository: Github repository where pull requests can be made, e.g. playground
     * github.repository.branch: Branch in github.repository to create pull request from
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository",
            "github.repository.branch"})
    void createPullRequestSameRepo() {
        var gitHubRepo = githubHost.repository((props.get("github.repository"))).orElseThrow();
        var sourceBranch = props.get("github.repository.branch");
        var pr = gitHubRepo.createPullRequest(gitHubRepo, "master", sourceBranch,
                "Skara test PR from same repo", List.of("Skara test PR from same repo"));
        assertEquals(gitHubRepo.name(), pr.repository().name());
        assertEquals(gitHubRepo.group(), pr.repository().group());
        assertEquals("master", pr.targetRef());
        assertEquals(gitHubRepo.name(), pr.sourceRepository().orElseThrow().name());
        assertEquals(gitHubRepo.group(), pr.sourceRepository().orElseThrow().group());
        pr.setState(PullRequest.State.CLOSED);
    }

    /**
     * Expects:
     * github.repository: Github repository where pull requests can be made, e.g. playground
     * github.user: User with a fork of github.repository with permission to create PR in github.respository
     * github.user.fork.branch: Name of branch in user fork to create pull request from
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository",
            "github.user.fork.branch"})
    void createPullRequestUserFork() {
        var gitHubRepo = githubHost.repository((props.get("github.repository"))).orElseThrow();
        var sourceBranch = props.get("github.user.fork.branch");
        var userName = props.get("github.user");
        String name = gitHubRepo.name().split("/")[1];
        var sourceRepo = githubHost.repository(userName + "/" + name).orElseThrow();
        var pr = sourceRepo.createPullRequest(gitHubRepo, "master", sourceBranch,
                "Skara test PR from user fork", List.of("Skara test PR from user fork"));
        assertEquals(gitHubRepo.name(), pr.repository().name());
        assertEquals(gitHubRepo.group(), pr.repository().group());
        assertEquals("master", pr.targetRef());
        assertEquals(sourceRepo.name(), pr.sourceRepository().orElseThrow().name());
        assertEquals(sourceRepo.group(), pr.sourceRepository().orElseThrow().group());
        pr.setState(PullRequest.State.CLOSED);
    }

    /**
     * Expects:
     * github.repository: Github repository where pull requests can be made, e.g. playground
     * github.user: User with a fork of github.repository with permission to create PR in github.respository
     * github.group: Group with fork of github.repository
     * github.group.fork.branch: Name of branch in group fork to create pull request from
     */
    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository",
            "github.group", "github.group.fork.branch"})
    void createPullRequestGroupFork() {
        var gitHubRepo = githubHost.repository((props.get("github.repository"))).orElseThrow();
        var groupName = props.get("github.group");
        var sourceBranch = props.get("github.group.fork.branch");
        String name = gitHubRepo.name().split("/")[1];
        var sourceRepo = githubHost.repository(groupName + "/" + name).orElseThrow();
        var pr = sourceRepo.createPullRequest(gitHubRepo, "master", sourceBranch,
                "Skara test PR from group fork", List.of("Skara test PR from group fork"));
        assertEquals(gitHubRepo.name(), pr.repository().name());
        assertEquals(gitHubRepo.group(), pr.repository().group());
        assertEquals("master", pr.targetRef());
        assertEquals(sourceRepo.name(), pr.sourceRepository().orElseThrow().name());
        assertEquals(sourceRepo.group(), pr.sourceRepository().orElseThrow().group());
        pr.setState(PullRequest.State.CLOSED);
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.stale.repository", "github.stale.commitHash"})
    void testRedirectLink() {
        var githubRepoOpt = githubHost.repository(props.get("github.stale.repository"));
        var checks = githubRepoOpt.get().allChecks(new Hash(props.get("github.stale.commitHash")));
        assertFalse(checks.isEmpty());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository", "github.prId", "github.repository.commitHash"})
    void testDiffComplete() {
        var githubRepoOpt = githubHost.repository(props.get("github.repository"));
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var commit = githubRepo.commit(new Hash(props.get("github.repository.commitHash")), true);
        var diff = commit.get().parentDiffs().get(0);
        assertFalse(diff.complete());

        var pr = githubRepo.pullRequest(props.get("github.prId"));
        var prDiff = pr.diff();
        assertFalse(prDiff.complete());
    }

    @Test
    @EnabledIfTestProperties({"github.user", "github.pat", "github.repository", "github.prId"})
    void testLastCommitTime() {
        var githubRepoOpt = githubHost.repository(props.get("github.repository"));
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();

        var pr = githubRepo.pullRequest(props.get("github.prId"));
        var lastTouchedTime = pr.lastTouchedTime();
    }
}
