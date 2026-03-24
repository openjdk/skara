/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.github;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.openjdk.skara.json.JSON;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubForgeFactoryTests {
    @Test
    void webUrlConfiguration() {
        var conf = JSON.object()
            .put("weburl", JSON.object()
                .put("pattern", "^(http://www.example.com)/test/(.*)$")
                .put("replacement", "$1/another/$2")
        );
        var factory = new GitHubForgeFactory();
        var gh = (GitHubHost) factory.create(URI.create("http://www.example.com"), null, conf);

        assertEquals(URI.create("http://www.example.com/another/hello"), gh.getWebURI("/test/hello"));
    }

    @Test
    void altWebUrlConfiguration() {
        var conf = JSON.object()
            .put("weburl", JSON.object()
                .put("pattern", "^(http://www.example.com)/test/(.*)$")
                .put("replacement", "$1/another/$2")
                .put("altreplacements", JSON.array().add("http://localhost/$2"))
        );
        var factory = new GitHubForgeFactory();
        var gh = (GitHubHost) factory.create(URI.create("http://www.example.com"), null, conf);

        assertEquals(List.of(URI.create("http://www.example.com/another/hello"),
                             URI.create("http://localhost/hello")),
                     gh.getAllWebURIs("/test/hello"));
    }

    @Test
    void offlineConfiguration() {
        var conf = JSON.object().put("offline", true);
        var factory = new GitHubForgeFactory();
        var gh = (GitHubHost) factory.create(URI.create("https://github.test"), null, conf);

        assertTrue(gh.isOffline());
    }

    @Test
    void orgsConfiguration() {
        var conf = JSON.object().put("orgs", JSON.array().add("foo").add("bar"));
        var factory = new GitHubForgeFactory();
        var gh = (GitHubHost) factory.create(URI.create("https://github.test"), null, conf);
        assertEquals(Set.of("foo", "bar"), gh.organizations());
    }

    @Test
    void prTemplateConfiguration() {
        var conf = JSON.object().put("prTemplate", JSON.array().add("").add("second").add("third"));
        var factory = new GitHubForgeFactory();
        var forge = factory.create(URI.create("https://github.test"), null, conf);
        assertEquals(Optional.of("\nsecond\nthird"), forge.defaultPullRequestTemplate());
    }

    @Test
    void defaultConfiguration() {
        var factory = new GitHubForgeFactory();
        var gh = (GitHubHost) factory.create(URI.create("http://github.test"), null, null);

        assertEquals(Set.of(), gh.organizations());
        assertFalse(gh.isOffline());
        assertEquals(URI.create("http://github.test/hello"), gh.getWebURI("/hello"));
        assertEquals(List.of(URI.create("http://github.test/hello")), gh.getAllWebURIs("/hello"));
        assertEquals(Optional.empty(), gh.defaultPullRequestTemplate());
    }
}
