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
package org.openjdk.skara.host.github;

import org.openjdk.skara.host.*;
import org.openjdk.skara.host.network.*;
import org.openjdk.skara.json.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

public class GitHubHost implements Host {
    private final URI uri;
    private final Pattern webUriPattern;
    private final String webUriReplacement;
    private final GitHubApplication application;
    private final PersonalAccessToken pat;
    private final RestRequest request;

    public GitHubHost(URI uri, GitHubApplication application, Pattern webUriPattern, String webUriReplacement) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.application = application;
        this.pat = null;

        var baseApi = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/")
                .build();

        request = new RestRequest(baseApi, () -> Arrays.asList(
                "Authorization", "token " + getInstallationToken(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json"));
    }

    public GitHubHost(URI uri, PersonalAccessToken pat) {
        this.uri = uri;
        this.webUriPattern = null;
        this.webUriReplacement = null;
        this.pat = pat;
        this.application = null;

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi, () -> Arrays.asList(
                "Authorization", "token " + pat.token()));
    }

    public GitHubHost(URI uri) {
        this.uri = uri;
        this.webUriPattern = null;
        this.webUriReplacement = null;
        this.pat = null;
        this.application = null;

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi);
    }

    public URI getURI() {
        return uri;
    }

    URI getWebURI(String endpoint) {
        var baseWebUri = URIBuilder.base(uri)
                                   .setPath(endpoint)
                                   .build();

        if (webUriPattern == null) {
            return baseWebUri;
        }

        var matcher = webUriPattern.matcher(baseWebUri.toString());
        if (!matcher.matches()) {
            return baseWebUri;

        }
        return URIBuilder.base(matcher.replaceAll(webUriReplacement)).build();
    }

    String getInstallationToken() {
        if (application != null) {
            return application.getInstallationToken();
        } else {
            return pat.token();
        }
    }

    @Override
    public boolean isValid() {
        var endpoints = request.get("")
                               .onError(response -> JSON.of())
                               .execute();
        return !endpoints.isNull();
    }

    JSONObject getProjectInfo(String name) {
        var project = request.get("repos/" + name)
                             .execute();
        return project.asObject();
    }

    @Override
    public HostedRepository getRepository(String name) {
        return new GitHubRepository(this, name);
    }

    @Override
    public HostUserDetails getUserDetails(String username) {
        var details = request.get("users/" + URLEncoder.encode(username, StandardCharsets.UTF_8)).execute().asObject();

        // Always present
        var login = details.get("login").asString();
        var id = details.get("id").asInt();

        var name = details.get("name").asString();
        if (name == null) {
            name = login;
        }
        return new HostUserDetails(id, login, name);
    }

    @Override
    public HostUserDetails getCurrentUserDetails() {
        if (application != null) {
            var appDetails = application.getAppDetails();
            var appName = appDetails.get("name").asString() + "[bot]";
            return getUserDetails(appName);
        } else if (pat != null){
            return getUserDetails(pat.userName());
        } else {
            throw new IllegalStateException("No credentials present");
        }
    }
}
