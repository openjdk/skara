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
package org.openjdk.skara.jbs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.HostCredentials;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IssueUtilTests {

    @Test
    void testFindClosestIssue(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo)) {
            var issueProject = credentials.getIssueProject();
            var issue = credentials.createIssue(issueProject, "Issue");
            issue.setProperty("issuetype", JSON.of("Bug"));
            var backport = credentials.createIssue(issueProject, "Backport");
            backport.setProperty("issuetype", JSON.of("Backport"));
            backport.setState(Issue.State.RESOLVED);
            issue.addLink(Link.create(backport, "backported by").build());
            var backportFoo = credentials.createIssue(issueProject, "Backport Foo");
            backportFoo.setProperty("issuetype", JSON.of("Backport"));
            issue.addLink(Link.create(backportFoo, "backported by").build());

            issue.setProperty("fixVersions", JSON.array().add("11-pool"));
            backport.setProperty("fixVersions", JSON.array().add("12-pool"));
            backportFoo.setProperty("fixVersions", JSON.array().add("12-pool-foo"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()).orElseThrow());
            assertEquals(backport, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2").orElseThrow()).orElseThrow());
            assertEquals(backportFoo, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2-foo").orElseThrow()).orElseThrow());
            assertEquals(Optional.empty(), IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("13.3").orElseThrow()));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1-foo").orElseThrow()).orElseThrow());

            issue.setProperty("fixVersions", JSON.array().add("8").add("11-pool"));
            backport.setProperty("fixVersions", JSON.array().add("8").add("12-pool"));
            backportFoo.setProperty("fixVersions", JSON.array().add("8").add("12-pool-foo"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()).orElseThrow());
            assertEquals(backport, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2").orElseThrow()).orElseThrow());
            assertEquals(backportFoo, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2-foo").orElseThrow()).orElseThrow());
            assertEquals(Optional.empty(), IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("13.3").orElseThrow()));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1-foo").orElseThrow()).orElseThrow());

            issue.setProperty("fixVersions", JSON.array().add("tbd"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()).orElseThrow());
            issue.setProperty("fixVersions", JSON.array().add("8").add("tbd"));
            assertEquals(Optional.empty(), IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()));

            issue.setProperty("fixVersions", JSON.array().add("12.2"));
            backport.setProperty("fixVersions", JSON.array().add("tbd"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2").orElseThrow()).orElseThrow());
            assertEquals(backport, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()).orElseThrow());
            issue.setProperty("fixVersions", JSON.array().add("8").add("12.2"));
            backport.setProperty("fixVersions", JSON.array().add("8").add("tbd"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2").orElseThrow()).orElseThrow());
            assertEquals(Optional.empty(), IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()));

            issue.setProperty("fixVersions", JSON.array().add("12.2"));
            backport.setProperty("fixVersions", JSON.array().add("11.1"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2").orElseThrow()).orElseThrow());
            assertEquals(backport, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()).orElseThrow());
            assertEquals(Optional.empty(), IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("13.3").orElseThrow()));
            issue.setProperty("fixVersions", JSON.array().add("8").add("12.2"));
            backport.setProperty("fixVersions", JSON.array().add("8").add("11.1"));
            assertEquals(issue, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("12.2").orElseThrow()).orElseThrow());
            assertEquals(backport, IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("11.1").orElseThrow()).orElseThrow());
            assertEquals(Optional.empty(), IssueUtil.findClosestIssue(List.of(issue, backport, backportFoo), JdkVersion.parse("13.3").orElseThrow()));
        }
    }
}
