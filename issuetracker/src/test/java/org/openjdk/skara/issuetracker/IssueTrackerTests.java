/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.issuetracker;

import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.HostCredentials;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssueTrackerTests {
    @Test
    void isMemberOfNegativeTests(TestInfo info) throws IOException {
        try (var credentials = new HostCredentials(info)) {
            var host = credentials.getIssueProject().issueTracker();
            var madeUpGroupIdThatCannotContainTestMember = "1234567890";
            assertFalse(host.isMemberOf(madeUpGroupIdThatCannotContainTestMember, host.currentUser()));
        }
    }

    @Test
    void simple(TestInfo info) throws IOException {
        try (var credentials = new HostCredentials(info)) {
            var project = credentials.getIssueProject();

            var userName = project.issueTracker().currentUser().userName();
            var user = project.issueTracker().user(userName);
            assertEquals(userName, user.get().userName());

            var issue = credentials.createIssue(project, "Test issue");
            issue.setTitle("Updated title");
            issue.setBody("This is now the body");
            var comment = issue.addComment("This is a comment");
            issue.updateComment(comment.id(), "Now it is updated");
            issue.addLabel("label");
            issue.addLabel("another");
            issue.removeLabel("label");
            issue.setAssignees(List.of(project.issueTracker().currentUser()));
            issue.setProperty("fixVersions", JSON.array().add("1.0"));
            issue.setProperty("fixVersions", JSON.array().add("1.0").add("2.0"));
            issue.setProperty("fixVersions", JSON.array().add("3.0"));
            var updated = project.issue(issue.id()).orElseThrow();
            assertEquals(List.of("another"), updated.labels());
            assertEquals(1, updated.properties().get("fixVersions").asArray().size());
            assertEquals("3.0", updated.properties().get("fixVersions").get(0).asString());
            assertEquals(List.of(project.issueTracker().currentUser()), updated.assignees());
            assertEquals(1, updated.comments().size());
            assertEquals("Updated title", updated.title());
            assertEquals("Now it is updated", updated.comments().get(0).body());

            issue.setState(Issue.State.RESOLVED);
        }
    }

    @Test
    void addLink(TestInfo info) throws IOException {
        try (var credentials = new HostCredentials(info)) {
            var project = credentials.getIssueProject();

            var userName = project.issueTracker().currentUser().userName();
            var user = project.issueTracker().user(userName);
            assertEquals(userName, user.get().userName());

            var issue = credentials.createIssue(project, "Test issue");
            issue.setBody("This is now the body");
            var link = Link.create(URI.create("http://www.example.com/abc"), "openjdk/skara/13")
                           .relationship("reviewed in")
                           .summary("Pull request")
                           .iconUrl(URI.create("https://bugs.openjdk.java.net/images/icons/icon-view.png"))
                           .iconTitle("Review")
                           .resolved(true)
                           .statusIconUrl(URI.create("https://bugs.openjdk.java.net/images/icons/icon-status-done-green.png"))
                           .statusIconTitle("Ready for integration")
                           .build();
            issue.addLink(link);

            var links = issue.links();
            assertEquals(1, links.size());
            assertEquals(link, links.get(0));

            issue.removeLink(link);
            links = issue.links();
            assertEquals(0, links.size());
        }
    }
}
