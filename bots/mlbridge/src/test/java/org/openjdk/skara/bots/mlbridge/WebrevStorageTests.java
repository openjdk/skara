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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.*;
import org.openjdk.skara.vcs.Repository;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebrevStorageTests {
    @Test
    void overwriteExisting(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();
            var archive = credentials.getHostedRepository();

            // Populate the projects repository
            var reviewFile = Path.of("reviewfile.txt");
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, author.repositoryType(), reviewFile);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.url(), "master", true);
            localRepo.push(masterHash, archive.url(), "webrev", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.url(), "edit", true);
            var pr = credentials.createPullRequest(archive, "master", "edit", "This is a pull request");
            pr.addLabel("rfr");
            pr.setBody("This is now ready");

            var from = EmailAddress.from("test", "test@test.mail");
            var storage = new WebrevStorage(archive, "webrev", Path.of("test"),
                                            URIBuilder.base("http://www.test.test/").build(), from);

            var prFolder = tempFolder.path().resolve("pr");
            var prInstance = new PullRequestInstance(prFolder, pr, URIBuilder.base("http://issues.test/browse/").build(), "TEST");
            var scratchFolder = tempFolder.path().resolve("scratch");
            storage.createAndArchive(prInstance, scratchFolder, masterHash, editHash, "00");

            // Update the local repository and check that the webrev has been generated
            Repository.materialize(repoFolder, archive.url(), "webrev");
            assertTrue(Files.exists(repoFolder.resolve("test/" + pr.id() + "/webrev.00/index.html")));

            // Create it again - it will overwrite the previous one
            storage.createAndArchive(prInstance, scratchFolder, masterHash, editHash, "00");
            Repository.materialize(repoFolder, archive.url(), "webrev");
            assertTrue(Files.exists(repoFolder.resolve("test/" + pr.id() + "/webrev.00/index.html")));
        }
    }
}
