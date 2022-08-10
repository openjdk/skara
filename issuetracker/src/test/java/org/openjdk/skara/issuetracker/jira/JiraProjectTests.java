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
package org.openjdk.skara.issuetracker.jira;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.network.URIBuilder;

import java.io.IOException;
import org.openjdk.skara.test.ManualTestSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

/**
 * To be able to run the tests, you need to remove or comment out the @Disabled
 * annotation first.
 */
@Disabled("Manual")
public class JiraProjectTests {

    @Test
    void testJepIssue() throws IOException {
        var uri = URIBuilder.base("https://bugs.openjdk.org").build();
        var jiraHost = new JiraIssueTrackerFactory().create(uri, null, null);
        var jiraProject = jiraHost.project("JDK");

        // Test a closed JEP. Note: all the JEPs may be changed to state `Closed` in the end.
        var closedJepOpt = jiraProject.jepIssue("421");
        assertTrue(closedJepOpt.isPresent());
        var closedJep = closedJepOpt.get();
        assertEquals("Closed", closedJep.properties().get("status").get("name").asString());
        assertEquals("Delivered", closedJep.properties().get("resolution").get("name").asString());
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
    void test() throws IOException {
        var settings = ManualTestSettings.loadManualTestSettings();
        var jiraUri = settings.getProperty("jira-uri");
        var cookie = settings.getProperty("jira-cookie");
        var projectId = settings.getProperty("jira-project-id");
        var issueId = settings.getProperty("jira-issue-id");
        var uri = URIBuilder.base(jiraUri).build();
        var jiraHost = new JiraIssueTrackerFactory().create(uri, cookie);
        var jiraProject = jiraHost.project(projectId);
        var jiraIssueOpt = jiraProject.issue(issueId);
        var jiraIssue = jiraIssueOpt.get();
        assertEquals(Issue.State.RESOLVED, jiraIssue.state());
        jiraIssue.setState(Issue.State.CLOSED);
        var jiraIssueOpt2 = jiraProject.issue(issueId);
        assertEquals(Issue.State.CLOSED, jiraIssueOpt2.get().state());
    }
}
