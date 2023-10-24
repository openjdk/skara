/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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

/**
 * Generates a valid census repository for use in tests. The possible structure
 * is limited compared to a real census. A default user with forge ID 0 is always
 * present as "lead" in the default group 'main' and default project 'test'.
 * <p>
 * Users can be added either directly to the default project with a given role, or
 * as just generic users without any project roles.
 */
public class CensusBuilder {
    private final String namespace;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.test.utils");;

    public record User(String forgeId, String name, String fullName) {
    }

    private final Map<String, User> users = new HashMap<>();
    private int userIndex = 1;

    private static class Project {
        private User lead;
        private final List<User> authors = new ArrayList<>();
        private final List<User> committers = new ArrayList<>();
        private final List<User> reviewers = new ArrayList<>();
    }
    private final Project defaultProject;
    private final Map<String, Project> projects = new HashMap<>();

    private static class Group {
        private User lead;
        private final List<User> members = new ArrayList<>();
    }
    private final Group defaultGroup;
    private final Map<String, Group> groups = new HashMap<>();


    /**
     * Creates a basic CensusBuilder with an implicit default project named
     * "test", a default group named "main" and a default user with lead role
     * in both of those.
     */
    public static CensusBuilder create(String namespace) {
        return new CensusBuilder(namespace);
    }

    private CensusBuilder(String namespace) {
        this.namespace = namespace;

        defaultProject = new Project();
        projects.put("test", defaultProject);

        defaultGroup = new Group();
        groups.put("main", defaultGroup);

        var lead = new User("0", "integrationlead", "Generated Lead");
        users.put(lead.forgeId, lead);
        defaultProject.lead = lead;
        defaultGroup.lead = lead;
    }

    /**
     * Creates new user and adds it to the default group and as author in the
     * default project.
     */
    public CensusBuilder addAuthor(String forgeId) {
        var user = createUser(forgeId, "integrationauthor", "Generated Author");
        defaultProject.authors.add(user);
        return this;
    }

    /**
     * Creates new user and adds it to the default group and as committer in the
     * default project.
     */
    public CensusBuilder addCommitter(String forgeId) {
        var user = createUser(forgeId, "integrationcommitter", "Generated Committer");
        defaultProject.committers.add(user);
        return this;
    }

    /**
     * Creates new user and adds it to the default group and as reviewer in the
     * default project.
     */
    public CensusBuilder addReviewer(String forgeId) {
        var user = createUser(forgeId, "integrationreviewer", "Generated Reviewer");
        defaultProject.reviewers.add(user);
        return this;
    }

    /**
     * Creates new user with custom names and adds it to the default group, but
     * not to any project.
     */
    public CensusBuilder addUser(String forgeId, String name, String fullName) {
        var user = new User(forgeId, name, fullName);
        userIndex++;
        users.put(forgeId, user);
        defaultGroup.members.add(user);
        return this;
    }

    private User createUser(String forgeId, String baseName, String baseFullName) {
        var user = new User(forgeId, baseName + userIndex, baseFullName + " " + userIndex);
        userIndex++;
        users.put(forgeId, user);
        defaultGroup.members.add(user);
        return user;
    }

    /**
     * Adds existing user to project as author
     */
    public CensusBuilder addAuthor(String forgeId, String project) {
        var user = users.get(forgeId);
        projects.get(project).authors.add(user);
        return this;
    }

    /**
     * Adds existing user to project as committer
     */
    public CensusBuilder addCommitter(String forgeId, String project) {
        var user = users.get(forgeId);
        projects.get(project).committers.add(user);
        return this;
    }

    /**
     * Adds existing user to project as reviewer
     */
    public CensusBuilder addReviewer(String forgeId, String project) {
        var user = users.get(forgeId);
        projects.get(project).reviewers.add(user);
        return this;
    }

    /**
     * Adds a new project with the existing user set as lead
     */
    public CensusBuilder addProject(String name, String leadForgeId) {
        Project project = new Project();
        project.lead = users.get(leadForgeId);
        projects.put(name, project);
        return this;
    }

    public User user(String forgeId) {
        return users.get(forgeId);
    }

    private void writeContributor(PrintWriter writer, User user) {
        writer.print("  <contributor username=\"");
        writer.print(user.name);
        writer.print("\" full-name=\"");
        writer.print(user.fullName);
        writer.print("\" />");
        writer.println();
    }

    private void writeMember(PrintWriter writer, User user, String role) {
        writer.print("  <");
        writer.print(role);
        writer.print(" username=\"");
        writer.print(user.name);
        writer.print("\" />");
        writer.println();
    }

    private void writeRole(PrintWriter writer, User user, String role) {
        writer.print("  <");
        writer.print(role);
        writer.print(" username=\"");
        writer.print(user.name);
        writer.print("\" since=\"0\" />");
        writer.println();
    }

    private void writeMapping(PrintWriter writer, User user) {
        writer.print("  <user id=\"");
        writer.print(user.forgeId);
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
            users.values().forEach(user -> writeContributor(writer, user));
            writer.println("</contributors>");
        }
    }

    private void generateGroup(Path folder) throws IOException {
        var groupFolder = folder.resolve("groups");
        Files.createDirectories(groupFolder);
        for (var groupEntry : groups.entrySet()) {
            var name = groupEntry.getKey();
            var group = groupEntry.getValue();
            try (var writer = new PrintWriter(new FileWriter(groupFolder.resolve(name + ".xml").toFile()))) {
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
                writer.println("<group name=\"" + name + "\" full-name=\"" + name + " group\">");
                writeMember(writer, group.lead, "lead");
                group.members.forEach(user -> writeMember(writer, user, "member"));
                writer.println("</group>");
            }
        }
    }

    private void generateProject(Path folder) throws IOException {
        var projectFolder = folder.resolve("projects");
        Files.createDirectories(projectFolder);
        for (var projectEntry : projects.entrySet()) {
            String name = projectEntry.getKey();
            var project = projectEntry.getValue();
            try (var writer = new PrintWriter(new FileWriter(projectFolder.resolve(name + ".xml").toFile()))) {
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
                writer.println("<project name=\"" + name + "\" full-name=\"" + name + " Project\" sponsor=\"main\">");
                writeRole(writer, project.lead, "lead");
                project.authors.forEach(user -> writeRole(writer, user, "author"));
                project.committers.forEach(user -> writeRole(writer, user, "committer"));
                project.reviewers.forEach(user -> writeRole(writer, user, "reviewer"));
                writer.println("</project>");
            }
        }
    }

    private void generateNamespace(Path folder) throws IOException {
        var namespaceFolder = folder.resolve("namespaces");
        Files.createDirectories(namespaceFolder);
        try (var writer = new PrintWriter(new FileWriter(namespaceFolder.resolve(namespace + ".xml").toFile()))) {
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            writer.println("<namespace name=\"" + namespace + "\">");
            users.values().forEach(user -> writeMapping(writer, user));
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
            var host = TestHost.createNew(List.of(HostUser.create(1, "cu", "Census User")));
            var repository = host.repository("census").orElseThrow();
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
            localRepository.push(hash, repository.authenticatedUrl(), Branch.defaultFor(VCS.GIT).name(), true);
            return repository;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
