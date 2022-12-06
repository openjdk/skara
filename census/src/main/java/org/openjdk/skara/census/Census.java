/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.census;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.net.URI;
import java.net.http.*;
import java.time.*;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.xml.XML;
import org.w3c.dom.Document;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.net.http.HttpResponse.BodyHandlers;

public class Census {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.census");
    private final Map<String, Contributor> contributors;
    private final Map<String, Group> groups;
    private final Map<String, Project> projects;
    private final Map<String, Namespace> namespaces;
    private final Version version;

    Census(Map<String, Contributor> contributors, Map<String, Group> groups, List<Project> projects, List<Namespace> namespaces, Version version) {
        this.contributors = contributors;
        this.groups = groups;
        this.projects = projects.stream().collect(toMap(Project::name, identity()));
        this.namespaces = namespaces.stream().collect(toMap(Namespace::name, identity()));
        this.version = version;
    }

    public static Census empty() {
        return new Census(Map.of(), Map.of(), List.of(), List.of(), null);
    }

    public List<Contributor> contributors() {
        return List.copyOf(contributors.values());
    }

    public List<Group> groups() {
        return List.copyOf(groups.values());
    }

    public List<Project> projects() {
        return List.copyOf(projects.values());
    }

    public List<Namespace> namespaces() {
        return List.copyOf(namespaces.values());
    }

    public Contributor contributor(String name) {
        return contributors.get(name);
    }

    public boolean isContributor(String name) {
        return contributors.containsKey(name);
    }

    public Group group(String name) {
        return groups.get(name);
    }

    public boolean isGroup(String name) {
        return groups.containsKey(name);
    }

    public Project project(String name) {
        return projects.get(name);
    }

    public boolean isProject(String name) {
        return projects.containsKey(name);
    }

    public Namespace namespace(String name) {
        return namespaces.get(name);
    }

    public boolean isNamespace(String name) {
        return namespaces.containsKey(name);
    }

    public Version version() {
        return version;
    }

    private static List<Path> xmlFiles(Path dir) throws IOException {
        var files = new ArrayList<Path>();

        if (Files.isDirectory(dir)) {
            try (var stream = Files.newDirectoryStream(dir, "*.xml")) {
                for (var xmlFile : stream) {
                    files.add(xmlFile);
                }
            }
        }

        return files;
    }

    private static Census parseDirectory(Path p) throws IOException {
        log.finer("Parsing directory " + p.toString());
        var contributorsFile = p.resolve("contributors.xml");
        var contributors = Files.exists(contributorsFile) ?
            Contributors.parse(contributorsFile) : new HashMap<String, Contributor>();

        var groups = new ArrayList<Group>();
        for (var file : xmlFiles(p.resolve("groups"))) {
            groups.add(Group.parse(file, contributors));
        }
        var groupMap = groups.stream().collect(toMap(Group::name, identity()));

        var projects = new ArrayList<Project>();
        for (var file : xmlFiles(p.resolve("projects"))) {
            projects.add(Project.parse(file, groupMap, contributors));
        }

        var namespaces = new ArrayList<Namespace>();
        for (var file : xmlFiles(p.resolve("namespaces"))) {
            namespaces.add(Namespace.parse(file, contributors));
        }

        var version = Version.parse(p.resolve("version.xml"));

        return new Census(contributors, groupMap, projects, namespaces, version);
    }

    private static Census parseDocument(Document document) throws IOException {
        var census = XML.child(document, "census");

        var date = ZonedDateTime.parse(XML.attribute(census, "time"));
        var timestamp = date.toInstant();
        var version = new Version(0, timestamp);

        var contributors = XML.children(census, "person")
                              .stream()
                              .map(e -> new Contributor(XML.attribute(e, "name"),
                                                        XML.child(e, "full-name").getTextContent()))
                              .collect(toMap(Contributor::username, identity()));

        var groups = new HashMap<String, Group>();
        for (var ele : XML.children(census, "group")) {
            var group = Group.parse(ele, contributors);
            groups.put(group.name(), group);
        }

        var projects = new ArrayList<Project>();
        for (var ele : XML.children(census, "project")) {
            projects.add(Project.parse(ele, groups, contributors));
        }

        var namespaces = new ArrayList<Namespace>();
        for (var ele : XML.children(census, "namespace")) {
            namespaces.add(Namespace.parse(ele, contributors));
        }

        return new Census(contributors, groups, projects, namespaces, version);
    }

    private static Census parseSingleFile(Path p) throws IOException {
        log.finer("Parsing single file " + p.toString());
        return parseDocument(XML.parse(p));
    }

    public static Census parse(List<String> lines) throws IOException {
        return parseDocument(XML.parse(lines));
    }

    public static Census parse(Path p) throws IOException {
        return Files.isDirectory(p) ? parseDirectory(p) : parseSingleFile(p);
    }

    private static Path download(URI uri) throws IOException, InterruptedException {
        log.finer("Downloading census from " + uri.toString());
        var tmpFile = Files.createTempFile("census", ".xml");
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .build();
        var response = client.send(request, BodyHandlers.ofFile(tmpFile));
        return tmpFile;
    }

    public static Census from(URI uri) throws IOException {
        try {
            return parse(download(uri));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    /**
     * Initializes a single Namespace directly from a hosted repository. This works
     * because the files needed to populate a single namespace are statically known.
     * A full Census needs to discover files by listing them, which makes
     * initialization from a remote repository inconvenient.
     *
     * @param repository HostedRepository to initialize from
     * @param ref The reference in the repository to get data from
     * @param name Name of namespace to initialize
     * @return Just the named Namespace from the Census hosted in the repository
     */
    public static Namespace parseNamespace(HostedRepository repository, String ref, String name) throws IOException {
        log.finer("Parsing namespace from repository " + repository.name());
        var contributorsData = repository.fileContents("contributors.xml", ref)
                .orElseThrow(() -> new RuntimeException("Could not find contributors.xml on ref " + ref + " in repo " + repository.name()));
        var contributors = Contributors.parse(contributorsData);
        var namespaceData = repository.fileContents("namespaces/" + name + ".xml", ref)
                .orElseThrow(() -> new RuntimeException("Could not find namespaces/" + name + ".xml on ref " + ref + " in repo " + repository.name()));
        return Namespace.parse(namespaceData, contributors);
    }
}
