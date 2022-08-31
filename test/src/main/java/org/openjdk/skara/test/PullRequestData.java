/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.time.ZonedDateTime;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.Hash;

import java.util.*;

class PullRequestData extends IssueData {
    Hash headHash;
    String targetRef;
    final List<ReviewComment> reviewComments = new ArrayList<>();
    final Set<Check> checks = new HashSet<>();
    final List<Review> reviews = new ArrayList<>();
    boolean draft;
    ZonedDateTime lastForcePushTime;

    PullRequestData() {
    }

    @Override
    PullRequestData copy() {
        var copy = new PullRequestData();
        copyTo(copy);
        return copy;
    }

    protected void copyTo(PullRequestData copy) {
        super.copyTo(copy);
        copy.headHash = headHash;
        copy.targetRef = targetRef;
        copy.reviewComments.addAll(reviewComments);
        copy.checks.addAll(checks);
        copy.reviews.addAll(reviews);
        copy.draft = draft;
        copy.lastForcePushTime = lastForcePushTime;
    }

    /**
     * This equals method is tailored for PullRequestPollerTests, where it
     * simulates the parts of a PullRequest which are included in the main
     * object and not accessed by sub queries. That means reviews and checks
     * are excluded.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) return false;
        PullRequestData that = (PullRequestData) o;
        return draft == that.draft &&
                Objects.equals(headHash, that.headHash) &&
                Objects.equals(targetRef, that.targetRef) &&
                Objects.equals(lastForcePushTime, that.lastForcePushTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), headHash, targetRef, draft, lastForcePushTime);
    }
}
