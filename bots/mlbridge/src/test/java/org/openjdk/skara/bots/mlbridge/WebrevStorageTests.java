/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.HostedRepositoryPool;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebrevStorageTests {
    @Test
    void overwriteExisting(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Check that the web link wasn't verified yet
            assertFalse(webrevServer.isChecked());

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.addLabel("rfr");
            pr.setBody("This is now ready");

            var from = EmailAddress.from("test", "test@test.mail");
            var storage = new WebrevStorage(archive, "webrev", Path.of("test"),
                                            webrevServer.uri(), from);

            var prFolder = tempFolder.path().resolve("pr");
            var prRepo = Repository.materialize(prFolder, pr.repository().authenticatedUrl(), "edit");
            var jsonScratchFolder = tempFolder.path().resolve("scratch").resolve("json");
            var htmlScratchFolder = tempFolder.path().resolve("scratch").resolve("html");
            var seedPath = tempFolder.path().resolve("seed");
            var generator = storage.generator(pr, prRepo, jsonScratchFolder, htmlScratchFolder, new HostedRepositoryPool(seedPath));
            generator.generate(masterHash, editHash, "00", WebrevDescription.Type.FULL);

            // Check that the web link has been verified now and followed the redirect
            assertTrue(webrevServer.isChecked());
            assertTrue(webrevServer.isRedirectFollowed());

            // Update the local repository and check that the webrev has been generated
            Repository.materialize(repoFolder, archive.authenticatedUrl(), "webrev");
            assertTrue(Files.exists(repoFolder.resolve("test/" + pr.id() + "/00/index.html")));

            // Create it again - it will overwrite the previous one
            generator.generate(masterHash, editHash, "00", WebrevDescription.Type.FULL);
            Repository.materialize(repoFolder, archive.authenticatedUrl(), "webrev");
            assertTrue(Files.exists(repoFolder.resolve("test/" + pr.id() + "/00/index.html")));
        }
    }

    @Test
    void dropLarge(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Make a change with a corresponding PR
            CheckableRepository.appendAndCommit(localRepo);
            var large = "This line is about 30 bytes long\n".repeat(1000 * 100);
            Files.writeString(repoFolder.resolve("large.txt"), large);
            localRepo.add(repoFolder.resolve("large.txt"));
            var editHash = localRepo.commit("Add large file", "duke", "duke@openjdk.org");

            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.addLabel("rfr");
            pr.setBody("This is now ready");

            var from = EmailAddress.from("test", "test@test.mail");
            var storage = new WebrevStorage(archive, "webrev", Path.of("test"),
                                            webrevServer.uri(), from);

            var prFolder = tempFolder.path().resolve("pr");
            var prRepo = Repository.materialize(prFolder, pr.repository().authenticatedUrl(), "edit");
            var jsonScratchFolder = tempFolder.path().resolve("scratch").resolve("json");
            var htmlScratchFolder = tempFolder.path().resolve("scratch").resolve("html");
            var seedPath = tempFolder.path().resolve("seed");
            var generator = storage.generator(pr, prRepo, jsonScratchFolder, htmlScratchFolder, new HostedRepositoryPool(seedPath));
            generator.generate(masterHash, editHash, "00", WebrevDescription.Type.FULL);

            // Update the local repository and check that the webrev has been generated
            Repository.materialize(repoFolder, archive.authenticatedUrl(), "webrev");
            assertTrue(Files.exists(repoFolder.resolve("test/" + pr.id() + "/00/index.html")));
            assertTrue(Files.size(repoFolder.resolve("test/" + pr.id() + "/00/large.txt")) > 0);
            assertTrue(Files.size(repoFolder.resolve("test/" + pr.id() + "/00/large.txt")) < 1000);
        }
    }

    private static class InterceptingHash extends Hash {
        private final Path generatorPath;
        private final Path scratchPath;
        private final HostedRepository archive;
        private final String ref;

        private boolean hasIntercepted = false;

        public InterceptingHash(String hex, Path generatorPath, Path scratchPath, HostedRepository archive, String ref) {
            super(hex);

            this.generatorPath = generatorPath;
            this.scratchPath = scratchPath;
            this.archive = archive;
            this.ref = ref;
        }

        @Override
        public String hex() {
            if (Files.exists(generatorPath)) {
                if (hasIntercepted) {
                    return super.hex();
                }

                try {
                    var repo = Repository.materialize(scratchPath, archive.authenticatedUrl(), ref);
                    Files.writeString(repo.root().resolve("intercept.txt"), UUID.randomUUID().toString());
                    repo.add(repo.root().resolve("intercept.txt"));
                    var commit = repo.commit("Concurrent unrelated commit", "duke", "duke@openjdk.org");
                    repo.push(commit, archive.authenticatedUrl(), ref);
                    hasIntercepted = true;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                System.out.println("Pushing an unrelated commit to the archive repo");
            } else {
                hasIntercepted = false;
            }
            return super.hex();
        }
    }

    @Test
    void retryConcurrentPush(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory();
             var webrevServer = new TestWebrevServer()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.authenticatedUrl(), "master", true);
            localRepo.push(masterHash, archive.authenticatedUrl(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.authenticatedUrl(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.addLabel("rfr");
            pr.setBody("This is now ready");

            var from = EmailAddress.from("test", "test@test.mail");
            var storage = new WebrevStorage(archive, "webrev", Path.of("test"),
                                            webrevServer.uri(), from);

            var prFolder = tempFolder.path().resolve("pr");
            var prRepo = Repository.materialize(prFolder, pr.repository().authenticatedUrl(), "edit");
            var jsonScratchFolder = tempFolder.path().resolve("scratch").resolve("json");
            var htmlScratchFolder = tempFolder.path().resolve("scratch").resolve("html");
            var generatorProgressMarker = htmlScratchFolder.resolve("test/" + pr.id() + "/00/nanoduke.ico");
            var seedPath = tempFolder.path().resolve("seed");
            var generator = storage.generator(pr, prRepo, jsonScratchFolder, htmlScratchFolder, new HostedRepositoryPool(seedPath));

            // Commit something during generation
            var interceptFolder = tempFolder.path().resolve("intercept");
            var interceptEditHash = new InterceptingHash(editHash.hex(),
                                                         generatorProgressMarker,
                                                         interceptFolder, archive, "webrev");
            generator.generate(masterHash, interceptEditHash, "00", WebrevDescription.Type.FULL);

            // Update the local repository and check that the webrev has been generated
            var archiveRepo = Repository.materialize(repoFolder, archive.authenticatedUrl(), "webrev");
            assertTrue(Files.exists(repoFolder.resolve("test/" + pr.id() + "/00/index.html")));

            // The intercepting commit should be present in the history
            assertTrue(archiveRepo.commitMetadata().stream()
                                  .anyMatch(cm -> cm.message().get(0).equals("Concurrent unrelated commit")));
        }
    }
}
