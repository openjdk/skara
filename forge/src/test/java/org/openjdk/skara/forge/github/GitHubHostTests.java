/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.junit.jupiter.api.Test;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.TemporaryDirectory;

import java.io.IOException;
import java.net.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubHostTests {
    @Test
    void webUriPatternReplacement() throws IOException, URISyntaxException {
        try (var tempFolder = new TemporaryDirectory()) {
            var host = new GitHubHost(URIBuilder.base("http://www.example.com").build(),
                                      Pattern.compile("^(http://www.example.com)/test/(.*)$"), "$1/another/$2");
            assertEquals(new URI("http://www.example.com/another/hello"), host.getWebURI("/test/hello"));
        }
    }

    @Test
    void nonTransformedWebUrl() throws IOException, URISyntaxException {
        try (var tempFolder = new TemporaryDirectory()) {
            var host = new GitHubHost(URIBuilder.base("http://www.example.com").build(),
                                      Pattern.compile("^(http://www.example.com)/test/(.*)$"), "$1/another/$2");
            assertEquals(new URI("http://www.example.com/another/hello"), host.getWebURI("/test/hello"));
            assertEquals(new URI("http://www.example.com/test/hello"), host.getWebURI("/test/hello", false));
        }
    }
}
