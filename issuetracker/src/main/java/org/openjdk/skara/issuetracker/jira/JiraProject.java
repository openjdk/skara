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
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.util.*;

public class JiraProject implements IssueProject {
    private final JiraHost jiraHost;
    private final String projectName;
    private final RestRequest request;

    JiraProject(JiraHost host, RestRequest request, String projectName) {
        this.jiraHost = host;
        this.projectName = projectName;
        this.request = request;
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
        throw new RuntimeException("needs authentication; not implemented yet");
    }

    @Override
    public Optional<Issue> issue(String id) {
        if (id.indexOf('-') < 0) {
            id = projectName.toUpperCase() + "-" + id;
        }
        var issue = request.get("issue/" + id)
                           .onError(r -> r.statusCode() == 404 ? JSON.object().put("NOT_FOUND", true) : null)
                           .execute();
        if (!issue.contains("NOT_FOUND")) {
            return Optional.of(new JiraIssue(this, request, issue));
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
