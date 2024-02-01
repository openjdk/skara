/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.json.JSONValue;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;

public interface IssueProject {
    IssueTracker issueTracker();
    URI webUrl();
    IssueTrackerIssue createIssue(String title, List<String> body, Map<String, JSONValue> properties);
    Optional<IssueTrackerIssue> issue(String id);
    List<IssueTrackerIssue> issues();

    /**
     * Find all issues that have been updated after or on the given time, with
     * a resolution given by Host::timeStampQueryPrecision.
     */
    List<IssueTrackerIssue> issues(ZonedDateTime updatedAfter);
    String name();

    /**
     * Get the JEP issue according to the JEP ID.
     * @param jepId JEP ID
     * @return the corresponding issue
     */
    Optional<IssueTrackerIssue> jepIssue(String jepId);

    /**
     * Find all issues of CSR type updated after or on the given time, with
     * a resolution given by Host::timeStampQueryPrecision.
     * @param updatedAfter Timestamp
     * @return List of issues found
     */
    List<IssueTrackerIssue> csrIssues(ZonedDateTime updatedAfter);

    /**
     * Find the last updated issue.
     * @return The last updated issue, or empty if none exist
     */
    Optional<IssueTrackerIssue> lastUpdatedIssue();
}
