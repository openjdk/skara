/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.bots.censussync;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.bots.common.BotUtils;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.network.RestRequest;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.xml.XML;
import org.w3c.dom.Element;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;

public class CensusSyncSplitBot implements Bot, WorkItem {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final URI from;
    private final HostedRepository to;
    private final int version;
    private final RestRequest request;

    private String lastCensus = "";

    CensusSyncSplitBot(URI from, HostedRepository to, int version) {
        this.from = from;
        this.to = to;
        this.version = version;

        request = new RestRequest(from);
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CensusSyncSplitBot o)) {
            return true;
        }
        return !o.to.equals(to);
    }

    @Override
    public String toString() {
        return "CensusSyncSplitBot(" + from + "->" + to.name() + "@" + version + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    private static PrintWriter newPrintWriter(Path p) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(p));
    }

    private static List<Path> syncVersion(Element census, Path to) throws IOException {
        var date = ZonedDateTime.parse(XML.attribute(census, "time"));
        var timestamp = date.toInstant();
        var filename = to.resolve("version.xml");
        try (var file = newPrintWriter(filename)) {
            file.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            file.format("<version format=\"1\" timestamp=\"%s\" />%n", timestamp.toString());
        }
        return List.of(filename);
    }

    private static List<Path> syncContributors(Element census, Path to) throws IOException {
        var filename = to.resolve("contributors.xml");
        try (var file = newPrintWriter(filename)) {
            file.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
            file.println("<contributors>");
            for (var person : XML.children(census, "person")) {
                var username = XML.attribute(person, "name");
                var fullName = XML.child(person, "full-name").getTextContent();
                file.format("    <contributor username=\"%s\" full-name=\"%s\" />%n",
                            username, fullName);
            }
            file.println("</contributors>");
        }
        return List.of(filename);
    }

    private static List<Path> syncGroups(Element census, Path to) throws IOException {
        var dir = to.resolve("groups");
        var ret = new ArrayList<Path>();
        for (var group : XML.children(census, "group")) {
            Files.createDirectories(dir);

            String lead = null;
            var members = new ArrayList<String>();
            for (var person : XML.children(group, "person")) {
                if (XML.hasAttribute(person, "role")) {
                    var role = XML.attribute(person, "role");
                    if (!role.equals("lead")) {
                        throw new IOException("Unexpected role: " + role);
                    }
                    lead = XML.attribute(person, "ref");
                } else {
                    members.add(XML.attribute(person, "ref"));
                }
            }

            var name = XML.attribute(group, "name");
            var fullName = XML.child(group, "full-name").getTextContent();
            var filename = dir.resolve(name + ".xml");
            try (var file = newPrintWriter(filename)) {
                file.format("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>%n");
                file.format("<group name=\"%s\" full-name=\"%s\">%n", name, BotUtils.escape(fullName));
                file.format("    <lead username=\"%s\" />%n", lead);
                for (var member : members) {
                    file.format("    <member username=\"%s\" />%n", member);
                }

                file.format("</group>%n");
            }
            ret.add(filename);
        }
        return ret;
    }

    private static List<Path> syncProjects(Element census, Path to) throws IOException {
        var dir = to.resolve("projects");
        var ret = new ArrayList<Path>();
        for (var project : XML.children(census, "project")) {
            Files.createDirectories(dir);

            String lead = null;
            var committers = new ArrayList<String>();
            var reviewers = new ArrayList<String>();
            var authors = new ArrayList<String>();

            var name = XML.attribute(project, "name");

            for (var person : XML.children(project, "person")) {
                var role = XML.attribute(person, "role");
                var username = XML.attribute(person, "ref");
                switch (role) {
                    case "lead":
                        lead = username;
                        break;
                    case "reviewer":
                        reviewers.add(username);
                        break;
                    case "committer":
                        committers.add(username);
                        break;
                    case "author":
                        authors.add(username);
                        break;
                    default:
                        if (name.equals("openjfx") && (username.equals("dwookey") || username.equals("jpereda"))) {
                            authors.add(username);
                        } else {
                            throw new IOException("Unexpected role '" + role +
                                                          "' for user '" + username +
                                                          "' in project '" + name + "'");
                        }
                }
            }

            var fullName = XML.child(project, "full-name").getTextContent();
            var sponsor = XML.attribute(XML.child(project, "sponsor"), "ref");
            var filename = dir.resolve(name + ".xml");
            try (var file = newPrintWriter(filename)) {
                file.format("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>%n");
                file.format("<project name=\"%s\" full-name=\"%s\" sponsor=\"%s\">%n", name, BotUtils.escape(fullName), sponsor);
                file.format("    <lead username=\"%s\" since=\"0\" />%n", lead);

                for (var reviewer : reviewers) {
                    file.format("    <reviewer username=\"%s\" since=\"0\" />%n", reviewer);
                }
                for (var committer : committers) {
                    file.format("    <committer username=\"%s\" since=\"0\" />%n", committer);
                }
                for (var author : authors) {
                    file.format("    <author username=\"%s\" since=\"0\" />%n", author);
                }

                file.format("</project>%n");
            }
            ret.add(filename);
        }
        return ret;
    }

    private static List<Path> sync(String from, Path to) throws IOException {
        var document = XML.parse(from);
        var census = XML.child(document, "census");
        var ret = new ArrayList<Path>();

        ret.addAll(syncVersion(census, to));
        ret.addAll(syncContributors(census, to));
        ret.addAll(syncGroups(census, to));
        ret.addAll(syncProjects(census, to));

        return ret;
    }

    @Override
    public Collection<WorkItem> run(Path scratch) {
        try {
            var currentCensus = request.get().executeUnparsed();
            if (currentCensus.equals(lastCensus)) {
                log.fine("No census changes detected");
                return List.of();
            }

            var toDir = scratch.resolve("to.git");
            var toRepo = Repository.materialize(toDir, to.authenticatedUrl(), Branch.defaultFor(VCS.GIT).name());

            var updatedFiles = sync(currentCensus, toDir);
            if (!toRepo.isClean()) {
                toRepo.add(updatedFiles);
                var head = toRepo.commit("Updated census", "duke", "duke@openjdk.org");
                toRepo.push(head, to.authenticatedUrl(), Branch.defaultFor(VCS.GIT).name(), false);
            } else {
                log.info("New census data did not result in any changes");
            }

            lastCensus = currentCensus;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public String name() {
        return CensusSyncBotFactory.NAME;
    }

    @Override
    public String botName() {
        return name();
    }

    @Override
    public String workItemName() {
        return "split";
    }
}
