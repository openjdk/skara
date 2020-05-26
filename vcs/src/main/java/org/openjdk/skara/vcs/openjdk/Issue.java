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
package org.openjdk.skara.vcs.openjdk;

import java.util.*;
import java.util.regex.Pattern;

public class Issue {
    private final String project;
    private final String shortId;
    private final String description;

    private final static Pattern relaxedIssueParsePattern = Pattern.compile("^((?:[A-Z][A-Z0-9]+-)?[0-9]+)[^\\p{Alnum}]*\\s(\\S.*)$");

    public Issue(String id, String description) {
        if (id.contains("-")) {
            var idSplit = id.split("-", 2);
            project = idSplit[0];
            this.shortId = idSplit[1];
        } else {
            project = null;
            this.shortId = id;
        }

        this.description = description;
    }

    public Optional<String> project() {
        return Optional.ofNullable(project);
    }

    public String id() {
        return (project != null ? project + "-" : "") + shortId;
    }

    public String shortId() {
        return shortId;
    }

    public String description() {
        return description;
    }

    public static Optional<Issue> fromString(String s) {
        var m = CommitMessageSyntax.ISSUE_PATTERN.matcher(s);
        if (m.matches()) {
            var id = m.group(1);
            var desc = m.group(2);
            return Optional.of(new Issue(id, desc));
        }
        return Optional.empty();
    }

    public static Optional<Issue> fromStringRelaxed(String s) {
        var relaxedIssueParseMatcher = relaxedIssueParsePattern.matcher(s.trim());
        if (relaxedIssueParseMatcher.matches()) {
            return Optional.of(new Issue(relaxedIssueParseMatcher.group(1), relaxedIssueParseMatcher.group(2)));
        }

        return Optional.empty();
    }

    @Override
    public String toString() {
        return id() + ": " + description;
    }

    public String toShortString() {
        return shortId + ": " + description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Issue issue = (Issue) o;
        return Objects.equals(project, issue.project) &&
                shortId.equals(issue.shortId) &&
                description.equals(issue.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project, shortId, description);
    }
}
