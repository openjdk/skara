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
package org.openjdk.skara.test;

import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.network.URIBuilder;

import java.net.URI;
import java.util.*;

public class TestIssueProject implements IssueProject {
    private final String projectName;
    private final TestHost host;

    String projectName() {
        return projectName;
    }

    @Override
    public IssueTracker issueTracker() {
        return host;
    }

    @Override
    public URI webUrl() {
        return URIBuilder.base("http://localhost/project/" + projectName).build();
    }

    public TestIssueProject(TestHost host, String projectName) {
        this.host = host;
        this.projectName = projectName;
    }

    @Override
    public Issue createIssue(String title, List<String> body, Map<String, JSONValue> properties) {
        return host.createIssue(this, title, body, properties);
    }

    @Override
    public Optional<Issue> issue(String id) {
        if (id.indexOf('-') < 0) {
            id = projectName.toUpperCase() + "-" + id;
        }

        return Optional.ofNullable(host.getIssue(this, id));
    }

    @Override
    public List<Issue> issues() {
        return new ArrayList<>(host.getIssues(this));
    }

    @Override
    public String name() {
        return projectName.toUpperCase();
    }
}
