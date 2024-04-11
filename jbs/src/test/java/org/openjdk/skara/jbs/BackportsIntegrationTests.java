/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jbs;

import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.issuetracker.IssueTrackerFactory;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.test.TestProperties;
import org.openjdk.skara.test.EnabledIfTestProperties;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


class BackportsIntegrationTests {
    private static TestProperties props;
    private static IssueTracker tracker;

    @BeforeAll
    static void beforeAll() {
        props = TestProperties.load();
        if (props.contains("jbs.uri", "jbs.pat")) {
            var factory = IssueTrackerFactory.getIssueTrackerFactories().stream().filter(f -> f.name().equals("jira")).findFirst();
            if (factory.isEmpty()) {
                throw new IllegalStateException("'jbs.uri' and 'jbs.pat' has been configured but could not find IssueTrackerFactory for 'jira'");
            }
            HttpProxy.setup();
            var uri = URIBuilder.base(props.get("jbs.uri")).build();
            var credential = new Credential("", "Bearer " + props.get("jbs.pat"));
            tracker = factory.get().create(uri, credential, new JSONObject());
        }
    }

    @Test
    @EnabledIfTestProperties({"jbs.uri", "jbs.pat"})
    void testBackportCreation() {
        var project = tracker.project("SKARA");
        var issue = project.createIssue("Issue to backport", List.of("This is just a test issue for testing backport"), new HashMap<String, JSONValue>());

        var backport = Backports.createBackport(issue, "1.0", "duke", null);
        assertEquals(JSON.of("Backport"), backport.properties().get("issuetype"));
        assertEquals(JSON.array().add("1.0"), backport.properties().get("fixVersions"));
        assertNotEquals(issue.id(), backport.id());

        var backportOfLink = backport.links().stream().filter(l -> l.relationship().equals(Optional.of("backport of"))).findFirst();
        assertTrue(backportOfLink.isPresent());
        assertTrue(backportOfLink.get().issue().isPresent());
        assertEquals(issue.id(), backportOfLink.get().issue().get().id());

        issue = project.issue(issue.id()).orElseThrow();
        var backportedByLink = issue.links().stream().filter(l -> l.relationship().equals(Optional.of("backported by"))).findFirst();
        assertTrue(backportedByLink.isPresent());
        assertTrue(backportedByLink.get().issue().isPresent());
        assertEquals(backport.id(), backportedByLink.get().issue().get().id());
    }
}
