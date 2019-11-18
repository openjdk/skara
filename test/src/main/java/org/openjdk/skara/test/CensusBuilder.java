/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.test;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

public class CensusBuilder {
    private final String namespace;
    private final Logger log;

    private static class User {
        final String platformId;
        final String name;
        final String fullName;
        final String role;

        User(String platformId, String name, String fullName, String role) {
            this.platformId = platformId;
            this.name = name;
            this.fullName = fullName;
            this.role = role;
        }
    }

    private User lead;
    private List<User> authors = new ArrayList<>();
    private List<User> committers = new ArrayList<>();
    private List<User> reviewers = new ArrayList<>();
    private int userIndex;

    static CensusBuilder create(String namespace) {
        return new CensusBuilder(namespace);
    }

    private CensusBuilder(String namespace) {
        this.namespace = namespace;
        userIndex = 1;

        log = Logger.getLogger("org.openjdk.skara.test.utils");
        lead = new User("0", "integrationlead", "Generated Lead", "lead");
    }

    public CensusBuilder addAuthor(String id) {
        authors.add(new User(id,
                             "integrationauthor" + userIndex,
                             "Generated Author " + userIndex,
                             "author"));
        userIndex++;
        return this;
    }

    public CensusBuilder addCommitter(String id) {
        committers.add(new User(id,
                                "integrationcommitter" + userIndex,
                                "Generated Committer " + userIndex,
                                "committer"));
        userIndex++;
        return this;
    }

    public CensusBuilder addReviewer(String id) {
        reviewers.add(new User(id,
                               "integrationreviewer" + userIndex,
                               "Generated Reviewer " + userIndex,
                               "reviewer"));
        userIndex++;
        return this;
    }

    private void writeContributor(PrintWriter writer, User user) {
        writer.print("  <contributor username=\"");
        writer.print(user.name);
        writer.print("\" full-name=\"");
        writer.print(user.fullName);
        writer.print("\" />");
        writer.println();
    }

    private void writeMember(PrintWriter writer, User user) {
        writer.print("  <");
        if (user.role.equals("lead")) {
            writer.print("lead");
        } else {
            writer.print("member");
        }
        writer.print(" username=\"");
        writer.print(user.name);
        writer.print("\" />");
        writer.println();
    }

    private void writeRole(PrintWriter writer, User user) {
        writer.print("  <");
        writer.print(user.role);
        writer.print(" username=\"");
        writer.print(user.name);
        writer.print("\" since=\"0\" />");
        writer.println();
    }

    private void writeMapping(PrintWriter writer, User user) {
        writer.print("  <user id=\"");
        writer.print(user.platformId);
        writer.print("\" census=\"");
        writer.print(user.name);
        writer.print("\" />");
        writer.println();
    }

    private void generateContributors(Path folder) throws IOException {
        Files.createDirectories(folder);
        try (var writer = new PrintWriter(new FileWriter(folder.resolve("contributors.xml").toFile()))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            writer.println("<contributors>");

            writeContributor(writer, lead);
            authors.forEach(user -> writeContributor(writer, user));
            committers.forEach(user -> writeContributor(writer, user));
            reviewers.forEach(user -> writeContributor(writer, user));

            writer.println("</contributors>");
        }
    }

    private void generateGroup(Path folder) throws IOException {
        var groupFolder = folder.resolve("groups");
        Files.createDirectories(groupFolder);
        try (var writer = new PrintWriter(new FileWriter(groupFolder.resolve("main.xml").toFile()))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            writer.println("<group name=\"main\" full-name=\"Main project\">");

            writeMember(writer, lead);
            authors.forEach(user -> writeMember(writer, user));
            committers.forEach(user -> writeMember(writer, user));
            reviewers.forEach(user -> writeMember(writer, user));

            writer.println("</group>");
        }
    }

    private void generateProject(Path folder) throws IOException {
        var projectFolder = folder.resolve("projects");
        Files.createDirectories(projectFolder);
        try (var writer = new PrintWriter(new FileWriter(projectFolder.resolve("test.xml").toFile()))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            writer.println("<project name=\"test\" full-name=\"Test Project\" sponsor=\"main\">");

            writeRole(writer, lead);
            authors.forEach(user -> writeRole(writer, user));
            committers.forEach(user -> writeRole(writer, user));
            reviewers.forEach(user -> writeRole(writer, user));

            writer.println("</project>");
        }
    }

    private void generateNamespace(Path folder) throws IOException {
        var namespaceFolder = folder.resolve("namespaces");
        Files.createDirectories(namespaceFolder);
        try (var writer = new PrintWriter(new FileWriter(namespaceFolder.resolve(namespace + ".xml").toFile()))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            writer.println("<namespace name=\"" + namespace + "\">");

            writeMapping(writer, lead);
            authors.forEach(user -> writeMapping(writer, user));
            committers.forEach(user -> writeMapping(writer, user));
            reviewers.forEach(user -> writeMapping(writer, user));

            writer.println("</namespace>");
        }
    }

    private void generateVersion(Path folder) throws IOException {
        Files.createDirectories(folder);
        try (var writer = new PrintWriter(new FileWriter(folder.resolve("version.xml").toFile()))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            writer.println("<version format=\"1\" timestamp=\"2018-11-21T20:49:40Z\" />");
        }
    }

    public HostedRepository build() {
        try {
            var host = TestHost.createNew(List.of(new HostUser(1, "cu", "Census User")));
            var repository = host.repository("census").get();
            var folder = Files.createTempDirectory("censusbuilder");
            var localRepository = Repository.init(folder, VCS.GIT);

            log.fine("Generating census XML files in " + folder);
            generateGroup(folder);
            generateProject(folder);
            generateContributors(folder);
            generateNamespace(folder);
            generateVersion(folder);

            localRepository.add(folder);
            var hash = localRepository.commit("Generated census", "Census User", "cu@test.test");
            localRepository.push(hash, repository.url(), "master", true);
            return repository;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
