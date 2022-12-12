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
package org.openjdk.skara.forge.gitlab;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.ManualTestSettings;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.Hash;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * To be able to run the tests, you need to remove or comment out the @Disabled annotation first.
 */
@Disabled("Manual")
public class GitLabRestApiTest {

    @Test
    void testReviews() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(settings.getProperty("gitlab.merge.request.id"));

        var reviewList = gitLabMergeRequest.reviews();
        var actualHash = reviewList.get(0).hash().orElse(new Hash(""));
        assertEquals(settings.getProperty("gitlab.review.hash"), actualHash.hex());
    }

    @Test
    void testFilesUrl() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(settings.getProperty("gitlab.merge.request.id"));

        // Test a version hash
        var versionUrl = gitLabMergeRequest.filesUrl(new Hash(settings.getProperty("gitlab.version.hash")));
        assertEquals(settings.getProperty("gitlab.version.url"), versionUrl.toString());

        // Test a non-version hash
        var nonVersionUrl = gitLabMergeRequest.filesUrl(new Hash(settings.getProperty("gitlab.nonversion.hash")));
        assertEquals(settings.getProperty("gitlab.nonversion.url"), nonVersionUrl.toString());
    }

    @Test
    void testLabels() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(settings.getProperty("gitlab.merge.request.id"));

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
    void testOversizeComment() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var gitLabMergeRequest = gitLabRepo.pullRequest(settings.getProperty("gitlab.merge.request.id"));

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
    void fileContentsNonExisting() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var branch = new Branch(settings.getProperty("gitlab.repository.branch"));

        var fileName = "testfile-that-does-not-exist.txt";
        var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
        assertTrue(returnedContents.isEmpty());
    }

    @Test
    void writeFileContents() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var username = settings.getProperty("gitlab.user");
        var token = settings.getProperty("gitlab.pat");
        var credential = new Credential(username, token);
        var uri = URIBuilder.base(settings.getProperty("gitlab.uri")).build();
        var gitLabHost = new GitLabHost("gitlab", uri, false, credential, Set.of());
        var gitLabRepo = gitLabHost.repository(settings.getProperty("gitlab.repository")).orElseThrow();
        var branch = new Branch(settings.getProperty("gitlab.repository.branch"));

        var fileName = "testfile.txt";

        // Create new file
        {
            var fileContent = "File content";
            gitLabRepo.writeFileContents(fileContent, fileName, branch,
                    "First commit message", "Duke", "duke@openjdk.org");
            var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }

        // Update file
        {
            var fileContent = "New file content";
            gitLabRepo.writeFileContents(fileContent, fileName, branch,
                    "Second commit message", "Duke", "duke@openjdk.org");
            var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }

        // Make the file huge
        {
            var fileContent = "a".repeat(1024 * 1024 * 10);
            gitLabRepo.writeFileContents(fileContent, fileName, branch,
                    "Third commit message", "Duke", "duke@openjdk.org");
            var returnedContents = gitLabRepo.fileContents(fileName, branch.name());
            assertEquals(fileContent, returnedContents.orElseThrow());
        }
    }
}
