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
package org.openjdk.skara.forge.gitlab;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestProperties;
import org.openjdk.skara.test.EnabledIfTestProperties;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.DiffComparator;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.openjdk.skara.vcs.git.GitRepository;

import static org.junit.jupiter.api.Assertions.*;

class GitLabIntegrationTests {
    private static TestProperties props;
    private static GitLabHost gitLabHost;

    @BeforeAll
    static void beforeAll() throws IOException {
        props = TestProperties.load();
        if (props.contains("gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group")) {
            var username = props.get("gitlab.user");
            var token = props.get("gitlab.pat");
            var credential = new Credential(username, token);
            var uri = URIBuilder.base(props.get("gitlab.uri")).build();
            gitLabHost = new GitLabHost("gitlab", uri, false, credential, Arrays.asList(props.get("gitlab.group").split(",")));
        }
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.merge.request.id", "gitlab.review.hash"})
    void testReviews() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));

        var reviewList = gitLabMergeRequest.reviews();
        var actualHash = reviewList.get(0).hash().orElse(new Hash(""));
        assertEquals(props.get("gitlab.review.hash"), actualHash.hex());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.merge.request.id",
                              "gitlab.version.hash", "gitlab.version.url",
                              "gitlab.nonversion.hash", "gitlab.nonversion.url"})
    void testFilesUrl() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));

        // Test a version hash
        var versionUrl = gitLabMergeRequest.filesUrl(new Hash(props.get("gitlab.version.hash")));
        assertEquals(props.get("gitlab.version.url"), versionUrl.toString());

        // Test a non-version hash
        var nonVersionUrl = gitLabMergeRequest.filesUrl(new Hash(props.get("gitlab.nonversion.hash")));
        assertEquals(props.get("gitlab.nonversion.url"), nonVersionUrl.toString());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.merge.request.id"})
    void testLabels() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));

        // Get the labels
        var labels = gitLabMergeRequest.labelNames();
        assertEquals(1, labels.size());
        assertEquals("test", labels.get(0));

        // Add a label
        gitLabMergeRequest.addLabel("test1");
        labels = gitLabMergeRequest.labelNames();
        assertEquals(2, labels.size());
        assertEquals("test", labels.get(0));
        assertEquals("test1", labels.get(1));

        // Remove a label
        gitLabMergeRequest.removeLabel("test1");
        labels = gitLabMergeRequest.labelNames();
        assertEquals(1, labels.size());
        assertEquals("test", labels.get(0));
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.merge.request.id"})
    void testOversizeComment() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));

        // Test add comment
        Comment comment = gitLabMergeRequest.addComment("1".repeat(1_000_000));
        assertTrue(comment.body().contains("..."));
        assertTrue(comment.body().contains("1"));

        // Test update comment
        Comment updateComment = gitLabMergeRequest.updateComment(comment.id(), "2".repeat(2_000_000));
        assertTrue(updateComment.body().contains("..."));
        assertTrue(updateComment.body().contains("2"));
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.repository.branch"})
    void fileContentsNonExisting() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var branch = new Branch(props.get("gitlab.repository.branch"));

        var fileName = "testfile-that-does-not-exist.txt";
        var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
        assertTrue(returnedContents.isEmpty());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri",
                              "gitlab.repository", "gitlab.repository.branch"})
    void writeFileContents() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var branch = new Branch(props.get("gitlab.repository.branch"));

        var fileName = "testfile.txt";

        // Create new file
        {
            var fileContent = "File content";
            gitLabRepo.writeFileContents(fileName, fileContent, branch,
                    "First commit message", "Duke", "duke@openjdk.org", true);
            var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }

        // Update file
        {
            var fileContent = "New file content";
            gitLabRepo.writeFileContents(fileName, fileContent, branch,
                    "Second commit message", "Duke", "duke@openjdk.org", false);
            var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }

        // Make the file huge
        {
            var fileContent = "a".repeat(1024 * 1024 * 10);
            gitLabRepo.writeFileContents(fileName, fileContent, branch,
                    "Third commit message", "Duke", "duke@openjdk.org", false);
            var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository"})
    void branchProtection() throws IOException {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var branchName = "pr/4711";

        gitLabRepo.protectBranchPattern(branchName);
        // Don't fail on repeated invocations
        gitLabRepo.protectBranchPattern(branchName);

        try (var tempDir = new TemporaryDirectory()) {
            var localRepoDir = tempDir.path().resolve("local");
            var localRepo = GitRepository.clone(gitLabRepo.authenticatedUrl(), localRepoDir, false, null);
            var head = localRepo.head();
            localRepo.push(head, gitLabRepo.authenticatedUrl(), branchName, true);

            gitLabRepo.unprotectBranchPattern(branchName);
            // Don't fail on repeated invocations
            gitLabRepo.unprotectBranchPattern(branchName);

            gitLabRepo.deleteBranch(branchName);
        }
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.merge.request.id"})
    void testLastMarkedAsDraftTime() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));

        var lastMarkedAsDraftTime = gitLabMergeRequest.lastMarkedAsDraftTime();
        assertEquals("2023-02-11T08:43:52.408Z", lastMarkedAsDraftTime.orElseThrow().toString());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.targetRef", "gitlab.sourceRef"})
    void testDraftMR() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();

        var gitLabMergeRequest = gitLabRepo.createPullRequest(gitLabRepo, props.get("gitlab.targetRef"),
                props.get("gitlab.sourceRef"), "Test", List.of("test"), true);
        assertTrue(gitLabMergeRequest.isDraft());
        assertEquals("Draft: Test", gitLabMergeRequest.title());

        gitLabMergeRequest.makeNotDraft();
        gitLabMergeRequest = gitLabRepo.pullRequest(gitLabMergeRequest.id());
        assertFalse(gitLabMergeRequest.isDraft());
        assertEquals("Test", gitLabMergeRequest.title());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.merge.request.id", "comment_html_url",
                              "reviewComment_html_url", "review_html_url"})
    void testHtmlUrl() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));

        var comment = gitLabMergeRequest.comments().get(0);
        assertEquals(props.get("comment_html_url"), gitLabMergeRequest.commentUrl(comment).toString());

        var reviewComment = gitLabMergeRequest.reviewComments().get(0);
        assertEquals(props.get("reviewComment_html_url"), gitLabMergeRequest.reviewCommentUrl(reviewComment).toString());

        var review = gitLabMergeRequest.reviews().get(0);
        assertEquals(props.get("review_html_url"), gitLabMergeRequest.reviewUrl(review).toString());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository"})
    void testDeleteDeployKey() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        gitLabRepo.deleteDeployKeys(Duration.ofHours(24));
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository"})
    void testDeployKeyTitles() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var expiredDeployKeys = gitLabRepo.deployKeyTitles(Duration.ofMinutes(5));
        assertTrue(expiredDeployKeys.contains("test1"));
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.prId", "gitlab.commitHash"})
    void testBackportCleanIgnoreCopyRight() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();

        var pr = gitLabRepo.pullRequest(props.get("gitlab.prId"));
        var hash = new Hash(props.get("gitlab.commitHash"));
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
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "commit.comments.gitlab.repository", "commit.comments.hash"})
    void testCommitComments() {
        var gitLabRepo = gitLabHost.repository(props.get("commit.comments.gitlab.repository")).orElseThrow();
        var commitHash = new Hash(props.get("commit.comments.hash"));

        var comments = gitLabRepo.commitComments(commitHash);

        assertFalse(comments.isEmpty());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "commit.comments.gitlab.repository", "commit.comments.local.repository"})
    void testRecentCommitComments() throws IOException {
        var gitLabRepo = gitLabHost.repository(props.get("commit.comments.gitlab.repository")).orElseThrow();

        var localRepo = GitRepository.get(Path.of(props.get("commit.comments.local.repository"))).orElseThrow();

        var comments = gitLabRepo.recentCommitComments(localRepo, Set.of(), List.of(new Branch("master")),
                ZonedDateTime.now().minus(Duration.ofDays(4)));

        assertFalse(comments.isEmpty());

        // Pause here to update the local repository to trigger another code path
        var comments2 = gitLabRepo.recentCommitComments(localRepo, Set.of(), List.of(new Branch("master")),
                ZonedDateTime.now().minus(Duration.ofDays(4)));

        assertFalse(comments2.isEmpty());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository", "gitlab.repository.branch"})
    void testDefaultBranchName() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        assertEquals(props.get("gitlab.repository.branch"), gitLabRepo.defaultBranchName());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group"})
    void testGetUser() {
        var userName = props.get("gitlab.user");
        var userByName = gitLabHost.user(userName).orElseThrow();
        var userById = gitLabHost.userById(userByName.id()).orElseThrow();
        assertEquals(userByName, userById);
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.repository"})
    void testCollaborators() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var collaborators = gitLabRepo.collaborators();
        assertNotNull(collaborators);
    }

    /**
     * Expects:
     * gitlab.collaborators.repository: GitLab repository where user has admin access
     * gitlab.collaborators.user: User not currently a collaborator in repository
     */
    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
                              "gitlab.collaborators.repository", "gitlab.collaborators.user"})
    void addRemoveCollaborator() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.collaborators.repository")).orElseThrow();
        var userName = props.get("gitlab.collaborators.user");
        var user = gitLabRepo.forge().user(userName).orElseThrow();
        gitLabRepo.addCollaborator(user, false);
        {
            var collaborators = gitLabRepo.collaborators();
            var collaborator = collaborators.stream()
                    .filter(c -> c.user().username().equals(userName))
                    .findAny().orElseThrow();
            assertFalse(collaborator.canPush());
        }
        gitLabRepo.removeCollaborator(user);
        {
            var collaborators = gitLabRepo.collaborators();
            var collaborator = collaborators.stream()
                    .filter(c -> c.user().username().equals(userName))
                    .findAny();
            assertTrue(collaborator.isEmpty());
        }
        gitLabRepo.addCollaborator(user, true);
        {
            var collaborators = gitLabRepo.collaborators();
            var collaborator = collaborators.stream()
                    .filter(c -> c.user().username().equals(userName))
                    .findAny().orElseThrow();
            assertTrue(collaborator.canPush());
        }
        gitLabRepo.removeCollaborator(user);
        {
            var collaborators = gitLabRepo.collaborators();
            var collaborator = collaborators.stream()
                    .filter(c -> c.user().username().equals(userName))
                    .findAny();
            assertTrue(collaborator.isEmpty());
        }
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
            "gitlab.repository", "gitlab.merge.request.id", "gitlab.repository.commitHash"})
    void testDiffComplete() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();
        var commit = gitLabRepo.commit(new Hash(props.get("gitlab.repository.commitHash")), true);
        var diff = commit.get().parentDiffs().get(0);
        assertTrue(diff.complete());

        var pr = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));
        var prDiff = pr.diff();
        assertFalse(prDiff.complete());
    }

    @Test
    @EnabledIfTestProperties({"gitlab.user", "gitlab.pat", "gitlab.uri", "gitlab.group",
            "gitlab.repository", "gitlab.merge.request.id"})
    void testLastCommitTIME() {
        var gitLabRepo = gitLabHost.repository(props.get("gitlab.repository")).orElseThrow();

        var pr = gitLabRepo.pullRequest(props.get("gitlab.merge.request.id"));
        var lastTouchedTime = pr.lastTouchedTime();
    }
}
