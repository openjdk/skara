/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jbs;

import org.openjdk.skara.issuetracker.Issue;

import java.util.List;
import java.util.Optional;

public class IssueUtil {

    /**
     * Find the closest issue from the provided issue list according to the provided fix version.
     * This method is similar to Backports#findIssue, but this method can handle all the fix versions of the issue
     * instead of only the main fix version and can receive an issue list instead of only the primary issue.
     *
     * If one of the issues has the correct fix version, use it.
     * Else, if one of the issues has a matching <N>-pool-<opt> fix version, use it.
     * Else, if one of the issues has a matching <N>-pool fix version, use it.
     * Else, if one of the issues has a "scratch" fix version, use it.
     * Otherwise, return empty.
     *
     * A "scratch" fixVersion is empty, "tbd.*", or "unknown".
     */
    public static Optional<Issue> findClosestIssue(List<Issue> issueList, JdkVersion fixVersion) {
        var matchingVersionIssue = issueList.stream()
                .filter(issue -> Backports.fixVersions(issue).stream().anyMatch(v -> v.equals(fixVersion.raw())))
                .findFirst();
        if (matchingVersionIssue.isPresent()) {
            return matchingVersionIssue;
        }

        if (fixVersion.opt().isPresent()) {
            var matchingOptPoolVersionIssue = issueList.stream()
                    .filter(issue -> Backports.fixVersions(issue).stream().anyMatch(
                            v -> v.equals(fixVersion.feature() + "-pool-" + fixVersion.opt().get())))
                    .findFirst();
            if (matchingOptPoolVersionIssue.isPresent()) {
                return matchingOptPoolVersionIssue;
            }
        }

        var matchingPoolVersionIssue = issueList.stream()
                .filter(issue -> Backports.fixVersions(issue).stream().anyMatch(v -> v.equals(fixVersion.feature() + "-pool")))
                .findFirst();
        if (matchingPoolVersionIssue.isPresent()) {
            return matchingPoolVersionIssue;
        }

        return issueList.stream()
                .filter(issue -> Backports.fixVersions(issue).stream().noneMatch(v -> !v.startsWith("tbd") && !v.equalsIgnoreCase("unknown")))
                .findFirst();
    }
}
