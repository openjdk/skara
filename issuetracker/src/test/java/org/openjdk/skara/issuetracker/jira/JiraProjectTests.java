/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.ManualTestSettings;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

/**
 * To be able to run the tests, you need to remove or comment out the @Disabled
 * annotation first.
 */
@Disabled("Manual")
public class JiraProjectTests {

    private URI jiraUri;
    private Properties settings;

    @BeforeEach
    void setupJira() throws IOException {
        HttpProxy.setup();
        settings = ManualTestSettings.loadManualTestSettings();
        jiraUri = URIBuilder.base(settings.getProperty("jira.uri")).build();
    }

    private IssueTracker authenticatedJiraHost() {
        var pat = settings.getProperty("jira.pat");
        return new JiraIssueTrackerFactory().createWithPat(jiraUri, "Bearer " + pat);
    }

    private IssueTracker jiraHost() {
        return new JiraIssueTrackerFactory().create(jiraUri, null, null);
    }

    @Test
    void testJepIssue() {
        var jiraHost = jiraHost();
        var jiraProject = jiraHost.project("JDK");

        // Test a closed JEP. Note: all the JEPs may be changed to state `Closed` in the end.
        var closedJepOpt = jiraProject.jepIssue("421");
        assertTrue(closedJepOpt.isPresent());
        var closedJep = closedJepOpt.get();
        assertEquals("Closed", closedJep.status());
        assertEquals("Delivered", closedJep.resolution().orElseThrow());
        assertEquals("JEP", closedJep.properties().get("issuetype").asString());
        assertEquals("421", closedJep.properties().get(JEP_NUMBER).asString());

        // Test a non-existing JEP (large JEP number).
        var nonExistingJepOpt = jiraProject.jepIssue("100000000000");
        assertTrue(nonExistingJepOpt.isEmpty());

        // Test the wrong JEP (number with alphabet).
        var wrongNumberJepOpt = jiraProject.jepIssue("JDK-123");
        assertTrue(wrongNumberJepOpt.isEmpty());
    }

    @Test
    void testClosingIssue() {
        var projectId = settings.getProperty("jira.project");
        var issueId = settings.getProperty("jira.issue");
        var jiraHost = authenticatedJiraHost();
        var jiraProject = jiraHost.project(projectId);
        var jiraIssue = jiraProject.issue(issueId).orElseThrow();
        assertNotEquals(Issue.State.CLOSED, jiraIssue.state());
        jiraIssue.setState(Issue.State.CLOSED);
        var jiraIssue2 = jiraProject.issue(issueId).orElseThrow();
        assertEquals(Issue.State.CLOSED, jiraIssue2.state());
    }

    @Test
    void testIssueEquals() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var project = settings.getProperty("jira.project");
        var issueId = settings.getProperty("jira.issue");
        var jiraHost = authenticatedJiraHost();
        var jiraProject = jiraHost.project(project);

        var issue = jiraProject.issue(issueId).orElseThrow();
        var issue2 = jiraProject.issue(issueId).orElseThrow();

        assertEquals(issue, issue2);
    }

    @Test
    void testUserActive() {
        var jiraHost = authenticatedJiraHost();
        var activeUserId = settings.getProperty("jira.user.active");
        var activeUser = jiraHost.user(activeUserId).orElseThrow();
        assertTrue(activeUser.active());

        var inactiveUserId = settings.getProperty("jira.user.inactive");
        var inactiveUser = jiraHost.user(inactiveUserId).orElseThrow();
        assertFalse(inactiveUser.active());
    }
}
