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
package org.openjdk.skara.issuetracker.jira;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.util.*;

public class JiraHost implements IssueTracker {
    private final URI uri;
    private final String visibilityRole;
    private final String securityLevel;
    private final RestRequest request;

    JiraHost(URI uri) {
        this.uri = uri;
        this.visibilityRole = null;
        this.securityLevel = null;

        var baseApi = URIBuilder.base(uri)
                                .setPath("/rest/api/2/")
                                .build();
        request = new RestRequest(baseApi);
    }

    JiraHost(URI uri, JiraVault jiraVault) {
        this.uri = uri;
        this.visibilityRole = null;
        this.securityLevel = null;
        var baseApi = URIBuilder.base(uri)
                                .setPath("/rest/api/2/")
                                .build();
        request = new RestRequest(baseApi, () -> Arrays.asList("Cookie", jiraVault.getCookie()));
    }

    JiraHost(URI uri, JiraVault jiraVault, String visibilityRole, String securityLevel) {
        this.uri = uri;
        this.visibilityRole = visibilityRole;
        this.securityLevel = securityLevel;
        var baseApi = URIBuilder.base(uri)
                                .setPath("/rest/api/2/")
                                .build();
        request = new RestRequest(baseApi, () -> Arrays.asList("Cookie", jiraVault.getCookie()));
    }

    URI getUri() {
        return uri;
    }

    Optional<String> visibilityRole() {
        return Optional.ofNullable(visibilityRole);
    }

    Optional<String> securityLevel() {
        return Optional.ofNullable(securityLevel);
    }

    @Override
    public boolean isValid() {
        var version = request.get("serverInfo")
                             .onError(r -> JSON.object().put("invalid", true))
                             .execute();
        return !version.contains("invalid");
    }

    @Override
    public IssueProject project(String name) {
        return new JiraProject(this, request, name);
    }

    @Override
    public Optional<HostUser> user(String username) {
        var data = request.get("user")
                          .param("username", username)
                          .onError(r -> JSON.of())
                          .execute();
        if (data.isNull()) {
            return Optional.empty();
        }

        var user = new HostUser(data.get("name").asString(),
                                data.get("name").asString(),
                                data.get("displayName").asString());
        return Optional.of(user);
    }

    @Override
    public HostUser currentUser() {
        var data = request.get("myself").execute();
        var user = new HostUser(data.get("name").asString(),
                                data.get("name").asString(),
                                data.get("displayName").asString());
        return user;
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        var data = request.get("user")
                          .param("username", user.id())
                          .param("expand", "groups")
                          .execute();
        for (var group : data.get("groups").get("items").asArray()) {
            if (group.get("name").asString().equals(groupId)) {
                return true;
            }
        }
        return false;
    }
}
