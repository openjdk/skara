/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.webrev;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebrevMetaData {
    private static final Pattern findPatchPattern = Pattern.compile(
            "[ ]*(?:<td>)?<a href=\".*\">(?<patchName>.*\\.patch)</a></td>(?:</tr>)?$");

    private final Optional<URI> patchURI;

    public WebrevMetaData(Optional<URI> patchURI) {
        this.patchURI = patchURI;
    }

    public static WebrevMetaData from(URI uri) throws IOException, URISyntaxException, InterruptedException {
        var sanatizedUri = sanitizeURI(uri);
        var patchFile = getPatchFile(sanatizedUri);

        return new WebrevMetaData(patchFile);
    }

    private static String dropSuffix(String s, String suffix) {
        if (s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    private static URI sanitizeURI(URI uri) throws URISyntaxException {
        var path = dropSuffix(uri.getPath(), "index.html");
        return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(),
                       path, uri.getQuery(), uri.getFragment());
    }

    private static Optional<URI> getPatchFile(URI uri) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var findPatchFileRcequest = HttpRequest.newBuilder()
                .uri(uri)
                .build();
        return client.send(findPatchFileRcequest, HttpResponse.BodyHandlers.ofLines())
                .body()
                .map(findPatchPattern::matcher)
                .filter(Matcher::matches)
                .findFirst()
                .map(m -> m.group("patchName"))
                .map(uri::resolve);
    }

    public Optional<URI> patchURI() {
        return patchURI;
    }
}
