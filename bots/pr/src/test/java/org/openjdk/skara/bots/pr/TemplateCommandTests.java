/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.json.JSON;
import org.junit.jupiter.api.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.file.Files;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.bots.pr.PullRequestAsserts.assertLastCommentContains;
import static org.openjdk.skara.bots.common.PullRequestConstants.PROGRESS_MARKER;

public class TemplateCommandTests {
    @Test
    void templateCommandOnlyAllowedByAuthor(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();
            var other = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addCommitter(other.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");
            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Try to issue the "/template" PR command, should not work
            var prAsOther = other.pullRequest(pr.id());
            prAsOther.addComment("/template append");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(prAsOther, "Only the pull request author can append a pull request template");
        }
    }

    @Test
    void mustSupplyArgument(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Try to issue the "/template" PR command without arguments, should not work
            var updatedPR = author.pullRequest(pr.id());
            updatedPR.addComment("/template");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(updatedPR, "Missing command 'append', usage: `/template append`");
        }
    }

    @Test
    void argumentMustBeAppend(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Try to issue the "/template" PR command with bogus argument, should not work
            var updatedPR = author.pullRequest(pr.id());
            updatedPR.addComment("/template foo");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(updatedPR, "Unknown argument 'foo', usage: `/template append`");
        }
    }

    @Test
    void missingPullRequestTemplate(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request");

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Try to issue the "/template append" PR command, should not work due to missing PR template
            var updatedPR = author.pullRequest(pr.id());
            updatedPR.addComment("/template append");
            TestBotRunner.runPeriodicItems(bot);
            assertLastCommentContains(updatedPR,
                "This repository does not have a pull request template"
            );
        }
    }

    @Test
    void gitHubTemplate(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var issues = credentials.getIssueProject();
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .issueProject(issues)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add pull request template
            var prTemplate = localRepo.root().resolve(".github/pull_request_template.md");
            Files.createDirectories(prTemplate.getParent());
            Files.writeString(prTemplate, "THIS IS A PR TEMPLATE");
            localRepo.add(prTemplate);
            var issue1 = credentials.createIssue(issues, "Add PR template");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var prTemplateHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(prTemplateHash, author.authenticatedUrl(), "refs/heads/master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request",
                    List.of("First line in body")
            );

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Add the "/template append" PR command
            pr.addComment("/template append");

            // Check status again
            TestBotRunner.runPeriodicItems(bot);

            // The PR template should have been added to the PR body
            var updatedPR = author.pullRequest(pr.id());
            assertLastCommentContains(updatedPR,
                "The pull request template has been appended to the pull request body");
            var expectedBodyPrefix =
                "First line in body\n" +
                "\n" +
                "THIS IS A PR TEMPLATE\n" +
                "\n" +
                PROGRESS_MARKER;
            assertTrue(updatedPR.body().startsWith(expectedBodyPrefix), updatedPR.body());
        }
    }

    @Test
    void gitLabTemplate(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var issues = credentials.getIssueProject();
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .issueProject(issues)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add pull request template
            var prTemplate =
                localRepo.root().resolve(".gitlab/merge_request_templates/default.md");
            Files.createDirectories(prTemplate.getParent());
            Files.writeString(prTemplate, "THIS IS A MERGE REQUEST TEMPLATE");
            localRepo.add(prTemplate);
            var issue1 = credentials.createIssue(issues, "Add PR template");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var prTemplateHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(prTemplateHash, author.authenticatedUrl(), "refs/heads/master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request",
                    List.of("First line in body")
            );

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Add the "/template append" PR command
            pr.addComment("/template append");

            // Check status again
            TestBotRunner.runPeriodicItems(bot);

            // The PR template should have been added to the PR body
            var updatedPR = author.pullRequest(pr.id());
            assertLastCommentContains(updatedPR,
                "The pull request template has been appended to the pull request body");
            var expectedBodyPrefix =
                "First line in body\n" +
                "\n" +
                "THIS IS A MERGE REQUEST TEMPLATE\n" +
                "\n" +
                PROGRESS_MARKER;
            assertTrue(updatedPR.body().startsWith(expectedBodyPrefix), updatedPR.body());
        }
    }

    @Test
    void forgeConfiguredTemplate(TestInfo testInfo) throws IOException {
        var forgeConf =
            JSON.object().put("prTemplate", JSON.array().add("").add("foo").add("bar"));
        try (var credentials = new HostCredentials(testInfo, forgeConf);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var issues = credentials.getIssueProject();
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .issueProject(issues)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request",
                    List.of("First line in body")
            );

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Add the "/template append" PR command
            pr.addComment("/template append");

            // Check status again
            TestBotRunner.runPeriodicItems(bot);

            // The PR template should have been added to the PR body
            var updatedPR = author.pullRequest(pr.id());
            assertLastCommentContains(updatedPR,
                "The pull request template has been appended to the pull request body");
            var expectedBodyPrefix =
                "First line in body\n" +
                "\n" +
                "foo\n" +
                "bar\n" +
                "\n" +
                PROGRESS_MARKER;
            assertTrue(updatedPR.body().startsWith(expectedBodyPrefix), updatedPR.body());
        }
    }

    @Test
    void templateCommandWorksInPRBody(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var issues = credentials.getIssueProject();
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .issueProject(issues)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add pull request template
            var prTemplate = localRepo.root().resolve(".github/pull_request_template.md");
            Files.createDirectories(prTemplate.getParent());
            Files.writeString(prTemplate, "THIS IS A PR TEMPLATE");
            localRepo.add(prTemplate);
            var issue1 = credentials.createIssue(issues, "Add PR template");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var prTemplateHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(prTemplateHash, author.authenticatedUrl(), "refs/heads/master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request",
                    List.of("First line in body", "/template append")
            );

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // The PR template should have been added to the PR body
            var updatedPR = author.pullRequest(pr.id());
            assertLastCommentContains(updatedPR,
                "The pull request template has been appended to the pull request body");
            var expectedBodyPrefix =
                "First line in body\n" +
                "/template append\n" +
                "\n" +
                "THIS IS A PR TEMPLATE\n" +
                "\n" +
                PROGRESS_MARKER;
            assertTrue(updatedPR.body().startsWith(expectedBodyPrefix), updatedPR.body());
        }
    }

    @Test
    void templateWithLeadingWhitespace(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var issues = credentials.getIssueProject();
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .issueProject(issues)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add pull request template
            var prTemplate = localRepo.root().resolve(".github/pull_request_template.md");
            Files.createDirectories(prTemplate.getParent());
            Files.writeString(prTemplate, "\n\n--------\nTEMPLATE WITH LEADING WHITESPACE");
            localRepo.add(prTemplate);
            var issue1 = credentials.createIssue(issues, "Add PR template");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var prTemplateHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(prTemplateHash, author.authenticatedUrl(), "refs/heads/master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request",
                    List.of("First line in body")
            );

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Add the "/template append" PR command
            pr.addComment("/template append");

            // Check status again
            TestBotRunner.runPeriodicItems(bot);

            // The PR template should have been added to the PR body
            var updatedPR = author.pullRequest(pr.id());
            assertLastCommentContains(updatedPR,
                "The pull request template has been appended to the pull request body");
            var expectedBodyPrefix =
                "First line in body\n" +
                "\n" +
                "--------\n" +
                "TEMPLATE WITH LEADING WHITESPACE\n" +
                "\n" +
                PROGRESS_MARKER;
            assertTrue(updatedPR.body().startsWith(expectedBodyPrefix), updatedPR.body());
        }
    }

    @Test
    void templateWithTrailingWhitespace(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tmp = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var integrator = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addCommitter(author.forge().currentUser().id())
                                           .addReviewer(integrator.forge().currentUser().id());

            var issues = credentials.getIssueProject();
            var bot = PullRequestBot.newBuilder()
                                    .repo(integrator)
                                    .issueProject(issues)
                                    .censusRepo(censusBuilder.build())
                                    .build();

            // Populate the projects repository
            var localRepoFolder = tmp.path().resolve("localrepo");
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            assertFalse(CheckableRepository.hasBeenEdited(localRepo));
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);

            // Add pull request template
            var prTemplate = localRepo.root().resolve(".github/pull_request_template.md");
            Files.createDirectories(prTemplate.getParent());
            Files.writeString(prTemplate, "--------\nTEMPLATE WITH TRAILING WHITESPACE\n\n\t\n");
            localRepo.add(prTemplate);
            var issue1 = credentials.createIssue(issues, "Add PR template");
            var issue1Number = issue1.id().split("-")[1];
            var originalMessage = issue1Number + ": An issue\n" +
                                  "\n" +
                                  "Reviewed-by: integrationreviewer2";
            var prTemplateHash = localRepo.commit(originalMessage, "integrationcommitter1", "integrationcommitter1@openjdk.org");
            localRepo.push(prTemplateHash, author.authenticatedUrl(), "refs/heads/master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "123: This is a pull request",
                    List.of("First line in body")
            );

            // Check status
            TestBotRunner.runPeriodicItems(bot);

            // Add the "/template append" PR command
            pr.addComment("/template append");

            // Check status again
            TestBotRunner.runPeriodicItems(bot);

            // The PR template should have been added to the PR body
            var updatedPR = author.pullRequest(pr.id());
            assertLastCommentContains(updatedPR,
                "The pull request template has been appended to the pull request body");
            var expectedBodyPrefix =
                "First line in body\n" +
                "\n" +
                "--------\n" +
                "TEMPLATE WITH TRAILING WHITESPACE\n" +
                "\n" +
                PROGRESS_MARKER;
            assertTrue(updatedPR.body().startsWith(expectedBodyPrefix), updatedPR.body());
        }
    }
}
