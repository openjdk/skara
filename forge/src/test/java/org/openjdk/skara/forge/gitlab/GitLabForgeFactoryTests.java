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
package org.openjdk.skara.forge.gitlab;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.openjdk.skara.json.JSON;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitLabForgeFactoryTests {
    @Test
    void nameConfiguration() {
        var conf = JSON.object().put("name", "foo");
        var factory = new GitLabForgeFactory();
        var forge = factory.create(URI.create("http://www.example.com"), null, conf);

        assertEquals("foo", forge.name());
    }

    @Test
    void groupsConfiguration() {
        var conf = JSON.object().put("groups", JSON.array().add("foo").add("bar"));
        var factory = new GitLabForgeFactory();
        var gl = (GitLabHost) factory.create(URI.create("http://www.example.com"), null, conf);

        assertEquals(List.of("foo", "bar"), gl.groups());
    }

    @Test
    void prTemplateConfiguration() {
        var conf = JSON.object().put("prTemplate", "FOO");
        var factory = new GitLabForgeFactory();
        var forge = factory.create(URI.create("http://www.example.com"), null, conf);

        assertEquals(Optional.of("FOO"), forge.defaultPullRequestTemplate());
    }

    @Test
    void defaultConfiguration() {
        var factory = new GitLabForgeFactory();
        var gl = (GitLabHost) factory.create(URI.create("http://www.example.com"), null, null);

        assertEquals("GitLab", gl.name());
        assertEquals(List.of(), gl.groups());
        assertEquals(Optional.empty(), gl.defaultPullRequestTemplate());
    }
}
