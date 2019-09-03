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
package org.openjdk.skara.census;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

class CensusTests {
    private Path createCensusDirectory() throws IOException {
        var censusDir = Files.createTempDirectory("census");

        var contributorsFile = censusDir.resolve("contributors.xml");
        var contributorsContent = List.of(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
            "<contributors>",
            "    <contributor username=\"user1\" full-name=\"User One\" />",
            "    <contributor username=\"user2\" full-name=\"User Two\" />",
            "    <contributor username=\"user3\" full-name=\"User Three\" />",
            "    <contributor username=\"user4\" full-name=\"User Four\" />",
            "</contributors>");
        Files.write(contributorsFile, contributorsContent);

        var groupsDir = censusDir.resolve("groups");
        Files.createDirectories(groupsDir);

        var testGroupFile = groupsDir.resolve("test.xml");
        var testGroupContent = List.of(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
            "<group name=\"group1\" full-name=\"Group One\">",
            "    <lead username=\"user3\" />",
            "    <member username=\"user1\" since=\"1\" />",
            "    <member username=\"user2\" since=\"1\" />",
            "</group>");
        Files.write(testGroupFile, testGroupContent);

        var projectDir = censusDir.resolve("projects");
        Files.createDirectories(projectDir);

        var testProjectFile = projectDir.resolve("test.xml");
        var testProjectContent = List.of(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
            "<project name=\"project1\" full-name=\"Project One\" sponsor=\"group1\">",
            "    <lead username=\"user1\" since=\"1\" />",
            "    <reviewer username=\"user2\" since=\"1\" />",
            "    <committer username=\"user3\" since=\"1\" />",
            "    <author username=\"user4\" since=\"1\" />",
            "</project>");
        Files.write(testProjectFile, testProjectContent);

        var namespacesDir = censusDir.resolve("namespaces");
        Files.createDirectories(namespacesDir);

        var namespaceFile = namespacesDir.resolve("github.xml");
        var namespaceContent = List.of(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
            "<namespace name=\"github.com\">",
            "    <user id=\"1234567\" census=\"user1\" />",
            "    <user id=\"2345678\" census=\"user2\" />",
            "</namespace>");
        Files.write(namespaceFile, namespaceContent);

        var versionFile = censusDir.resolve("version.xml");
        var versionContent = List.of(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>",
            "<version format=\"1\" timestamp=\"" + Instant.now().toString() + "\" />");
        Files.write(versionFile, versionContent);

        return censusDir;
    }

    @Test
    void testParseCensusDirectory() throws IOException {
        var censusDir = createCensusDirectory();
        var census = Census.parse(censusDir);

        var c1 = new Contributor("user1", "User One");
        var c2 = new Contributor("user2", "User Two");
        var c3 = new Contributor("user3", "User Three");
        var c4 = new Contributor("user4", "User Four");
        assertEquals(List.of(c1, c2, c3, c4), census.contributors());

        var g1 = new Group("group1", "Group One", c3, List.of(c1, c2, c3));
        assertEquals(List.of(g1), census.groups());

        var p1 = new Project("project1", "Project One", g1,
                             List.of(new Member(c1, 1)), List.of(new Member(c2, 1)), List.of(new Member(c3, 1)), List.of(new Member(c4, 1)));
        assertEquals(List.of(p1), census.projects());

        var namespace = census.namespace("github.com");
        assertEquals("github.com", namespace.name());
        assertEquals(c1, namespace.get("1234567"));
        assertEquals(c2, namespace.get("2345678"));
        assertEquals("1234567", namespace.get(c1));
        assertEquals("2345678", namespace.get(c2));

        assertEquals(1, census.version().format());
    }

    @Test
    void testParseSingleFile() throws IOException {
        var contents = List.of(
            "<census time=\"2019-01-22T13:51:55-08:00\">",
            "  <person name=\"user1\">",
            "    <full-name>User One</full-name>",
            "    <org>Org One</org>",
            "  </person>",
            "  <person name=\"user2\">",
            "    <full-name>User Two</full-name>",
            "    <org>Org Two</org>",
            "  </person>",
            "  <group name=\"group1\">",
            "    <full-name>Group One</full-name>",
            "    <person ref=\"user1\" role=\"lead\" />",
            "    <person ref=\"user2\" />",
            "  </group>",
            "  <project name=\"project1\" >",
            "    <full-name>Project One</full-name>",
            "    <sponsor ref=\"group1\" />",
            "    <person role=\"lead\" ref=\"user1\" />",
            "    <person role=\"committer\" ref=\"user2\" />",
            "  </project>",
            "</census>");
        var tmpFile = Files.createTempFile("census", ".xml");
        Files.write(tmpFile, contents);
        var census = Census.parse(tmpFile);

        var contributor1 = new Contributor("user1", "User One");
        var contributor2 = new Contributor("user2", "User Two");
        assertEquals(List.of(contributor1, contributor2), census.contributors());

        var group1 = new Group("group1", "Group One", contributor1, List.of(contributor1, contributor2));
        assertEquals(List.of(group1), census.groups());

        var expectedProject = new Project("project1", "Project One", group1,
                                          List.of(new Member(contributor1)),
                                          List.of(),
                                          List.of(new Member(contributor2)),
                                          List.of());
        assertEquals(List.of(expectedProject), census.projects());

        assertEquals(0, census.version().format());

        Files.delete(tmpFile);
    }
}
