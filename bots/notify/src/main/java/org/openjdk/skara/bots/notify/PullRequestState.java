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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.vcs.Hash;

import java.util.*;

class PullRequestState {
    private final String prId;
    private final Set<String> issueIds;
    private final Hash commitId;
    private final Hash head;
    private final Issue.State state;
    private final String targetBranch;

    PullRequestState(PullRequest pr, Set<String> issueIds, Hash commitId, Hash head, Issue.State state) {
        this.prId = pr.repository().id() + ":" + pr.id();
        this.issueIds = issueIds;
        this.commitId = commitId;
        this.head = head;
        this.state = state;
        this.targetBranch = pr.targetRef();
    }

    PullRequestState(String prId, Set<String> issueIds, Hash commitId, Hash head, Issue.State state, String targetBranch) {
        this.prId = prId;
        this.issueIds = issueIds;
        this.commitId = commitId;
        this.head = head;
        this.state = state;
        this.targetBranch = targetBranch;
    }

    public String prId() {
        return prId;
    }

    public Set<String> issueIds() {
        return issueIds;
    }

    public Optional<Hash> commitId() {
        return Optional.ofNullable(commitId);
    }

    public Hash head() {
        return head;
    }

    public Issue.State state() {
        return state;
    }

    public String targetBranch() {
        return targetBranch;
    }

    @Override
    public String toString() {
        return "PullRequestState{" +
                "prId='" + prId + '\'' +
                ", issueIds=" + issueIds +
                ", commitId=" + commitId +
                ", head=" + head +
                ", state=" + state +
                ", targetBranch=" + targetBranch +
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
        var that = (PullRequestState) o;
        return prId.equals(that.prId) &&
                issueIds.equals(that.issueIds) &&
                Objects.equals(commitId, that.commitId) &&
                Objects.equals(head, that.head) &&
                Objects.equals(state, that.state) &&
                Objects.equals(targetBranch, that.targetBranch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prId, issueIds, commitId, head, targetBranch);
    }
}
