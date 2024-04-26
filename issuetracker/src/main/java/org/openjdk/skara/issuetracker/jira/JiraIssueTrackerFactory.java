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

import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.network.URIBuilder;

import java.net.URI;

public class JiraIssueTrackerFactory implements IssueTrackerFactory {
    @Override
    public String name() {
        return "jira";
    }

    @Override
    public IssueTracker create(URI uri, Credential credential, JSONObject configuration) {
        if (credential == null) {
            return new JiraHost(uri);
        } else {
            if (credential.username().startsWith("https://")) {
                var vaultUrl = URIBuilder.base(credential.username()).build();
                var jiraVault = new JiraVault(vaultUrl, credential.password(), uri);

                if (configuration.contains("visibility")) {
                    return new JiraHost(uri, jiraVault, configuration.get("visibility").asString());
                }
                return new JiraHost(uri, jiraVault);
            } else {
                if (credential.username().isEmpty() && !credential.password().isEmpty()) {
                    // This branch is only used to support test code
                    return createWithPat(uri, credential.password());
                } else {
                    throw new RuntimeException("basic authentication not implemented yet");
                }
            }
        }
    }

    /**
     * Get the issue tracker according to the cookie which is copied from the browser.
     * This method is only used by the manual test code.
     */
    public IssueTracker create(URI uri, String cookie) {
        return new JiraHost(uri, "Cookie", cookie);
    }

    /**
     * Get the issue tracker according to personal access token.
     * This method is only used to support to test code (it is
     * called from a branch in production code that is only
     * used to support test code).
     */
    public IssueTracker createWithPat(URI uri, String pat) {
        return new JiraHost(uri, "Authorization", pat);
    }
}
