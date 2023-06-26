/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.issuetracker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openjdk.skara.json.JSONValue;

/**
 * Extension of the Issue interface with additional functionality present in a bug
 * tracking system. Extracted to an interface to facilitate test implementations.
 */
public interface IssueTrackerIssue extends Issue {
    List<Link> links();

    void addLink(Link link);

    void removeLink(Link link);

    Map<String, JSONValue> properties();

    void setProperty(String name, JSONValue value);

    void removeProperty(String name);

    /**
     * @return The raw status name string from the issue tracker
     */
    String status();

    /**
     * @return The raw resolution name string from the issue tracker, or empty
     * if it hasn't been set.
     */
    Optional<String> resolution();
}
