/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static org.openjdk.skara.issuetracker.jira.JiraProject.RESOLVED_IN_BUILD;

public class JbsBackport {
    private final RestRequest backportRequest;

    private static URI backportRequest(URI uri) {
        return URIBuilder.base(uri)
                         .setPath("/rest/jbs/1.0/backport/")
                         .build();
    }

    JbsBackport(URI uri, JbsVault vault) {
        if (vault != null) {
            backportRequest = new RestRequest(backportRequest(uri), vault.authId(), (r) -> Arrays.asList("Cookie", vault.getCookie()));
        } else {
            backportRequest = null;
        }
    }

    private IssueTrackerIssue createBackportIssue(IssueTrackerIssue primary, String fixVersion, String defaultSecurity) {
        var finalProperties = new HashMap<>(primary.properties());
        finalProperties.put("issuetype", JSON.of("Backport"));
        finalProperties.put("fixVersion", JSON.of(fixVersion));
        if (!primary.properties().containsKey("security") && defaultSecurity != null) {
            finalProperties.put("security", JSON.of(defaultSecurity));
        }
        finalProperties.remove(RESOLVED_IN_BUILD);

        var backport = primary.project().createIssue(primary.title(), primary.body().lines().collect(Collectors.toList()), finalProperties);

        var backportLink = Link.create(backport, "backported by").build();
        primary.addLink(backportLink);
        return backport;
    }

    public IssueTrackerIssue createBackport(IssueTrackerIssue primary, String fixVersion, String assignee, String defaultSecurity) {
        if (backportRequest == null) {
            if (primary.project().webUrl().toString().contains("openjdk.org")) {
                throw new RuntimeException("Backports on JBS require vault authentication");
            } else {
                return createBackportIssue(primary, fixVersion, defaultSecurity);
            }
        }

        var request = backportRequest.post()
                                     .body("parentIssueKey", primary.id())
                                     .body("fixVersion", fixVersion);
        if (assignee != null) {
            request.body("assignee", assignee);
        }
        if (primary.properties().containsKey("security")) {
            request.body("level", primary.properties().get("security").asString());
        } else if (defaultSecurity != null) {
            request.body("level", defaultSecurity);
        }
        var response = request.execute();
        var issue = primary.project().issue(response.get("key").asString()).orElseThrow();

        // The backport should not have any labels set - if it does, clear them
        var labels = issue.labelNames();
        if (!labels.isEmpty()) {
            issue.setLabels(List.of());
        }
        return issue;
    }
}
