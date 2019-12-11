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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.PullRequest;

import java.util.*;

public class PullRequestIssues {
    private final String prId;
    private final Set<String> issueIds;

    PullRequestIssues(PullRequest pr, Set<String> issueIds) {
        this.prId = pr.repository().id() + ":" + pr.id();
        this.issueIds = issueIds;
    }

    PullRequestIssues(String prId, Set<String> issueIds) {
        this.prId = prId;
        this.issueIds = issueIds;
    }

    public String prId() {
        return prId;
    }

    public Set<String> issueIds() {
        return issueIds;
    }

    @Override
    public String toString() {
        return "PullRequestIssues{" +
                "prId='" + prId + '\'' +
                ", issueIds=" + issueIds +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PullRequestIssues that = (PullRequestIssues) o;
        return prId.equals(that.prId) &&
                issueIds.equals(that.issueIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prId, issueIds);
    }
}
