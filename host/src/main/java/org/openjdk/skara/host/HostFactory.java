/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.host;

import org.openjdk.skara.host.github.*;
import org.openjdk.skara.host.gitlab.GitLabHost;
import org.openjdk.skara.host.jira.JiraHost;

import java.net.URI;
import java.util.regex.Pattern;

public class HostFactory {
    public static Host createGitHubHost(URI uri, Pattern webUriPattern, String webUriReplacement, String keyFile, String issue, String id) {
        var app = new GitHubApplication(keyFile, issue, id);
        return new GitHubHost(uri, app, webUriPattern, webUriReplacement);
    }

    public static Host createGitHubHost(URI uri, PersonalAccessToken pat) {
        if (pat != null) {
            return new GitHubHost(uri, pat);
        } else {
            return new GitHubHost(uri);
        }
    }

    public static Host createGitLabHost(URI uri, PersonalAccessToken pat) {
        if (pat != null) {
            return new GitLabHost(uri, pat);
        } else {
            return new GitLabHost(uri);
        }
    }

    public static Host createJiraHost(URI uri, PersonalAccessToken pat) {
        if (pat != null) {
            throw new RuntimeException("authentication not implemented yet");
        }
        return new JiraHost(uri);
    }

    public static Host createFromURI(URI uri, PersonalAccessToken pat) throws IllegalArgumentException {
        // Short-circuit
        if (uri.toString().contains("github")) {
            return createGitHubHost(uri, pat);
        } else if (uri.toString().contains("gitlab")) {
            return createGitLabHost(uri, pat);
        }

        try {
            var gitLabHost = createGitLabHost(uri, pat);
            if (gitLabHost.isValid()) {
                return gitLabHost;
            }
        } catch (RuntimeException e) {
            try {
                var gitHubHost = createGitHubHost(uri, pat);
                if (gitHubHost.isValid()) {
                    return gitHubHost;
                }
            } catch (RuntimeException ignored) {
            }
        }

        throw new IllegalArgumentException("Unable to detect host type from URI: " + uri);
    }
}
