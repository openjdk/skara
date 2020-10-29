/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.checkout;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.test.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.vcs.Tag;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutBotTests {
    private static void populate(Repository r) throws IOException {
        var readme = r.root().resolve("README");
        Files.write(readme, List.of("Hello, readme!"));

        r.add(readme);
        r.commit("Add README", "duke", "duke@openjdk.java.net");

        Files.write(readme, List.of("Another line"), WRITE, APPEND);
        r.add(readme);
        r.commit("Modify README", "duke", "duke@openjdk.java.net");

        Files.write(readme, List.of("A final line"), WRITE, APPEND);
        r.add(readme);
        r.commit("Final README", "duke", "duke@openjdk.java.net");
    }

    @Test
    void simpleConversion(TestInfo testInfo) throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));
            var marksLocalDir = tmp.path().resolve("marks.git");
            Files.createDirectories(marksLocalDir);
            var marksLocalRepo = Repository.init(marksLocalDir, VCS.GIT);
            marksLocalRepo.config("receive", "denyCurrentBranch", "ignore");
            var marksHostedRepo = new TestHostedRepository(host, "marks", marksLocalRepo);

            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("scratch");
            var marksAuthor = new Author("duke", "duke@openjdk.org");
            var marksStorage = MarkStorage.create(marksHostedRepo, marksAuthor, "test");

            var hgDir = tmp.path().resolve("hg");

            var gitLocalDir = tmp.path().resolve("from.git");
            Files.createDirectories(gitLocalDir);
            var gitLocalRepo = Repository.init(gitLocalDir, VCS.GIT);
            populate(gitLocalRepo);
            var gitHostedRepo = new TestHostedRepository(host, "from", gitLocalRepo);

            var bot = new CheckoutBot(gitHostedRepo, gitLocalRepo.defaultBranch(), hgDir, storage, marksStorage);
            var runner = new TestBotRunner();
            runner.runPeriodicItems(bot);

            var hgRepo = Repository.get(hgDir).orElseThrow();
            assertEquals(3, hgRepo.commitMetadata().size());
        }
    }

    @Test
    void update(TestInfo testInfo) throws IOException {
        try (var tmp = new TemporaryDirectory()) {
            var host = TestHost.createNew(List.of(HostUser.create(0, "duke", "J. Duke")));
            var marksLocalDir = tmp.path().resolve("marks.git");
            Files.createDirectories(marksLocalDir);
            var marksLocalRepo = Repository.init(marksLocalDir, VCS.GIT);
            marksLocalRepo.config("receive", "denyCurrentBranch", "ignore");
            var marksHostedRepo = new TestHostedRepository(host, "marks", marksLocalRepo);

            var storage = tmp.path().resolve("storage");
            var scratch = tmp.path().resolve("scratch");
            var marksAuthor = new Author("duke", "duke@openjdk.org");
            var marksStorage = MarkStorage.create(marksHostedRepo, marksAuthor, "test");
            var runner = new TestBotRunner();

            var hgDir = tmp.path().resolve("hg");

            var gitLocalDir = tmp.path().resolve("from.git");
            Files.createDirectories(gitLocalDir);
            var gitLocalRepo = Repository.init(gitLocalDir, VCS.GIT);
            populate(gitLocalRepo);
            var gitHostedRepo = new TestHostedRepository(host, "from", gitLocalRepo);

            var bot = new CheckoutBot(gitHostedRepo, gitLocalRepo.defaultBranch(), hgDir, storage, marksStorage);
            runner.runPeriodicItems(bot);

            var hgRepo = Repository.get(hgDir).orElseThrow();
            assertEquals(3, hgRepo.commitMetadata().size());
            assertEquals(3, gitLocalRepo.commitMetadata().size());

            var readme = gitLocalRepo.root().resolve("README");
            Files.write(readme, List.of("An updated line"), WRITE, APPEND);
            gitLocalRepo.add(readme);
            gitLocalRepo.commit("Updated Final README", "duke", "duke@openjdk.java.net");

            runner.runPeriodicItems(bot);
            assertEquals(4, hgRepo.commitMetadata().size());
        }
    }
}
