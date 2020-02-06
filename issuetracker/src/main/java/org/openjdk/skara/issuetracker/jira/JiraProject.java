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
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JiraProject implements IssueProject {
    private final JiraHost jiraHost;
    private final String projectName;
    private final RestRequest request;

    private JSONObject projectMetadataCache = null;
    private List<JiraLinkType> linkTypes = null;

    private final Logger log = Logger.getLogger("org.openjdk.skara.issuetracker.jira");

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

    private Map<String, String> versions() {
        var ret = new HashMap<String, String>();
        for (var type : project().get("versions").asArray()) {
            ret.put(type.get("name").asString(), type.get("id").asString());
        }
        return ret;
    }

    private void populateLinkTypesIfNeeded() {
        if (linkTypes != null) {
            return;
        }

        linkTypes = request.get("issueLinkType").execute()
                           .get("issueLinkTypes").stream()
                           .map(JSONValue::asObject)
                           .map(o -> new JiraLinkType(o.get("name").asString(),
                                                      o.get("inward").asString(),
                                                      o.get("outward").asString()))
                           .collect(Collectors.toList());
    }

    List<JiraLinkType> linkTypes() {
        populateLinkTypesIfNeeded();
        return linkTypes;
    }

    void executeLinkQuery(JSONObject query) {
        request.post("issueLink").body(query).execute();
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

    private static final Set<String> knownProperties = Set.of("issuetype", "fixVersions", "versions", "priority", "components");

    boolean isAllowedProperty(String name) {
        if (knownProperties.contains(name)) {
            return true;
        }
        return name.startsWith("customfield_");
    }

    Optional<JSONValue> decodeProperty(String name, JSONValue value) {
        if (!isAllowedProperty(name)) {
            return Optional.empty();
        }
        if (value.isNull()) {
            return Optional.empty();
        }

        // Transform known fields to a better representation
        switch (name) {
            case "fixVersions": // fall-through
            case "versions": // fall-through
            case "components":
                return Optional.of(new JSONArray(value.stream()
                                                      .map(obj -> obj.get("name"))
                                                      .collect(Collectors.toList())));
            case "issuetype":
                return Optional.of(JSON.of(value.get("name").asString()));
            case "priority":
                return Optional.of(JSON.of(value.get("id").asString()));
            default:
                return Optional.of(value);
        }
    }

    Optional<JSONValue> encodeProperty(String name, JSONValue value) {
        if (!isAllowedProperty(name)) {
            return Optional.empty();
        }

        switch (name) {
            case "fixVersions": // fall-through
            case "versions":
                return Optional.of(new JSONArray(value.stream()
                                                      .map(JSONValue::asString)
                                                      .map(s -> JSON.object().put("id", versions().get(s)))
                                                      .collect(Collectors.toList())));
            case "components":
                return Optional.of(new JSONArray(value.stream()
                                                      .map(JSONValue::asString)
                                                      .map(s -> JSON.object().put("id", components().get(s)))
                                                      .collect(Collectors.toList())));
            case "issuetype":
                return Optional.of(JSON.object().put("id", issueTypes().get(value.asString())));
            case "priority":
                return Optional.of(JSON.object().put("id", value.asString()));
            default:
                return Optional.of(value);
        }
    }

    @Override
    public IssueTracker issueTracker() {
        return jiraHost;
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(jiraHost.getUri()).setPath("/projects/" + projectName).build();
    }

    private boolean isInitialField(String name, JSONValue value) {
        if (name.equals("project") || name.equals("summary") || name.equals("description") || name.equals("components")) {
            return true;
        }
        return false;
    }

    // Custom fields are set a bit differently depending on their type
    private JSONValue filterCustomFieldValue(String name, JSONValue unfiltered) {
        if (!name.startsWith("customfield_")) {
            return unfiltered;
        }
        if (unfiltered.isObject()) {
            if (unfiltered.asObject().contains("id")) {
                return unfiltered.get("id");
            } else {
                return unfiltered;
            }
        } else {
            return unfiltered;
        }
    }

    @Override
    public Issue createIssue(String title, List<String> body, Map<String, JSONValue> properties) {
        var query = JSON.object();

        var finalProperties = new HashMap<String, JSONValue>(properties);

        // Always override certain fields
        finalProperties.put("project", JSON.object().put("id", projectId()));
        finalProperties.put("summary", JSON.of(title));
        finalProperties.put("description", JSON.of(String.join("\n", body)));

        // Encode optional properties as fields
        for (var property : properties.entrySet()) {
            var encoded = encodeProperty(property.getKey(), property.getValue());
            if (encoded.isEmpty()) {
                continue;
            }
            finalProperties.put(property.getKey(), encoded.get());
        }

        // Provide default values for required fields if not present
        finalProperties.putIfAbsent("components", JSON.array().add(JSON.object().put("id", defaultComponent())));

        // Filter out the fields that can be set at creation time
        var fields = JSON.object();
        finalProperties.entrySet().stream()
                       .filter(entry -> isInitialField(entry.getKey(), entry.getValue()))
                       .forEach(entry -> fields.put(entry.getKey(), entry.getValue()));

        // Certain types can only be set after creation, so always start with the default value
        fields.put("issuetype", JSON.object().put("id", defaultIssueType()));

        query.put("fields", fields);
        jiraHost.securityLevel().ifPresent(securityLevel -> fields.put("security", JSON.object()
                                                                                       .put("id", securityLevel)));
        var data = request.post("issue")
                          .body(query)
                          .execute();

        // Apply fields that have to be set later (if any)
        var editFields = JSON.object();
        finalProperties.entrySet().stream()
                       .filter(entry -> !isInitialField(entry.getKey(), entry.getValue()))
                       .forEach(entry -> editFields.put(entry.getKey(), filterCustomFieldValue(entry.getKey(),
                                                                                               entry.getValue())));

        if (editFields.fields().size() > 0) {
            var id = data.get("key").asString();
            if (id.indexOf('-') < 0) {
                id = projectName.toUpperCase() + "-" + id;
            }
            var updateQuery = JSON.object().put("fields", editFields);
            request.put("issue/" + id)
                   .body(updateQuery)
                   .execute();

        }

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
