/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.notify.issue;

import org.junit.jupiter.api.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.HostCredentials;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BackportsTests {
    @Test
    void mainIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("issuetype", JSON.of("Bug"));

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("issuetype", JSON.of("Backport"));
            issue1.addLink(Link.create(issue2, "backported by").build());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue3.addLink(Link.create(issue1, "backport of").build());

            assertEquals(issue1, Backports.findMainIssue(issue1).orElseThrow());
            assertEquals(issue1, Backports.findMainIssue(issue2).orElseThrow());
            assertEquals(issue1, Backports.findMainIssue(issue3).orElseThrow());
        }
    }

    @Test
    void noMainIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("issuetype", JSON.of("Bug"));

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("issuetype", JSON.of("Backport"));

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("issuetype", JSON.of("Backport"));
            issue2.addLink(Link.create(issue3, "backported by").build());

            assertEquals(issue1, Backports.findMainIssue(issue1).orElseThrow());
            assertEquals(Optional.empty(), Backports.findMainIssue(issue2));
            assertEquals(Optional.empty(), Backports.findMainIssue(issue3));
        }
    }

    @Test
    void nonBackportLink(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();

            var issue1 = credentials.createIssue(issueProject, "Issue 1");
            issue1.setProperty("issuetype", JSON.of("Bug"));

            var issue2 = credentials.createIssue(issueProject, "Issue 2");
            issue2.setProperty("issuetype", JSON.of("Bug"));
            issue1.addLink(Link.create(issue2, "duplicated by").build());

            var issue3 = credentials.createIssue(issueProject, "Issue 3");
            issue3.setProperty("issuetype", JSON.of("CSR"));
            issue1.addLink(Link.create(issue3, "CSRed by").build());

            assertEquals(issue1, Backports.findMainIssue(issue1).orElseThrow());
            assertEquals(issue2, Backports.findMainIssue(issue2).orElseThrow());
            assertEquals(Optional.empty(), Backports.findMainIssue(issue3));
        }
    }

    @Test
    void findMainVersion(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var issue = credentials.createIssue(issueProject, "Issue");

            issue.setProperty("fixVersions", JSON.array().add("tbd"));
            assertEquals(Optional.empty(), Backports.mainFixVersion(issue));

            issue.setProperty("fixVersions", JSON.array().add("tbd_minor"));
            assertEquals(Optional.empty(), Backports.mainFixVersion(issue));

            issue.setProperty("fixVersions", JSON.array().add("unknown"));
            assertEquals(Optional.empty(), Backports.mainFixVersion(issue));

            issue.setProperty("fixVersions", JSON.array().add("11.3"));
            assertEquals(List.of("11", "3"), Backports.mainFixVersion(issue).orElseThrow().components());

            issue.setProperty("fixVersions", JSON.array().add("unknown").add("11.3"));
            assertEquals(List.of("11", "3"), Backports.mainFixVersion(issue).orElseThrow().components());

            issue.setProperty("fixVersions", JSON.array().add("11.3").add("unknown"));
            assertEquals(List.of("11", "3"), Backports.mainFixVersion(issue).orElseThrow().components());

            issue.setProperty("fixVersions", JSON.array().add("11.3").add("12.1"));
            assertEquals(Optional.empty(), Backports.mainFixVersion(issue));

            issue.setProperty("fixVersions", JSON.array().add("12.1").add("11.3"));
            assertEquals(Optional.empty(), Backports.mainFixVersion(issue));
        }
    }

    @Test
    void findIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var issue = credentials.createIssue(issueProject, "Issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var backport = credentials.createIssue(issueProject, "Backport");
            backport.setProperty("issuetype", JSON.of("Backport"));
            issue.addLink(Link.create(backport, "backported by").build());

            issue.setProperty("fixVersions", JSON.array().add("11-pool"));
            backport.setProperty("fixVersions", JSON.array().add("12-pool"));
            assertEquals(issue, Backports.findIssue(issue, JdkVersion.parse("11.1")).orElseThrow());
            assertEquals(backport, Backports.findIssue(issue, JdkVersion.parse("12.2")).orElseThrow());
            assertEquals(Optional.empty(), Backports.findIssue(issue, JdkVersion.parse("13.3")));

            issue.setProperty("fixVersions", JSON.array().add("tbd"));
            assertEquals(issue, Backports.findIssue(issue, JdkVersion.parse("11.1")).orElseThrow());

            issue.setProperty("fixVersions", JSON.array().add("12.2"));
            backport.setProperty("fixVersions", JSON.array().add("tbd"));
            assertEquals(issue, Backports.findIssue(issue, JdkVersion.parse("12.2")).orElseThrow());
            assertEquals(backport, Backports.findIssue(issue, JdkVersion.parse("11.1")).orElseThrow());

            issue.setProperty("fixVersions", JSON.array().add("12.2"));
            backport.setProperty("fixVersions", JSON.array().add("11.1"));
            assertEquals(issue, Backports.findIssue(issue, JdkVersion.parse("12.2")).orElseThrow());
            assertEquals(backport, Backports.findIssue(issue, JdkVersion.parse("11.1")).orElseThrow());
            assertEquals(Optional.empty(), Backports.findIssue(issue, JdkVersion.parse("13.3")));
        }
    }

    private static class BackportManager {
        private final HostCredentials credentials;
        private final IssueProject issueProject;
        private final List<Issue> issues;

        private void setVersion(Issue issue, String version) {
            var resolvedInBuild = "";
            if (version.contains("/")) {
                resolvedInBuild = version.split("/", 2)[1];
                version = version.split("/", 2)[0];
            }
            issue.setProperty("fixVersions", JSON.array().add(version));
            if (!resolvedInBuild.isEmpty()) {
                issue.setProperty("customfield_10006", JSON.object().put("value", resolvedInBuild));
            }
        }

        BackportManager(HostCredentials credentials, String initialVersion) {
            this.credentials = credentials;
            issueProject = credentials.getIssueProject();
            issues = new ArrayList<>();

            issues.add(credentials.createIssue(issueProject, "Main issue"));
            issues.get(0).setProperty("issuetype", JSON.of("Bug"));
            setVersion(issues.get(0), initialVersion);
        }

        void addBackports(String... versions) {
            for (int backportIndex = 0; backportIndex < versions.length; ++backportIndex) {
                var issue = credentials.createIssue(issueProject, "Backport issue " + backportIndex);
                issue.setProperty("issuetype", JSON.of("Backport"));
                setVersion(issue, versions[backportIndex]);
                issues.get(0).addLink(Link.create(issue, "backported by").build());
                issues.add(issue);
            }
        }

        void assertLabeled(String... labeledVersions) {
            Backports.labelReleaseStreamDuplicates(issues.get(0), "hgupdater-sync");

            var labels = new HashSet<>(Arrays.asList(labeledVersions));
            var labeledIssues = new HashSet<String>();
            for (var issue : issues) {
                var version = issue.properties().get("fixVersions").get(0).asString();
                if (issue.labels().contains("hgupdater-sync")) {
                    labeledIssues.add(version);
                }
            }
            assertEquals(labels, labeledIssues);
        }
    }

    @Test
    void labelFeatureReleaseStream(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "15");
            backports.assertLabeled();

            backports.addBackports("14", "16");
            backports.assertLabeled("15", "16");
        }
    }

    @Test
    void labelOpenJfxFeatureReleaseStream(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "openjfx15");
            backports.assertLabeled();

            backports.addBackports("openjfx14", "openjfx16");
            backports.assertLabeled();
        }
    }

    @Test
    void labelUpdateReleaseStream(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "14");
            backports.assertLabeled();

            backports.addBackports("14.0.1", "14.0.2");
            backports.assertLabeled("14.0.1", "14.0.2");

            backports.addBackports("15", "15.0.1", "15.0.2");
            backports.assertLabeled("14.0.1", "14.0.2", "15.0.1", "15.0.2");
        }
    }

    @Test
    void labelOpenJdkUpdateReleaseStream(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "11");
            backports.assertLabeled();

            backports.addBackports("11.0.1", "11.0.2");
            backports.assertLabeled("11.0.1", "11.0.2");

            backports.addBackports("11.0.3", "11.0.3-oracle");
            backports.assertLabeled("11.0.1", "11.0.2", "11.0.3", "11.0.3-oracle");
        }
    }

    @Test
    void labelBprStream8(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "8u251");
            backports.assertLabeled();

            backports.addBackports("8u241/b31");
            backports.assertLabeled();
        }
    }

    @Test
    void labelBprStream11(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "11");
            backports.assertLabeled();

            backports.addBackports("11.0.7.0.3-oracle");
            backports.assertLabeled();

            backports.addBackports("11.0.8.0.1-oracle", "12.0.3.0.1-oracle");
            backports.assertLabeled("11.0.8.0.1-oracle");
        }
    }

    @Test
    void labelTest8229219(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "13/b33");
            backports.assertLabeled();

            backports.addBackports("14/b10");
            backports.assertLabeled("14");

            backports.addBackports("13.0.1/b06", "13.0.2/b01");
            backports.assertLabeled("14", "13.0.1", "13.0.2");
        }
    }

    @Test
    void labelTest8244004(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "8u271/master");
            backports.assertLabeled();

            backports.addBackports("8u251/b34");
            backports.assertLabeled();

            backports.addBackports("8u260/master", "8u261/b06");
            backports.assertLabeled("8u261", "8u271");
        }
    }

    @Test
    void labelTest8077707(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "9/b78");
            backports.assertLabeled();

            backports.addBackports("emb-9/team");
            backports.assertLabeled();

            backports.addBackports("openjdk8u242/team", "openjdk8u232/master");
            backports.assertLabeled();

            backports.addBackports("8u261/b04", "8u251/b01", "8u241/b31", "8u231/b34");
            backports.assertLabeled("8u261", "8u241");

            backports.addBackports("emb-8u251/team", "7u261/b01");
            backports.assertLabeled("8u261", "8u241");
        }
    }

    @Test
    void labelTest8239803(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "openjfx15");
            backports.assertLabeled();

            backports.addBackports("8u261/b01", "8u251/b31", "8u241/b33");
            backports.assertLabeled("8u251");
        }
    }

    @Test
    void labelTest7092821(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "12/b24");
            backports.assertLabeled();

            backports.addBackports("13/team", "11.0.8-oracle/b01", "11.0.7/b02");
            backports.assertLabeled("13");

            backports.addBackports("8u261/b01", "8u251/b33", "8u241/b61");
            backports.assertLabeled("13");
        }
    }

    @Test
    void labelTest8222913(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var backports = new BackportManager(credentials, "13");
            backports.assertLabeled();

            backports.addBackports("11.0.6-oracle");

            backports.addBackports("11.0.5.0.1-oracle", "11.0.5-oracle", "11.0.5");
            backports.assertLabeled("11.0.6-oracle");

            backports.addBackports("11.0.4.0.1-oracle", "11.0.4-oracle", "11.0.4");
            backports.assertLabeled("11.0.6-oracle", "11.0.5.0.1-oracle", "11.0.5-oracle", "11.0.5");

            backports.addBackports("11.0.3.0.1-oracle");
            backports.assertLabeled("11.0.4.0.1-oracle", "11.0.6-oracle", "11.0.5.0.1-oracle", "11.0.5-oracle", "11.0.5");
        }
    }
}
