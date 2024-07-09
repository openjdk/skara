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
import org.openjdk.skara.census.Census;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.util.logging.Logger;
import java.time.*;
import java.time.format.*;

public class CensusSyncUnifyBot implements Bot, WorkItem {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");;
    private final HostedRepository from;
    private final HostedRepository to;
    private final int version;
    private Hash last;

    CensusSyncUnifyBot(HostedRepository from, HostedRepository to, int version) {
        this.from = from;
        this.to = to;
        this.version = version;
        this.last = null;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CensusSyncUnifyBot o)) {
            return true;
        }
        return !o.to.equals(to);
    }

    @Override
    public String toString() {
        return "CensusSyncUnifyBot(" + from.name() + "->" + to.name() + "@" + version + ")";
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }

    @Override
    public Collection<WorkItem> run(Path scratch) {
        try {
            var fromDir = scratch.resolve("from.git");
            var fromRepo = Repository.materialize(fromDir, from.authenticatedUrl(), Branch.defaultFor(VCS.GIT).name());
            if (last != null && last.equals(fromRepo.head())) {
                // Nothing to do
                return List.of();
            }

            var census = Census.parse(fromDir);

            var toDir = scratch.resolve("to.git");
            var toRepo = Repository.materialize(toDir, to.authenticatedUrl(), Branch.defaultFor(VCS.GIT).name());

            var censusXML = toRepo.root().resolve("census.xml");
            if (!Files.exists(censusXML)) {
                Files.createFile(censusXML);
            }
            try (var file = new PrintWriter(Files.newBufferedWriter(censusXML))) {
                file.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
                var formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
                file.println("<census time=\"" + ZonedDateTime.now().format(formatter) + "\">");
                for (var contributor : census.contributors()) {
                    file.println("<person name=\"" + contributor.username() + "\">");
                    file.println("  <full-name>" + contributor.fullName().orElse("") + "</full-name>");
                    file.println("</person>");
                }
                for (var group : census.groups()) {
                    file.println("<group name=\"" + group.name() + "\">");
                    file.println("  <full-name>" + BotUtils.escape(group.fullName()) + "</full-name>");
                    file.println("  <person ref=\"" + group.lead().username() + "\" role=\"lead\" />");
                    for (var member : group.members()) {
                        if (!member.username().equals(group.lead().username())) {
                            file.println("  <person ref=\"" + member.username() + "\" />");
                        }
                    }
                    file.println("</group>");
                }
                for (var project : census.projects()) {
                    file.println("<project name=\"" + project.name() + "\">");
                    file.println("  <full-name>" + BotUtils.escape(project.fullName()) + "</full-name>");
                    file.println("  <sponsor ref=\"" + project.sponsor().name() + "\" />");

                    var roles = project.roles(version);
                    for (var role : roles.keySet()) {
                        for (var member : roles.get(role)) {
                            file.println("  <person role=\"" + role + "\" ref=\"" + member.username() + "\" />");
                        }
                    }

                    file.println("</project>");
                }
                for (var namespace : census.namespaces()) {
                    file.println("<namespace name=\"" + namespace.name() + "\">");
                    for (var entry : namespace.entries()) {
                        var id = entry.getKey();
                        var contributor = entry.getValue();
                        file.println("  <user id=\"" + id + "\" census=\"" + contributor.username() + "\" />");
                    }
                    file.println("</namespace>");
                }
                file.println("</census>");
            }
            toRepo.add(censusXML);
            var head = toRepo.commit("Updated census.xml", "duke", "duke@openjdk.org");
            toRepo.push(head, to.authenticatedUrl(), Branch.defaultFor(VCS.GIT).name(), false);
            last = fromRepo.head();
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
        return "unitfy";
    }
}
