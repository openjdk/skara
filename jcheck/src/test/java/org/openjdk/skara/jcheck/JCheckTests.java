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
package org.openjdk.skara.jcheck;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openjdk.skara.census.Census;
import org.openjdk.skara.test.TemporaryDirectory;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JCheckTests {
    static class CheckableRepository {
        public static Repository create(Path path, VCS vcs) throws IOException {
            var repo = Repository.init(path, vcs);

            Files.createDirectories(path.resolve(".jcheck"));
            var checkConf = path.resolve(".jcheck/conf");
            try (var output = new FileWriter(checkConf.toFile())) {
                output.append("[general]\n");
                output.append("project=test\n");
                output.append("\n");
                output.append("[checks]\n");
                output.append("error=reviewers,whitespace\n");
                output.append("\n");
                output.append("[census]\n");
                output.append("version=0\n");
                output.append("domain=openjdk.java.net\n");
                output.append("\n");
                output.append("[checks \"whitespace\"]\n");
                output.append("suffixes=.txt\n");
                output.append("\n");
                output.append("[checks \"reviewers\"]\n");
                output.append("minimum=1\n");
            }
            repo.add(checkConf);

            repo.commit("Initial commit\n\nReviewed-by: user2", "user3", "user3@openjdk.java.net");

            return repo;
        }
    }

    static class CensusCreator {
        static void populateCensusDirectory(Path censusDir) throws IOException {
            var contributorsFile = censusDir.resolve("contributors.xml");
            var contributorsContent = List.of(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
                    "<contributors>",
                    "    <contributor username=\"user1\" full-name=\"User Number 1\" />",
                    "    <contributor username=\"user2\" full-name=\"User Number 2\" />",
                    "    <contributor username=\"user3\" full-name=\"User Number 3\" />",
                    "    <contributor username=\"user4\" full-name=\"User Number 4\" />",
                    "</contributors>");
            Files.write(contributorsFile, contributorsContent);

            var groupsDir = censusDir.resolve("groups");
            Files.createDirectories(groupsDir);

            var testGroupFile = groupsDir.resolve("test.xml");
            var testGroupContent = List.of(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
                    "<group name=\"test\" full-name=\"TEST\">",
                    "    <lead username=\"user_4\" />",
                    "    <member username=\"user1\" since=\"0\" />",
                    "    <member username=\"user2\" since=\"0\" />",
                    "</group>");
            Files.write(testGroupFile, testGroupContent);

            var projectDir = censusDir.resolve("projects");
            Files.createDirectories(projectDir);

            var testProjectFile = projectDir.resolve("test.xml");
            var testProjectContent = List.of(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
                    "<project name=\"test\" full-name=\"TEST\" sponsor=\"test\">",
                    "    <lead username=\"user1\" since=\"0\" />",
                    "    <reviewer username=\"user2\" since=\"0\" />",
                    "    <committer username=\"user3\" since=\"0\" />",
                    "    <author username=\"user4\" since=\"0\" />",
                    "</project>");
            Files.write(testProjectFile, testProjectContent);

            var namespacesDir = censusDir.resolve("namespaces");
            Files.createDirectories(namespacesDir);

            var namespaceFile = namespacesDir.resolve("github.xml");
            var namespaceContent = List.of(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
                    "<namespace name=\"github.com\">",
                    "    <user id=\"1234567\" census=\"user1\" />",
                    "    <user id=\"2345678\" census=\"user2\" />",
                    "</namespace>");
            Files.write(namespaceFile, namespaceContent);

            var versionFile = censusDir.resolve("version.xml");
            var versionContent = List.of(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
                    "<version format=\"1\" timestamp=\"" + Instant.now().toString() + "\" />");
            Files.write(versionFile, versionContent);
        }
    }

    class TestVisitor implements IssueVisitor {
        private final Set<Issue> issues = new HashSet<>();

        @Override
        public void visit(TagIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(BranchIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(DuplicateIssuesIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(SelfReviewIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(TooFewReviewersIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(MergeMessageIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(HgTagCommitIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(CommitterIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(WhitespaceIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(MessageIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(MessageWhitespaceIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(IssuesIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(InvalidReviewersIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(ExecutableIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(SymlinkIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(AuthorNameIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(AuthorEmailIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(CommitterNameIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(CommitterEmailIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(BlacklistIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(BinaryIssue e) {
            issues.add(e);
        }

        @Override
        public void visit(ProblemListsIssue e) {
            issues.add(e);
        }

        Set<Issue> issues() {
            return issues;
        }

        Set<String> issueNames() {
            return issues.stream()
                         .map(issue -> issue.getClass().getName())
                         .collect(Collectors.toSet());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void checksForCommit(VCS vcs) throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var repoPath = dir.path().resolve("repo");
            var repo = CheckableRepository.create(repoPath, vcs);

            var readme = repoPath.resolve("README");
            Files.write(readme, List.of("Hello, readme!"));
            repo.add(readme);
            var first = repo.commit("Add README", "duke", "duke@openjdk.java.net");

            var censusPath = dir.path().resolve("census");
            Files.createDirectories(censusPath);
            CensusCreator.populateCensusDirectory(censusPath);
            var census = Census.parse(censusPath);

            var checks = JCheck.checksFor(repo, census, first);
            var checkNames = checks.stream()
                                   .map(Check::name)
                                   .collect(Collectors.toSet());
            assertEquals(Set.of("whitespace", "reviewers"), checkNames);
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void checkRemoval(VCS vcs) throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var repoPath = dir.path().resolve("repo");
            var repo = CheckableRepository.create(repoPath, vcs);

            var file = repoPath.resolve("file.txt");
            Files.write(file, List.of("Hello, file!"));
            repo.add(file);
            var first = repo.commit("Add file", "duke", "duke@openjdk.java.net");

            Files.delete(file);
            repo.remove(file);
            var second = repo.commit("Remove file", "duke", "duke@openjdk.java.net");

            var censusPath = dir.path().resolve("census");
            Files.createDirectories(censusPath);
            CensusCreator.populateCensusDirectory(censusPath);
            var census = Census.parse(censusPath);

            var visitor = new TestVisitor();
            try (var issues = JCheck.check(repo, census, CommitMessageParsers.v1, first.hex() + ".." + second.hex(), Map.of(), Set.of())) {
                for (var issue : issues) {
                    issue.accept(visitor);
                }
            }
            assertEquals(Set.of("org.openjdk.skara.jcheck.TooFewReviewersIssue"), visitor.issueNames());
        }
    }

    @ParameterizedTest
    @EnumSource(VCS.class)
    void checkOverridingConfiguration(VCS vcs) throws Exception {
        try (var dir = new TemporaryDirectory()) {
            var repoPath = dir.path().resolve("repo");
            var repo = CheckableRepository.create(repoPath, vcs);

            var initialCommit = repo.commits().asList().get(0);

            var jcheckConf = repoPath.resolve(".jcheck").resolve("conf");
            assertTrue(Files.exists(jcheckConf));
            Files.writeString(jcheckConf, "[checks \"reviewers\"]\nminimum = 0\n",
                              StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            repo.add(jcheckConf);
            var secondCommit = repo.commit("Do not require reviews", "user3", "user3@openjdk.java.net");

            var censusPath = dir.path().resolve("census");
            Files.createDirectories(censusPath);
            CensusCreator.populateCensusDirectory(censusPath);
            var census = Census.parse(censusPath);

            // Check the last commit without reviewers, should pass since .jcheck/conf was updated
            var range = initialCommit.hash().hex() + ".." + secondCommit.hex();
            var visitor = new TestVisitor();
            try (var issues = JCheck.check(repo, census, CommitMessageParsers.v1, range, Map.of(), Set.of())) {
                for (var issue : issues) {
                    issue.accept(visitor);
                }
            }
            assertEquals(Set.of(), visitor.issues());

            // Check the last commit without reviewers with the initial .jcheck/conf. Should fail
            // due to missing reviewers.
            try (var issues = JCheck.check(repo, census, CommitMessageParsers.v1, secondCommit, initialCommit.hash(), List.of())) {
                for (var issue : issues) {
                    issue.accept(visitor);
                }
            }
            assertEquals(Set.of("org.openjdk.skara.jcheck.TooFewReviewersIssue"), visitor.issueNames());
        }
    }
}
