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
package org.openjdk.skara.issuetracker.jira;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.TestProperties;
import org.openjdk.skara.test.EnabledIfTestProperties;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

class JiraIntegrationTests {
    private static TestProperties props;
    private static IssueTracker tracker;

    @BeforeAll
    static void beforeAll() {
        props = TestProperties.load();
        if (props.contains("jira.uri", "jira.pat")) {
            HttpProxy.setup();
            var uri = URIBuilder.base(props.get("jira.uri")).build();
            tracker = new JiraIssueTrackerFactory().createWithPat(uri, "Bearer " + props.get("jira.pat"));
        }
    }

    @Test
    @EnabledIfTestProperties({"jira.uri", "jira.pat"})
    void testJepIssue() {
        var project = tracker.project("JDK");

        // Test a closed JEP. Note: all the JEPs may be changed to state `Closed` in the end.
        var closedJepOpt = project.jepIssue("421");
        assertTrue(closedJepOpt.isPresent());
        var closedJep = closedJepOpt.get();
        assertEquals("Closed", closedJep.status());
        assertEquals("Delivered", closedJep.resolution().orElseThrow());
        assertEquals("JEP", closedJep.properties().get("issuetype").asString());
        assertEquals("421", closedJep.properties().get(JEP_NUMBER).asString());

        // Test a non-existing JEP (large JEP number).
        var nonExistingJepOpt = project.jepIssue("100000000000");
        assertTrue(nonExistingJepOpt.isEmpty());

        // Test the wrong JEP (number with alphabet).
        var wrongNumberJepOpt = project.jepIssue("JDK-123");
        assertTrue(wrongNumberJepOpt.isEmpty());
    }

    @Test
    @EnabledIfTestProperties({"jira.uri", "jira.pat", "jira.project", "jira.issue"})
    void testClosingIssue() {
        var project = tracker.project(props.get("jira.project"));
        var issueId = props.get("jira.issue");

        var issue = project.issue(issueId).orElseThrow();
        assertNotEquals(Issue.State.CLOSED, issue.state());
        issue.setState(Issue.State.CLOSED);

        var issue2 = project.issue(issueId).orElseThrow();
        assertEquals(Issue.State.CLOSED, issue2.state());
    }

    @Test
    @EnabledIfTestProperties({"jira.uri", "jira.pat", "jira.project", "jira.issue"})
    void testIssueEquals() throws IOException {
        var project = tracker.project(props.get("jira.project"));
        var issueId = props.get("jira.issue");

        var issue = project.issue(issueId).orElseThrow();
        var issue2 = project.issue(issueId).orElseThrow();

        assertEquals(issue, issue2);
    }

    @Test
    @EnabledIfTestProperties({"jira.uri", "jira.pat", "jira.user.active"})
    void testUserActive() {
        var activeUserId = props.get("jira.user.active");
        var activeUser = tracker.user(activeUserId).orElseThrow();
        assertTrue(activeUser.active());
    }

    @Test
    @EnabledIfTestProperties({"jira.uri", "jira.pat", "jira.user.inactive"})
    void testUserInactive() {
        var inactiveUserId = props.get("jira.user.inactive");
        var inactiveUser = tracker.user(inactiveUserId).orElseThrow();
        assertFalse(inactiveUser.active());
    }

    @Test
    @EnabledIfTestProperties({"jira.uri", "jira.pat", "jira.project", "jira.issue"})
    void testResolutionOfResolvedIssue() throws IOException {
        var project = tracker.project(props.get("jira.project"));
        var issueId = props.get("jira.issue");

        var issue = project.issue(issueId).orElseThrow();
        issue.setState(Issue.State.OPEN);
        issue.setState(Issue.State.RESOLVED);
        issue = project.issue(issueId).orElseThrow();
        assertTrue(issue.resolution().isPresent());
        assertEquals("Fixed", issue.resolution().get());
    }
}
