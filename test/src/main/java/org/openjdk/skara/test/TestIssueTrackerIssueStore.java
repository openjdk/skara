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

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;

import java.util.*;

/**
 * Backing store for TestIssueTrackerIssue. Represents the "server side" state of an Issue.
 */
public class TestIssueTrackerIssueStore extends TestIssueStore {

    private final List<Link> links = new ArrayList<>();
    private final Map<String, JSONValue> properties = new HashMap<>();

    public TestIssueTrackerIssueStore(String id, IssueProject issueProject, HostUser author, String title,
            List<String> body, Map<String, JSONValue> properties) {
        super(id, issueProject, author, title, body);
        if (properties != null) {
            this.properties.putAll(properties);
        }
    }

    public List<Link> links() {
        return links;
    }

    public Map<String, JSONValue> properties() {
        return properties;
    }
}
