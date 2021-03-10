/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.synclabel;

import org.junit.jupiter.api.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openjdk.skara.issuetracker.Issue.State.RESOLVED;

public class SyncLabelBotTests {
    private TestBotFactory testBotBuilder(IssueProject issueProject, Path storagePath) {
        return testBotBuilder(issueProject, storagePath, null, null);
    }

    private TestBotFactory testBotBuilder(IssueProject issueProject, Path storagePath, String inspect, String ignore) {
        var cfg = JSON.object().put("project", issueProject.name());
        if (inspect != null) {
            cfg.put("inspect", inspect);
        }
        if (ignore != null) {
            cfg.put("ignore", ignore);
        }
        return TestBotFactory.newBuilder()
                             .addIssueProject(issueProject.name(), issueProject)
                             .storagePath(storagePath)
                             .addConfiguration("issueprojects", JSON.array()
                                                                    .add(cfg))
                             .build();
    }

    @Test
    void testAddLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var syncLabelBot = testBotBuilder(issueProject, storageFolder).create("synclabel", JSON.object());

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("fixVersions", JSON.array().add(JSON.of("8u182")));
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setState(RESOLVED);
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u162")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue2.setState(RESOLVED);
            issue1.addLink(Link.create(issue2, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("10")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.setState(RESOLVED);
            issue1.addLink(Link.create(issue3, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue3.labels());

            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("11")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue4.setState(RESOLVED);
            issue1.addLink(Link.create(issue4, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());

            // Ensure it is stable
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());
        }
    }

    @Test
    void testRemoveLabel(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var syncLabelBot = testBotBuilder(issueProject, storageFolder).create("synclabel", JSON.object());

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("fixVersions", JSON.array().add(JSON.of("8u182")));
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setState(RESOLVED);
            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u162")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue2.setState(RESOLVED);
            issue1.addLink(Link.create(issue2, "backported by").build());
            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("10")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.setState(RESOLVED);
            issue1.addLink(Link.create(issue3, "backported by").build());
            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("11")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue4.setState(RESOLVED);
            issue1.addLink(Link.create(issue4, "backported by").build());

            // First correct them
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());

            // Intentionally mislabel
            issue2.addLabel("hgupdate-sync");

            // They should be restored
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());
        }
    }

    @Test
    void testManualIgnore(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var syncLabelBot = testBotBuilder(issueProject, storageFolder).create("synclabel", JSON.object());

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("fixVersions", JSON.array().add(JSON.of("8u182")));
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setState(RESOLVED);
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u162")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue2.setState(RESOLVED);
            issue1.addLink(Link.create(issue2, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("10")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.setState(RESOLVED);
            issue1.addLink(Link.create(issue3, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue3.labels());

            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("11")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue4.setState(RESOLVED);
            issue1.addLink(Link.create(issue4, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());

            // Now ignore one of them - it should cause another to change
            issue3.addLabel("hgupdate-sync-ignore");
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of("hgupdate-sync-ignore"), issue3.labels());
            assertEquals(List.of(), issue4.labels());

            // Rearrange it a bit more
            var issue5 = credentials.createIssue(issueProject, "Issue 5");
            issue5.setProperty("fixVersions", JSON.array().add(JSON.of("8u192")));
            issue5.setProperty("issuetype", JSON.of("Backport"));
            issue5.setState(RESOLVED);
            issue1.addLink(Link.create(issue5, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue5.labels());

            // Now ignore another
            issue2.addLabel("hgupdate-sync-ignore");
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());
            assertEquals(List.of("hgupdate-sync-ignore"), issue2.labels());
            assertEquals(List.of("hgupdate-sync-ignore"), issue3.labels());
            assertEquals(List.of(), issue4.labels());
            assertEquals(List.of("hgupdate-sync"), issue5.labels());

            // Now ignore the main issue as well
            issue1.addLabel("hgupdate-sync-ignore");

            // This should lead to issue 5 no longer being a sync issue
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync-ignore"), issue1.labels());
            assertEquals(List.of("hgupdate-sync-ignore"), issue2.labels());
            assertEquals(List.of("hgupdate-sync-ignore"), issue3.labels());
            assertEquals(List.of(), issue4.labels());
            assertEquals(List.of(), issue5.labels());
        }
    }

    @Test
    void testIgnore(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var syncLabelBot = testBotBuilder(issueProject, storageFolder).create("synclabel", JSON.object());

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("fixVersions", JSON.array().add(JSON.of("8u41")));
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setState(RESOLVED);
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u261")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue2.setState(RESOLVED);
            issue1.addLink(Link.create(issue2, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("8u251")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.setState(RESOLVED);
            issue1.addLink(Link.create(issue3, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue3.labels());

            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("emb-8u251")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue4.setState(RESOLVED);
            issue1.addLink(Link.create(issue4, "backported by").build());

            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());
            assertEquals(List.of("hgupdate-sync"), issue2.labels());
            assertEquals(List.of("hgupdate-sync"), issue3.labels());
            assertEquals(List.of(), issue4.labels());

            // Now try it with a configured ignore - issue 3 should lose its label
            var syncLabelBotWithIgnore = testBotBuilder(issueProject, storageFolder, null, "8u4\\d").create("synclabel", JSON.object());
            TestBotRunner.runPeriodicItems(syncLabelBotWithIgnore);
            assertEquals(List.of(), issue1.labels());
            assertEquals(List.of("hgupdate-sync"), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of(), issue4.labels());
        }
    }

    @Test
    void testInspect(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var syncLabelBot = testBotBuilder(issueProject, storageFolder).create("synclabel", JSON.object());

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("fixVersions", JSON.array().add(JSON.of("8u41")));
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setState(RESOLVED);
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u261")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue2.setState(RESOLVED);
            issue1.addLink(Link.create(issue2, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("8u251")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.setState(RESOLVED);
            issue1.addLink(Link.create(issue3, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of("hgupdate-sync"), issue3.labels());

            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("8u361")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue4.setState(RESOLVED);
            issue1.addLink(Link.create(issue4, "backported by").build());

            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());
            assertEquals(List.of("hgupdate-sync"), issue2.labels());
            assertEquals(List.of("hgupdate-sync"), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());

            // Now try it with a configured include - issue 2 will now lose its label
            var syncLabelBotWithIgnore = testBotBuilder(issueProject, storageFolder, "8u\\d6\\d", null).create("synclabel", JSON.object());
            TestBotRunner.runPeriodicItems(syncLabelBotWithIgnore);
            assertEquals(List.of(), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of("hgupdate-sync"), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());
        }
    }

    @Test
    void testAddLabelWithBuild(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var storageFolder = tempFolder.path().resolve("storage");
            var issueProject = credentials.getIssueProject();
            var syncLabelBot = testBotBuilder(issueProject, storageFolder).create("synclabel", JSON.object());

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("fixVersions", JSON.array().add(JSON.of("openjfx17")));
            issue1.setProperty("issuetype", JSON.of("Bug"));
            issue1.setState(RESOLVED);
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("fixVersions", JSON.array().add(JSON.of("8u271")));
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue2.setProperty("customfield_10006", JSON.of("b33"));
            issue2.setState(RESOLVED);
            issue1.addLink(Link.create(issue2, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("fixVersions", JSON.array().add(JSON.of("8u291")));
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.setState(RESOLVED);
            issue1.addLink(Link.create(issue3, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue3.labels());

            var issue4 = credentials.createIssue(issueProject, "Issue 4");
            issue4.setProperty("fixVersions", JSON.array().add(JSON.of("8u301")));
            issue4.setProperty("issuetype", JSON.of("Backport"));
            issue4.setState(RESOLVED);
            issue1.addLink(Link.create(issue4, "backported by").build());
            TestBotRunner.runPeriodicItems(syncLabelBot);
            assertEquals(List.of(), issue1.labels());
            assertEquals(List.of(), issue2.labels());
            assertEquals(List.of(), issue3.labels());
            assertEquals(List.of("hgupdate-sync"), issue4.labels());
        }
    }
}
