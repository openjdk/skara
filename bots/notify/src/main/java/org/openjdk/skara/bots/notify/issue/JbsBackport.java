/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

public class JbsBackport {
    private final IssueTracker.CustomEndpoint backportEndpoint;

    JbsBackport(IssueTracker tracker) {
        this.backportEndpoint = tracker.lookupCustomEndpoint("/rest/jbs/1.0/backport/").orElseThrow(() ->
            new IllegalArgumentException("Issue tracker does not support backport endpoint")
        );
    }

    IssueTrackerIssue createBackport(IssueTrackerIssue primary, String fixVersion, String assignee, String defaultSecurity) {
        var body = JSON.object()
                       .put("parentIssueKey", primary.id())
                       .put("fixVersion", fixVersion);

        if (assignee != null) {
            body = body.put("assignee", assignee);
        }

        if (primary.properties().containsKey("security")) {
            body = body.put("level", primary.properties().get("security").asString());
        } else if (defaultSecurity != null) {
            body = body.put("level", defaultSecurity);
        }

        var response = backportEndpoint.post()
                                       .body(body)
                                       .execute();
        var issue = primary.project().issue(response.get("key").asString()).orElseThrow();

        // The backport should not have any labels set - if it does, clear them
        var labels = issue.labelNames();
        if (!labels.isEmpty()) {
            issue.setLabels(List.of());
        }

        return issue;
    }
}
