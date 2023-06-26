/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.json.JSONValue;

/**
 * Backing store for TestIssueTrackerIssue. Represents the "server side" state of an Issue.
 */
public class TestIssueTrackerIssueStore extends TestIssueStore {

    private final List<Link> links = new ArrayList<>();
    private final Map<String, JSONValue> properties = new HashMap<>();

    public TestIssueTrackerIssueStore(String id, IssueProject issueProject, HostUser author, String title,
            List<String> body, Map<String, JSONValue> properties) {
        super(id, issueProject, author, title, body);
        // Default status New to mimic JiraIssue
        this.properties.put("status", JSON.object().put("name", JSON.of("New")));
        if (properties != null) {
            this.properties.putAll(properties);
        }
    }

    /**
     * Use the underlying status of the issue for state to better mimic JiraIssue
     */
    @Override
    public Issue.State state() {
        return switch (properties().get("status").get("name").asString()) {
            case "Closed" -> Issue.State.CLOSED;
            case "Resolved" -> Issue.State.RESOLVED;
            default -> Issue.State.OPEN;
        };
    }

    /**
     * Use the underlying status of the issue for state to better mimic JiraIssue
     */
    @Override
    public void setState(Issue.State state) {
        var newStatus = switch (state) {
            case CLOSED -> "Closed";
            case RESOLVED -> "Resolved";
            default -> "Open";
        };
        properties().put("status", JSON.object().put("name", JSON.of(newStatus)));
    }

    public List<Link> links() {
        return links;
    }

    public Map<String, JSONValue> properties() {
        return properties;
    }
}
