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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.test.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LabelerTests {
    @Test
    void simple(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var reviewer = credentials.getHostedRepository();

            var labelConfiguration = LabelConfiguration.newBuilder()
                                                       .addMatchers("test1", List.of(Pattern.compile("a.txt")))
                                                       .addMatchers("test2", List.of(Pattern.compile("b.txt")))
                                                       .build();
            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.forge().currentUser().id())
                                           .addReviewer(reviewer.forge().currentUser().id());
            var labelBot = PullRequestBot.newBuilder()
                                         .repo(author)
                                         .censusRepo(censusBuilder.build())
                                         .labelConfiguration(labelConfiguration)
                                         .build();

            // Populate the projects repository
            var localRepoFolder = tempFolder.path();
            var localRepo = CheckableRepository.init(localRepoFolder, author.repositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check the status - only the rfr label should be set
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr"), new HashSet<>(pr.labels()));

            var fileA = localRepoFolder.resolve("a.txt");
            Files.writeString(fileA, "Hello");
            localRepo.add(fileA);
            var hashA = localRepo.commit("test1", "test", "test@test");
            localRepo.push(hashA, author.url(), "edit");

            // Make sure that the push registered
            var lastHeadHash = pr.headHash();
            var refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Check the status - there should now be a test1 label
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test1"), new HashSet<>(pr.labels()));

            var fileB = localRepoFolder.resolve("b.txt");
            Files.writeString(fileB, "Hello");
            localRepo.add(fileB);
            var hashB = localRepo.commit("test2", "test", "test@test");
            localRepo.push(hashB, author.url(), "edit");

            // Make sure that the push registered
            lastHeadHash = pr.headHash();
            refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Check the status - there should now be a test2 label
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test1", "test2"), new HashSet<>(pr.labels()));

            localRepo.remove(fileA);
            var hashNoA = localRepo.commit("test2", "test", "test@test");
            localRepo.push(hashNoA, author.url(), "edit");

            // Make sure that the push registered
            lastHeadHash = pr.headHash();
            refreshCount = 0;
            do {
                pr = author.pullRequest(pr.id());
                if (refreshCount++ > 100) {
                    fail("The PR did not update after the new push");
                }
            } while (pr.headHash().equals(lastHeadHash));

            // Check the status - the test1 label should be gone
            TestBotRunner.runPeriodicItems(labelBot);
            assertEquals(Set.of("rfr", "test2"), new HashSet<>(pr.labels()));
        }
    }
}
