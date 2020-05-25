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
package org.openjdk.skara.bots.notify;

import org.junit.jupiter.api.*;
import org.openjdk.skara.bots.notify.json.JsonUpdater;
import org.openjdk.skara.json.*;
import org.openjdk.skara.test.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openjdk.skara.bots.notify.UpdaterTests.*;

public class JsonUpdaterTests {
    private List<Path> findJsonFiles(Path folder, String partialName) throws IOException {
        return Files.walk(folder)
                    .filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> path.toString().contains(partialName))
                    .collect(Collectors.toList());
    }

    @Test
    void testJsonUpdaterBranch(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var tagStorage = createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var jsonFolder = tempFolder.path().resolve("json");
            Files.createDirectory(jsonFolder);
            var storageFolder = tempFolder.path().resolve("storage");

            var updater = new JsonUpdater(jsonFolder, "12", "team");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(List.of(), findJsonFiles(jsonFolder, ""));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "One more line", "12345678: Fixes");
            localRepo.push(editHash, repo.url(), "master");
            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(1, jsonFiles.size());
            var jsonData = Files.readString(jsonFiles.get(0), StandardCharsets.UTF_8);
            var json = JSON.parse(jsonData);
            assertEquals(1, json.asArray().size());
            assertEquals(repo.webUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
            assertEquals(List.of("12345678"), json.asArray().get(0).get("issue").asArray().stream()
                                                  .map(JSONValue::asString)
                                                  .collect(Collectors.toList()));
        }
    }

    @Test
    void testJsonUpdaterTag(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var localRepoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(localRepoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            var masterHash = localRepo.resolve("master").orElseThrow();
            localRepo.tag(masterHash, "jdk-12+1", "Added tag 1", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.url());

            var tagStorage = UpdaterTests.createTagStorage(repo);
            var branchStorage = createBranchStorage(repo);
            var prIssuesStorage = createPullRequestIssuesStorage(repo);
            var jsonFolder = tempFolder.path().resolve("json");
            Files.createDirectory(jsonFolder);
            var storageFolder =tempFolder.path().resolve("storage");

            var updater = new JsonUpdater(jsonFolder, "12", "team");
            var notifyBot = NotifyBot.newBuilder()
                                     .repository(repo)
                                     .storagePath(storageFolder)
                                     .branches(Pattern.compile("master"))
                                     .tagStorageBuilder(tagStorage)
                                     .branchStorageBuilder(branchStorage)
                                     .prIssuesStorageBuilder(prIssuesStorage)
                                     .updaters(List.of(updater))
                                     .build();

            TestBotRunner.runPeriodicItems(notifyBot);
            assertEquals(List.of(), findJsonFiles(jsonFolder, ""));

            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", "23456789: More fixes");
            localRepo.fetch(repo.url(), "history:history");
            localRepo.tag(editHash, "jdk-12+2", "Added tag 2", "Duke", "duke@openjdk.java.net");
            var editHash2 = CheckableRepository.appendAndCommit(localRepo, "Another line", "34567890: Even more fixes");
            localRepo.tag(editHash2, "jdk-12+4", "Added tag 3", "Duke", "duke@openjdk.java.net");
            localRepo.pushAll(repo.url());

            TestBotRunner.runPeriodicItems(notifyBot);
            var jsonFiles = findJsonFiles(jsonFolder, "");
            assertEquals(3, jsonFiles.size());

            for (var file : jsonFiles) {
                var jsonData = Files.readString(file, StandardCharsets.UTF_8);
                var json = JSON.parse(jsonData);

                if (json.asArray().get(0).contains("date")) {
                    assertEquals(2, json.asArray().size());
                    assertEquals(List.of("23456789"), json.asArray().get(0).get("issue").asArray().stream()
                                                          .map(JSONValue::asString)
                                                          .collect(Collectors.toList()));
                    assertEquals(repo.webUrl(editHash).toString(), json.asArray().get(0).get("url").asString());
                    assertEquals("team", json.asArray().get(0).get("build").asString());
                    assertEquals(List.of("34567890"), json.asArray().get(1).get("issue").asArray().stream()
                                                          .map(JSONValue::asString)
                                                          .collect(Collectors.toList()));
                    assertEquals(repo.webUrl(editHash2).toString(), json.asArray().get(1).get("url").asString());
                    assertEquals("team", json.asArray().get(1).get("build").asString());
                } else {
                    assertEquals(1, json.asArray().size());
                    if (json.asArray().get(0).get("build").asString().equals("b02")) {
                        assertEquals(List.of("23456789"), json.asArray().get(0).get("issue").asArray().stream()
                                                              .map(JSONValue::asString)
                                                              .collect(Collectors.toList()));
                    } else {
                        assertEquals("b04", json.asArray().get(0).get("build").asString());
                        assertEquals(List.of("34567890"), json.asArray().get(0).get("issue").asArray().stream()
                                                              .map(JSONValue::asString)
                                                              .collect(Collectors.toList()));
                    }
                }
            }
        }
    }}
