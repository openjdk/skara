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
package org.openjdk.skara.host.jira;

import org.openjdk.skara.host.*;
import org.openjdk.skara.host.network.*;
import org.openjdk.skara.json.JSON;

import java.net.URI;

public class JiraHost implements Host {
    private final URI uri;
    private final RestRequest request;

    public JiraHost(URI uri) {
        this.uri = uri;

        var baseApi = URIBuilder.base(uri)
                                .setPath("/rest/api/2/")
                                .build();
        request = new RestRequest(baseApi);
    }

    URI getUri() {
        return uri;
    }

    @Override
    public boolean isValid() {
        var version = request.get("serverInfo")
                             .onError(r -> JSON.object().put("invalid", true))
                             .execute();
        return !version.contains("invalid");
    }

    @Override
    public HostedRepository getRepository(String name) {
        throw new RuntimeException("Jira does not support repositories");
    }

    @Override
    public IssueProject getIssueProject(String name) {
        return new JiraProject(this, request, name);
    }

    @Override
    public HostUserDetails getUserDetails(String username) {
        throw new RuntimeException("needs authentication; not implemented yet");
    }

    @Override
    public HostUserDetails getCurrentUserDetails() {
        throw new RuntimeException("needs authentication; not implemented yet");
    }

    @Override
    public boolean supportsReviewBody() {
        return false;
    }
}
