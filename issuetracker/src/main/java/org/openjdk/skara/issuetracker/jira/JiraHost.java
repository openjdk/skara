/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;
import java.time.ZoneId;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.util.*;

public class JiraHost implements IssueTracker {
    private static class BackportEndpoint implements CustomEndpoint, CustomEndpointRequest {
        private final RestRequest request;

        private RestRequest.QueryBuilder query;
        private JSONValue body;

        private BackportEndpoint(RestRequest request) {
            this.request = request;
        }

        @Override
        public CustomEndpointRequest post() {
            query = request.post();
            return this;
        }

        @Override
        public CustomEndpointRequest body(JSONValue body) {
            this.body = body;
            return this;
        }

        @Override
        public CustomEndpointRequest header(String value, String name) {
            query = query.header(value, name);
            return this;
        }

        @Override
        public CustomEndpointRequest onError(RestRequest.ErrorTransform transform) {
            query = query.onError(transform);
            return this;
        }

        @Override
        public JSONValue execute() {
            if (body == null || !body.contains("parentIssueKey")) {
                throw new IllegalStateException("Body must be a JSON object with at least the field 'parentIssueKey' set");
            }

            return query.body(body).execute();
        }
    }

    private static final String REST_API_ENDPOINT_PATH = "/rest/api/2/";
    private static final String BACKPORT_ENDPOINT_PATH = "/rest/jbs/1.0/backport/";

    private final URI uri;
    private final String visibilityRole;
    private final RestRequest request;
    private final RestRequest backportRequest;
    private final Map<String, IssueProject> issueProjects = new HashMap<>();

    private HostUser currentUser;
    private ZoneId timeZone;

    JiraHost(URI uri) {
        this.uri = uri;
        this.visibilityRole = null;

        var baseApi = URIBuilder.base(uri)
                                .appendPath(REST_API_ENDPOINT_PATH)
                                .build();
        this.request = new RestRequest(baseApi);

        var backportUri = URIBuilder.base(uri)
                                    .appendPath(BACKPORT_ENDPOINT_PATH)
                                    .build();
        this.backportRequest = new RestRequest(backportUri);
    }

    /**
     * This constructor is only used by the manual test code.
     */
    JiraHost(URI uri, String header, String value) {
        this.uri = uri;
        this.visibilityRole = null;

        var baseApi = URIBuilder.base(uri)
                                .appendPath(REST_API_ENDPOINT_PATH)
                                .build();
        this.request = new RestRequest(baseApi, "test", (r) -> Arrays.asList(header, value));

        var backportUri = URIBuilder.base(uri)
                                    .appendPath(BACKPORT_ENDPOINT_PATH)
                                    .build();
        this.backportRequest = new RestRequest(backportUri, "test", (r) -> Arrays.asList(header, value));
    }

    JiraHost(URI uri, JiraVault jiraVault) {
        this(uri, jiraVault, null);
    }

    JiraHost(URI uri, JiraVault jiraVault, String visibilityRole) {
        this.uri = uri;
        this.visibilityRole = visibilityRole;

        var baseApi = URIBuilder.base(uri)
                                .appendPath(REST_API_ENDPOINT_PATH)
                                .build();
        this.request = new RestRequest(baseApi, jiraVault.authId(), (r) -> Arrays.asList("Cookie", jiraVault.getCookie()));

        var backportUri = URIBuilder.base(uri)
                                    .appendPath(BACKPORT_ENDPOINT_PATH)
                                    .build();
        this.backportRequest = new RestRequest(backportUri, jiraVault.authId(), (r) -> Arrays.asList("Cookie", jiraVault.getCookie()));
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public Optional<CustomEndpoint> lookupCustomEndpoint(String path) {
        var endpoint = switch (path) {
            case BACKPORT_ENDPOINT_PATH -> new BackportEndpoint(backportRequest);
            default -> null;
        };
        return Optional.ofNullable(endpoint);
    }

    Optional<String> visibilityRole() {
        return Optional.ofNullable(visibilityRole);
    }

    @Override
    public boolean isValid() {
        var version = request.get("serverInfo")
                             .onError(r -> Optional.of(JSON.object().put("invalid", true)))
                             .execute();
        return !version.contains("invalid");
    }

    @Override
    public IssueProject project(String name) {
        return issueProjects.computeIfAbsent(name, n -> new JiraProject(this, request, n));
    }

    @Override
    public Optional<HostUser> user(String username) {
        var data = request.get("user")
                          .param("username", username)
                          .onError(r -> r.statusCode() == 404 ? Optional.of(JSON.of()) : Optional.empty())
                          .execute();
        if (data.isNull()) {
            return Optional.empty();
        }

        var user = HostUser.create(data.get("name").asString(),
                data.get("name").asString(),
                data.get("displayName").asString(),
                data.get("active").asBoolean());
        return Optional.of(user);
    }

    @Override
    public HostUser currentUser() {
        if (currentUser == null) {
            var data = request.get("myself").execute();
            currentUser = HostUser.builder()
                    .id(data.get("name").asString())
                    .username(data.get("name").asString())
                    .fullName(data.get("displayName").asString())
                    .active(data.get("active").asBoolean())
                    .email(data.get("emailAddress").asString())
                    .build();
        }
        return currentUser;
    }

    public ZoneId timeZone() {
        if (timeZone == null) {
            var data = request.get("myself").execute();
            timeZone = ZoneId.of(data.get("timeZone").asString());
        }
        return timeZone;
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

    @Override
    public String hostname() {
        return uri.getHost();
    }

    /**
     * Jira can only query on timestamps with minute precision.
     */
    @Override
    public Duration timeStampQueryPrecision() {
        return Duration.ofMinutes(1);
    }
}
