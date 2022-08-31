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
package org.openjdk.skara.issuetracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;

public class UpdatedIssuePollerTest {
    @Test
    void testGetUpdatedIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var issueProject = credentials.getIssueProject();

            var bugIssue = issueProject.createIssue("bug issue", List.of(), Map.of());
            bugIssue.setProperty("issuetype", JSON.of("Bug"));
            var enhancementIssue = issueProject.createIssue("enhancement issue", List.of(), Map.of());
            enhancementIssue.setProperty("issuetype", JSON.of("Enhancement"));
            var csrIssue = issueProject.createIssue("CSR issue", List.of(), Map.of());
            csrIssue.setProperty("issuetype", JSON.of("CSR"));
            var jepIssue = issueProject.createIssue("JEP issue", List.of(), Map.of());
            jepIssue.setProperty("issuetype", JSON.of("JEP"));

            // First time, the poller should get empty list.
            var poller = new UpdatedIssuePoller(issueProject);
            var list = poller.getUpdatedIssues(IssueProject::issues);
            assertEquals(0, list.size());

            // Update bug issue and csr issue.
            bugIssue.addLabel("test");
            csrIssue.addLabel("test");

            // The poller should get bug issue and csr issue.
            list = poller.getUpdatedIssues(IssueProject::issues);
            assertEquals(2, list.size());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(bugIssue.id())).count());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(csrIssue.id())).count());

            // Update bug issue, enhancement issue and jep issue.
            bugIssue.addLabel("test2");
            enhancementIssue.addLabel("test2");
            jepIssue.addLabel("test2");

            // The poller should get bug issue and jep issue.
            list = poller.getUpdatedIssues(IssueProject::issues);
            assertEquals(3, list.size());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(bugIssue.id())).count());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(enhancementIssue.id())).count());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(jepIssue.id())).count());

            // No issue updates.
            list = poller.getUpdatedIssues(IssueProject::issues);
            assertEquals(0, list.size());
        }
    }

    @Test
    void testGetUpdatedCsrIssues(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var issueProject = credentials.getIssueProject();

            var bugIssue = issueProject.createIssue("bug issue", List.of(), Map.of());
            bugIssue.setProperty("issuetype", JSON.of("Bug"));
            var enhancementIssue = issueProject.createIssue("enhancement issue", List.of(), Map.of());
            enhancementIssue.setProperty("issuetype", JSON.of("Enhancement"));
            var csrIssue1 = issueProject.createIssue("CSR issue 1", List.of(), Map.of());
            csrIssue1.setProperty("issuetype", JSON.of("CSR"));
            var csrIssue2 = issueProject.createIssue("CSR issue 2", List.of(), Map.of());
            csrIssue2.setProperty("issuetype", JSON.of("CSR"));
            var jepIssue = issueProject.createIssue("JEP issue", List.of(), Map.of());
            jepIssue.setProperty("issuetype", JSON.of("JEP"));

            // First time, the poller should get empty list.
            var poller = new UpdatedIssuePoller(issueProject);
            var list = poller.getUpdatedIssues(IssueProject::csrIssues);
            assertEquals(0, list.size());

            // Update bug issue and csr issue 1.
            bugIssue.addLabel("test");
            csrIssue1.addLabel("test");

            // The poller should only get csr issue 1.
            list = poller.getUpdatedIssues(IssueProject::csrIssues);
            assertEquals(1, list.size());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(csrIssue1.id())).count());

            // Update bug issue, enhancement issue and jep issue.
            bugIssue.addLabel("test2");
            enhancementIssue.addLabel("test2");
            jepIssue.addLabel("test2");

            // The poller should get no issue.
            list = poller.getUpdatedIssues(IssueProject::csrIssues);
            assertEquals(0, list.size());

            // Update csr issue 1 and csr issue 2
            csrIssue1.addLabel("test3");
            csrIssue2.addLabel("test3");

            // The poller should get csr issue 1 and csr issue 2.
            list = poller.getUpdatedIssues(IssueProject::csrIssues);
            assertEquals(2, list.size());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(csrIssue1.id())).count());
            assertEquals(1, list.stream().filter(issue -> issue.id().equals(csrIssue2.id())).count());

            // No issue updates.
            list = poller.getUpdatedIssues(IssueProject::csrIssues);
            assertEquals(0, list.size());
        }
    }
}
