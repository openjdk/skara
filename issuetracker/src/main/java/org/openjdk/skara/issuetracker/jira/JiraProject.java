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

import java.time.format.DateTimeFormatter;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JiraProject implements IssueProject {

    public static final String RESOLVED_IN_BUILD = "customfield_10006";
    public static final String SUBCOMPONENT = "customfield_10008";
    public static final String JEP_NUMBER = "customfield_10701";

    private final JiraHost jiraHost;
    private final String projectName;
    private final RestRequest request;

    private JSONObject projectMetadataCache = null;
    private List<JiraLinkType> linkTypes = null;
    private JSONObject createMetaCache = null;
    private final Map<String, Map<String, JSONObject>> createFieldCache = new HashMap<>();
    private JSONObject editMetaCache = null;

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

    private JSONObject createMeta() {
        if (createMetaCache == null) {
            createMetaCache = request.get("issue/createmeta")
                                     .param("projectKeys", projectName)
                                     .param("expand", "projects.issuetypes.fields")
                                     .execute()
                                     .asObject();
        }
        return createMetaCache;
    }

    private Map<String, JSONObject> createFields(String issueType) {
        if (createFieldCache.containsKey(issueType)) {
            return createFieldCache.get(issueType);
        }
        var ret = new HashMap<String, JSONObject>();
        var fields = request.get("issue/createmeta/" + projectName + "/issuetypes/" + issueType)
                            .onError(et -> et.statusCode() == 404 ? Optional.of((JSON.object().put("jira7", true))) : Optional.empty())
                            .execute()
                            .asObject();
        if (fields.contains("jira7")) {
            var createMeta = createMeta();
            fields = createMeta.get("projects").stream()
                               .filter(p -> p.contains("name"))
                               .filter(p -> p.get("name").asString().equalsIgnoreCase(projectName))
                               .findAny().orElseThrow()
                               .get("issuetypes").stream()
                               .filter(i -> i.get("id").asString().equals(issueType))
                               .findAny().orElseThrow()
                               .get("fields")
                               .asObject();
            for (var field : fields.fields()) {
                ret.put(field.name(), field.value().asObject());
            }
        } else {
            for (var field : fields.get("values").asArray()) {
                ret.put(field.get("fieldId").asString(), field.asObject());
            }
        }
        createFieldCache.put(issueType, ret);
        return ret;
    }

    private JSONObject editMeta(String issueId) {
        if (editMetaCache == null) {
            editMetaCache = request.get("issue/" + issueId + "/editmeta")
                                     .execute()
                                     .asObject();
        }
        return editMetaCache;
    }

    private Map<String, String> issueTypes() {
        var ret = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (var type : project().get("issueTypes").asArray()) {
            ret.put(type.get("name").asString(), type.get("id").asString());
        }
        return ret;
    }

    private String issueTypeId(String name) {
        var ret = issueTypes().get(name);
        if (ret == null) {
            var allowedList = issueTypes().keySet().stream()
                                          .map(s -> "`" + s + "`")
                                          .collect(Collectors.joining(", "));
            throw new RuntimeException("Unknown issue type `" + name + "`` Known issue types are " + allowedList + ".");
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

    private String componentId(String name) {
        var ret = components().get(name);
        if (ret == null) {
            var allowedList = components().keySet().stream()
                                          .map(s -> "`" + s + "`")
                                          .collect(Collectors.joining(", "));
            throw new RuntimeException("Unknown component `" + name + "`. Known components are " + allowedList + ".");
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

    private String versionId(String name) {
        var ret = versions().get(name);
        if (ret == null) {
            // Ensure this is not due to a stale cache
            projectMetadataCache = null;
            ret = versions().get(name);
            if (ret == null) {
                throw new RuntimeException("Unknown version `" + name + "`");
            }
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

    private static final Set<String> knownProperties = Set.of("issuetype", "fixVersions", "versions", "priority", "components", "status");
    private static final Set<String> readOnlyProperties = Set.of("resolution", "security");

    boolean isAllowedProperty(String name, boolean forWrite) {
        if (knownProperties.contains(name)) {
            return true;
        }
        if (!forWrite && readOnlyProperties.contains(name)) {
            return true;
        }
        return name.startsWith("customfield_");
    }

    Optional<JSONValue> decodeProperty(String name, JSONValue value) {
        if (!isAllowedProperty(name, false)) {
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
            case RESOLVED_IN_BUILD:
                return Optional.of(JSON.of(value.get("value").asString()));
            case SUBCOMPONENT:
                if (value.isString()) {
                    return Optional.of(value);
                } // fall-through
            case "status": // fall-through
            case "issuetype":
                return Optional.of(JSON.of(value.get("name").asString()));
            case "priority": // fall-through
            case "security":
                return Optional.of(JSON.of(value.get("id").asString()));
            default:
                return Optional.of(value);
        }
    }

    Optional<JSONValue> encodeProperty(String name, JSONValue value) {
        if (!isAllowedProperty(name, true)) {
            return Optional.empty();
        }

        switch (name) {
            case "fixVersions": // fall-through
            case "versions":
                return Optional.of(new JSONArray(value.stream()
                                                      .map(JSONValue::asString)
                                                      .map(s -> JSON.object().put("id", versionId(s)))
                                                      .collect(Collectors.toList())));
            case "components":
                return Optional.of(new JSONArray(value.stream()
                                                      .map(JSONValue::asString)
                                                      .map(s -> JSON.object().put("id", componentId(s)))
                                                      .collect(Collectors.toList())));
            case "issuetype":
                return Optional.of(JSON.object().put("id", issueTypeId(value.asString())));
            case "priority": // fall-through
            case "security":
                return Optional.of(JSON.object().put("id", value.asString()));
            default:
                return Optional.of(value);
        }
    }

    JSONValue encodeCustomFields(String name, JSONValue value, Map<String, JSONValue> allProperties, String forIssue) {
        if (!name.startsWith("customfield_")) {
            return value;
        }

        if (name.equals(RESOLVED_IN_BUILD)) {
            var editMeta = editMeta(forIssue);
            var valueToId = editMeta.get("fields").get(name).get("allowedValues").stream()
                                    .collect(Collectors.toMap(o -> o.get("value").asString(),
                                                              o -> o.get("id")));
            return JSON.object().put("id", valueToId.get(value.asString()));
        }

        if (!name.equals(SUBCOMPONENT)) {
            if (value.isObject()) {
                if (value.asObject().contains("id")) {
                    return value.get("id");
                } else {
                    return value;
                }
            } else {
                return value;
            }
        }

        var fields = createFields(allProperties.get("issuetype").get("id").asString());
        var field = fields.get(name);
        var componentIds = allProperties.get("components").stream()
                                        .map(c -> c.get("id").asString())
                                        .map(Integer::parseInt)
                                        .collect(Collectors.toSet());
        if (!field.contains("allowedValues")) {
             return value.get("name");
        }
        var allowed = field.get("allowedValues").stream()
                           .filter(c -> componentIds.contains(c.get("id").asInt()))
                           .flatMap(c -> c.get("subComponents").stream())
                           .collect(Collectors.toMap(s -> s.get("name").asString(),
                                                     s -> s.get("id").asInt()));
        if (!allowed.containsKey(value.asString())) {
            var allowedList = allowed.keySet().stream()
                                     .map(s -> "`" + s + "`")
                                     .collect(Collectors.joining(", "));
            throw new RuntimeException("Unknown subcomponent `" + value.asString() + "`. Known subcomponents are " +
                                               allowedList + ".");
        }

        return JSON.of(allowed.get(value.asString()));
    }

    @Override
    public IssueTracker issueTracker() {
        return jiraHost;
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base(jiraHost.uri()).appendPath("/projects/" + projectName).build();
    }

    private boolean isInitialField(String issueType, String name) {
        var fields = createFields(issueType);
        return fields.containsKey(name);
    }

    @Override
    public IssueTrackerIssue createIssue(String title, List<String> body, Map<String, JSONValue> properties) {
        var query = JSON.object();

        // Encode optional properties as fields
        var finalProperties = new HashMap<String, JSONValue>();
        for (var property : properties.entrySet()) {
            var encoded = encodeProperty(property.getKey(), property.getValue());
            if (encoded.isEmpty()) {
                continue;
            }
            finalProperties.put(property.getKey(), encoded.get());
        }

        // Always override certain fields
        finalProperties.put("project", JSON.object().put("id", projectId()));
        finalProperties.put("summary", JSON.of(title));
        finalProperties.put("description", JSON.of(String.join("\n", body)));

        // Provide default values for required fields if not present
        finalProperties.putIfAbsent("components", JSON.array().add(JSON.object().put("id", defaultComponent())));
        finalProperties.putIfAbsent("issuetype", JSON.object().put("id", defaultIssueType()));

        // Filter out the fields that can be set at creation time
        var issueType = finalProperties.get("issuetype").get("id").asString();
        var fields = JSON.object();
        finalProperties.entrySet().stream()
                       .filter(entry -> isInitialField(issueType, entry.getKey()))
                       .forEach(entry -> fields.put(entry.getKey(), encodeCustomFields(entry.getKey(),
                                                                                       entry.getValue(),
                                                                                       finalProperties,
                                                                                       null)));
        query.put("fields", fields);
        var data = request.post("issue")
                          .body(query)
                          .execute();

        // Apply fields that have to be set later (if any)
        var id = data.get("key").asString();
        if (id.indexOf('-') < 0) {
            id = projectName.toUpperCase() + "-" + id;
        }
        var finalId = id;
        var editFields = JSON.object();
        finalProperties.entrySet().stream()
                       .filter(entry -> !isInitialField(issueType, entry.getKey()))
                       .forEach(entry -> editFields.put(entry.getKey(), encodeCustomFields(entry.getKey(),
                                                                                           entry.getValue(),
                                                                                           finalProperties,
                                                                                           finalId)));
        if (editFields.fields().size() > 0) {
            var updateQuery = JSON.object().put("fields", editFields);
            request.put("issue/" + id)
                   .body(updateQuery)
                   .execute();

        }

        return issue(data.get("key").asString()).orElseThrow();
    }

    private RestRequest generateIssueRequest(String id) {
        return request.restrict("issue/" + id);
    }

    private RestRequest generateIssueRequest(JSONValue json) {
        return generateIssueRequest(JiraIssue.id(json));
    }

    @Override
    public Optional<IssueTrackerIssue> issue(String id) {
        if (id.indexOf('-') < 0) {
            id = projectName.toUpperCase() + "-" + id;
        }
        var issueRequest = generateIssueRequest(id);
        final var finalId = id;
        var issue = issueRequest.get("")
                                .onError(r -> {
                                    log.info("Getting issue " + finalId + " failed with " + r.statusCode());
                                    return r.statusCode() < 500 ? Optional.of(JSON.object().put("NOT_FOUND", true)) : Optional.empty();
                                })
                                .execute();
        if (!issue.contains("NOT_FOUND")) {
            return Optional.of(new JiraIssue(this, issueRequest, issue));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<IssueTrackerIssue> jepIssue(String jepId) {
        var issues = request.post("search")
                .body("jql", "project = " + projectName + " AND \"JEP Number\" ~ \"" + jepId + "\"")
                .execute();
        if (issues.get("issues").asArray().size() == 0) {
            return Optional.empty();
        } else {
            var json = issues.get("issues").asArray().get(0);
            return Optional.of(new JiraIssue(this, generateIssueRequest(json), json));
        }
    }

    @Override
    public List<IssueTrackerIssue> issues() {
        var ret = new ArrayList<IssueTrackerIssue>();
        var issues = request.post("search")
                            .body("jql", "project = " + projectName + " AND status in (Open, New)")
                            .execute();
        for (var issue : issues.get("issues").asArray()) {
            ret.add(new JiraIssue(this, generateIssueRequest(issue), issue));
        }
        return ret;
    }

    // Need to sort by updated time in descending order to guarantee that no issues are missed, see SKARA-1962 for details
    @Override
    public List<IssueTrackerIssue> issues(ZonedDateTime updatedAfter) {
        var timeString = toTimeString(updatedAfter);
        var jql = "project = " + projectName + " AND updated >= '" + timeString + "' ORDER BY updated DESC";
        return queryIssues(jql);
    }


    // Need to sort by updated time in descending order to guarantee that no issues are missed, see SKARA-1962 for details
    @Override
    public List<IssueTrackerIssue> csrIssues(ZonedDateTime updatedAfter) {
        var timeString = toTimeString(updatedAfter);
        var jql = "project = " + projectName + " AND updated >= '" + timeString + "' AND issuetype = CSR ORDER BY updated DESC";
        return queryIssues(jql);
    }

    @Override
    public Optional<IssueTrackerIssue> lastUpdatedIssue() {
        var jql = "project = " + projectName + " ORDER BY updated DESC";
        var issues = request.get("search")
                .param("jql", jql)
                .param("maxResults", "1")
                .execute();
        var issuesArray = issues.get("issues").asArray();
        if (issuesArray.isEmpty()) {
            return Optional.empty();
        } else {
            var json = issuesArray.get(0);
            return Optional.of(new JiraIssue(this, generateIssueRequest(json), json));
        }
    }

    private String toTimeString(ZonedDateTime time) {
        var timeZoned = time.withZoneSameInstant(jiraHost.timeZone());
        return timeZoned.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
    }

    private List<IssueTrackerIssue> queryIssues(String jql) {
        var ret = new HashMap<String, IssueTrackerIssue>();
        int count = 0;
        var issues = request.get("search")
                .param("jql", jql)
                .execute();
        var startAt = 0;
        while (issues.get("issues").asArray().size() > 0) {
            for (var issue : issues.get("issues").asArray()) {
                ret.put(JiraIssue.id(issue), new JiraIssue(this, generateIssueRequest(issue), issue));
                count++;
            }

            if (count < issues.get("total").asInt()) {
                startAt += issues.get("issues").asArray().size();
                issues = request.get("search")
                        .param("jql", jql)
                        .param("startAt", String.valueOf(startAt))
                        .execute();
            } else {
                break;
            }
        }

        return ret.values().stream().toList();
    }

    @Override
    public String name() {
        return projectName.toUpperCase();
    }
}
