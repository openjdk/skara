/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.*;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.ManualTestSettings;
import org.openjdk.skara.vcs.Diff;
import org.openjdk.skara.vcs.DiffComparator;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * To be able to run the tests, you need to remove or comment out the @Disabled annotation first.
 */
@Disabled("Manual")
public class GitHubRestApiTests {
    private static final String GITHUB_REST_URI = "https://github.com";
    Forge githubHost;

    @BeforeEach
    void setupHost() throws IOException {
        HttpProxy.setup();
        var settings = ManualTestSettings.loadManualTestSettings();
        // Here use the OAuth2 token. To use a GitHub App, please see ManualForgeTests#gitHubLabels.
        var username = settings.getProperty("github.user");
        var token = settings.getProperty("github.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(GITHUB_REST_URI).build();
        githubHost = new GitHubForgeFactory().create(uri, credential, null);
    }

    @Test
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
    void testLastForcePushTime() {
        var githubRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(githubRepoOpt.isPresent());
        var githubRepo = githubRepoOpt.get();
        var pr = githubRepo.pullRequest("96");
        var lastForcePushTime = pr.lastForcePushTime();
        assertEquals("2022-05-29T10:32:43Z", lastForcePushTime.get().toString());
    }

    @Test
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
    void testLatestBody(){
        var testRepoOpt = githubHost.repository("openjdk/playground");
        assumeTrue(testRepoOpt.isPresent());
        var testRepo = testRepoOpt.get();
        var testPr = testRepo.pullRequest("99");

        String latestBody = testPr.latestBody();
        assertEquals("test", latestBody);
    }
}
