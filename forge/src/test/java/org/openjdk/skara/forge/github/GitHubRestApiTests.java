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
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.ManualTestSettings;
import org.openjdk.skara.vcs.Diff;
import org.openjdk.skara.vcs.DiffComparator;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;

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
        var username = settings.getProperty("username");
        var token = settings.getProperty("token");
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
}
