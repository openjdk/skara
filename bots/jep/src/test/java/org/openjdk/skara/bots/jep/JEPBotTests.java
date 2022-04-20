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
package org.openjdk.skara.bots.jep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.test.TestBotRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

public class JEPBotTests {
    private static final String jepMarker = "<!-- jep: '%s' '%s' '%s' -->"; // <!-- jep: 'JEP-ID' 'ISSUE-ID' 'ISSUE-TITLE' -->

    @Test
    void testJepIssueStatus(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var jepBot = new JEPBot(repo, issueProject);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), repo.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            List<Issue> issueLists = new ArrayList<>();
            var statusList = List.of("Draft", "Submitted", "Candidate", "Proposed to Target",
                    "Proposed to Drop", "Closed", "Targeted", "Integrated", "Completed");
            for (int i = 1; i <= 9; i++) {
                issueLists.add(issueProject.createIssue(statusList.get(i - 1) + " jep", List.of("Jep body"), Map.of("issuetype", JSON.of("JEP"),
                        "status", JSON.object().put("name", statusList.get(i - 1)), JEP_NUMBER, JSON.of(String.valueOf(i)))));
            }
            issueLists.add(issueProject.createIssue("The jep issue", List.of("Jep body"),
                    Map.of("issuetype", JSON.of("JEP"), "status", JSON.object().put("name", "Closed"),
                            "resolution", JSON.object().put("name", "Delivered"), JEP_NUMBER, JSON.of("10"))));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label at first
            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));

            // Test draft/submitted/candidate/proposedToTarget/proposedToDrop/closedWithoutDelivered JEPs
            for (int i = 1; i <= 6; i++) {
                pr.addComment(String.format(jepMarker, i, issueLists.get(i - 1).id(), issueLists.get(i - 1).title()));
                pr.removeLabel(JEPBot.JEP_LABEL);
                TestBotRunner.runPeriodicItems(jepBot);
                assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));
            }

            // PR should have the `jep` label
            TestBotRunner.runPeriodicItems(jepBot);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));
            // Remove the `jep` label for the following test
            pr.removeLabel(JEPBot.JEP_LABEL);

            // Test targeted/integrated/completed/closedWithDelivered JEPs
            for (int i = 7; i <= 10; i++) {
                pr.addComment(String.format(jepMarker, i, issueLists.get(i - 1).id(), issueLists.get(i - 1).title()));
                pr.addLabel(JEPBot.JEP_LABEL);
                TestBotRunner.runPeriodicItems(jepBot);
                assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));
            }
        }
    }

    @Test
    void testJepCommentNotExist(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var jepBot = new JEPBot(repo, issueProject);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), repo.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            issueProject.createIssue("Demo jep", List.of("Jep body"), Map.of("issuetype", JSON.of("JEP"),
                        "status", JSON.object().put("name", "Targeted"), JEP_NUMBER, JSON.of("1")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label at first
            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));

            // Add the `jep` label and don't add the jep comment
            pr.addLabel(JEPBot.JEP_LABEL);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));

            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));
        }
    }

    @Test
    void testJepUnneeded(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var jepBot = new JEPBot(repo, issueProject);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), repo.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            issueProject.createIssue("Demo jep", List.of("Jep body"), Map.of("issuetype", JSON.of("JEP"),
                    "status", JSON.object().put("name", "Targeted"), JEP_NUMBER, JSON.of("1")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label at first
            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));

            // Add the `jep` label and add the jep unneeded comment
            pr.addComment(String.format(jepMarker, "unneeded", "unneeded", "unneeded"));
            pr.addLabel(JEPBot.JEP_LABEL);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));

            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));
        }
    }

    @Test
    void testIssueNotExist(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var jepBot = new JEPBot(repo, issueProject);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), repo.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            issueProject.createIssue("Demo jep", List.of("Jep body"), Map.of("issuetype", JSON.of("JEP"),
                    "status", JSON.object().put("name", "Targeted"), JEP_NUMBER, JSON.of("1")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label at first
            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));

            // Add the `jep` label and add the non-existing jep comment
            pr.addComment(String.format(jepMarker, "100", "TEST-100", "Demo jep"));
            pr.addLabel(JEPBot.JEP_LABEL);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));

            TestBotRunner.runPeriodicItems(jepBot);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));
        }
    }

    @Test
    void testErrorIssueType(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var issueProject = credentials.getIssueProject();
            var jepBot = new JEPBot(repo, issueProject);

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), repo.repositoryType(),
                    Path.of("appendable.txt"), Set.of("issues"), null);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, repo.url(), "master", true);

            var mainIssue = issueProject.createIssue("The main issue", List.of("main"), Map.of("issuetype", JSON.of("Bug")));
            issueProject.createIssue("Demo jep", List.of("Jep body"), Map.of("issuetype", JSON.of("Enhancement"),
                    "status", JSON.object().put("name", "Targeted"), JEP_NUMBER, JSON.of("1")));

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, repo.url(), "edit", true);
            var pr = credentials.createPullRequest(repo, "master", "edit", mainIssue.id() + ": " + mainIssue.title());

            // PR should not have the `jep` label at first
            TestBotRunner.runPeriodicItems(jepBot);
            assertFalse(pr.labelNames().contains(JEPBot.JEP_LABEL));

            // Add the `jep` label and add the wrong type issue comment
            pr.addComment(String.format(jepMarker, "1", "TEST-2", "Demo jep"));
            pr.addLabel(JEPBot.JEP_LABEL);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));

            TestBotRunner.runPeriodicItems(jepBot);
            assertTrue(pr.labelNames().contains(JEPBot.JEP_LABEL));
        }
    }
}
