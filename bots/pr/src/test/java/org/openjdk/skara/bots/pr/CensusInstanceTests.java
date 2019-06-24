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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class CensusInstanceTests {
    @Test
    void fetchOnce(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var author = credentials.getHostedRepository();

            var censusBuilder = credentials.getCensusBuilder()
                                           .addAuthor(author.host().getCurrentUserDetails().id());

            // Populate the projects repository
            var localRepo = CheckableRepository.init(tempFolder.path(), author.getRepositoryType());
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.push(masterHash, author.getUrl(), "master", true);

            // Make a change with a corresponding PR
            var editHash = CheckableRepository.appendAndCommit(localRepo);
            localRepo.push(editHash, author.getUrl(), "refs/heads/edit", true);
            var pr = credentials.createPullRequest(author, "master", "edit", "This is a pull request");

            // Check contents of the census instance
            var census = censusBuilder.build();
            var repoName = census.getUrl().getHost() + "/" + census.getName();
            var censusFolder = tempFolder.path().resolve("census");
            var censusRepoFolder = censusFolder.resolve(URLEncoder.encode(repoName, StandardCharsets.UTF_8));
            var censusInstance = CensusInstance.create(census, "master", censusFolder, pr);
            var fetchMarker = censusRepoFolder.resolve(".last_fetch");
            assertEquals("test", censusInstance.project().name());
            assertTrue(censusInstance.project().isAuthor("integrationauthor1", censusInstance.configuration().census().version()));
            assertFalse(censusInstance.project().isReviewer("integrationauthor1", censusInstance.configuration().census().version()));
            var fetchMarkerDate = Files.getLastModifiedTime(fetchMarker);

            // Updating the census should still give us cached results
            censusBuilder.addReviewer(author.host().getCurrentUserDetails().id());
            census = censusBuilder.build();
            censusInstance = CensusInstance.create(census, "master", censusFolder, pr);
            assertEquals("test", censusInstance.project().name());
            assertTrue(censusInstance.project().isAuthor("integrationauthor1", censusInstance.configuration().census().version()));
            assertFalse(censusInstance.project().isReviewer("integrationreviewer2", censusInstance.configuration().census().version()));
            assertEquals(fetchMarkerDate, Files.getLastModifiedTime(fetchMarker));

            // Force an update
            Files.delete(fetchMarker);
            censusInstance = CensusInstance.create(census, "master", censusFolder, pr);
            assertEquals("test", censusInstance.project().name());
            assertTrue(censusInstance.project().isAuthor("integrationauthor1", censusInstance.configuration().census().version()));
            assertTrue(censusInstance.project().isReviewer("integrationreviewer2", censusInstance.configuration().census().version()));
        }
    }
}
