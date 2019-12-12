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

import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class JiraProject implements IssueProject {
    private final JiraHost jiraHost;
    private final String projectName;
    private final RestRequest request;

    private JSONObject projectMetadataCache = null;
    private Map<String, String> versionNameToId = null;
    private Map<String, String> versionIdToName = null;

    JiraProject(JiraHost host, RestRequest request, String projectName) {
        this.jiraHost = host;
        this.projectName = projectName;
        this.request = request;
    }

    private JSONObject project() {
        if (projectMetadataCache == null) {
            projectMetadataCache = request.get("project/" + projectName).execute().asObject();
        }
        return projectMetadataCache;
    }

    private Map<String, String> issueTypes() {
        var ret = new HashMap<String, String>();
        for (var type : project().get("issueTypes").asArray()) {
            ret.put(type.get("name").asString(), type.get("id").asString());
        }
        return ret;
    }

    private Map<String, String> components() {
        var ret = new HashMap<String, String>();
        for (var type : project().get("components").asArray()) {
            ret.put(type.get("name").asString(), type.get("id").asString());
        }
        return ret;
    }

    private void populateVersionsIfNeeded() {
        if (versionIdToName != null) {
            return;
        }

        versionNameToId = project().get("versions").stream()
                                   .collect(Collectors.toMap(v -> v.get("name").asString(), v -> v.get("id").asString()));
        versionIdToName = project().get("versions").stream()
                                   .collect(Collectors.toMap(v -> v.get("id").asString(), v -> v.get("name").asString()));
    }

    Optional<String> fixVersionNameFromId(String id) {
        populateVersionsIfNeeded();
        return Optional.ofNullable(versionIdToName.getOrDefault(id, null));
    }

    Optional<String> fixVersionIdFromName(String name) {
        populateVersionsIfNeeded();
        return Optional.ofNullable(versionNameToId.getOrDefault(name, null));
    }

    private String projectId() {
        return project().get("id").asString();
    }

    private String defaultIssueType() {
        return issueTypes().values().stream()
                           .min(Comparator.naturalOrder()).orElseThrow();
    }

    private String defaultComponent() {
        return components().values().stream()
                           .min(Comparator.naturalOrder()).orElseThrow();
    }

    JiraHost jiraHost() {
        return jiraHost;
    }

    @Override
    public IssueTracker issueTracker() {
        return jiraHost;
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(jiraHost.getUri()).setPath("/projects/" + projectName).build();
    }

    @Override
    public Issue createIssue(String title, List<String> body) {
        var query = JSON.object();
        var fields = JSON.object()
                         .put("project", JSON.object()
                                             .put("id", projectId()))
                         .put("issuetype", JSON.object()
                                               .put("id", defaultIssueType()))
                         .put("components", JSON.array()
                                                .add(JSON.object().put("id", defaultComponent())))
                         .put("summary", title)
                         .put("description", String.join("\n", body));
        query.put("fields", fields);

        jiraHost.securityLevel().ifPresent(securityLevel -> fields.put("security", JSON.object()
                                                                                       .put("id", securityLevel)));
        var data = request.post("issue")
                          .body(query)
                          .execute();

        return issue(data.get("key").asString()).orElseThrow();
    }

    @Override
    public Optional<Issue> issue(String id) {
        if (id.indexOf('-') < 0) {
            id = projectName.toUpperCase() + "-" + id;
        }
        var issueRequest = request.restrict("issue/" + id);
        var issue = issueRequest.get("")
                           .onError(r -> r.statusCode() < 500 ? JSON.object().put("NOT_FOUND", true) : null)
                           .execute();
        if (issue == null) {
            throw new RuntimeException("Server error when trying to fetch issue " + id);
        }
        if (!issue.contains("NOT_FOUND")) {
            return Optional.of(new JiraIssue(this, issueRequest, issue));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public List<Issue> issues() {
        var ret = new ArrayList<Issue>();
        var issues = request.post("search")
                            .body("jql", "project = " + projectName + " AND status in (Open, New)")
                            .execute();
        for (var issue : issues.get("issues").asArray()) {
            ret.add(new JiraIssue(this, request, issue));
        }
        return ret;
    }
}
