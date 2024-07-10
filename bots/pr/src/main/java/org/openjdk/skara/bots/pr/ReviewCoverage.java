/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.forge.Review;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.Repository;

public class ReviewCoverage {

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private final boolean useStaleReviews;
    private final boolean acceptSimpleMerges;
    private final Repository repo;
    private final PullRequest pr;
    private final Map<Review, Boolean> cachedCoverage = new HashMap<>();
    private Hash cachedTargetHash;

    public ReviewCoverage(boolean useStaleReviews,
                          boolean acceptSimpleMerges,
                          Repository repo,
                          PullRequest pr) {
        this.useStaleReviews = useStaleReviews;
        this.acceptSimpleMerges = acceptSimpleMerges;
        this.repo = repo;
        this.pr = pr;
    }

    public boolean covers(Review review) {
        return cachedCoverage.computeIfAbsent(review, this::covers0);
    }

    private boolean covers0(Review review) {
        var r = review.hash();
        // Reviews without a hash are never valid as they referred to no longer
        // existing commits.
        if (r.isEmpty() || review.verdict() != Review.Verdict.APPROVED
                || !review.targetRef().equals(pr.targetRef())) {
            return false;
        }
        if (useStaleReviews || r.get().equals(pr.headHash())) {
            return true;
        }
        if (!acceptSimpleMerges) {
            return false;
        }
        boolean seenAtLeastOneCommit = false;
        try {
            try (var commits = repo.commits(List.of(pr.headHash()), List.of(r.get(), targetHash()))) {
                for (var c : commits) {
                    seenAtLeastOneCommit = true;
                    if (!c.isMerge() || c.numParents() != 2) {
                        return false;
                    }
                    // from https://git-scm.com/book/en/v2/Git-Tools-Revision-Selection
                    // we expect that ^1 has to belong to the PR and ^2 to the target
                    // branch; the former seems obvious and enforced by Git, while
                    // the latter should be checked
                    var secondParent = c.parents().get(1);
                    if (!repo.isAncestor(secondParent, targetHash())) {
                        return false;
                    }
                    if (!repo.isRemergeDiffEmpty(c.hash())) {
                        return false;
                    }
                }
            }
        } catch (IOException e) {
            log.log(Level.FINE, "Error while looking for simple merges: " + pr.repository() + ", " + pr.id(), e);
            return false;
        }
        if (seenAtLeastOneCommit) {
            log.finest("Saved a merge from review: " + pr.repository() + ", " + pr.id());
        }
        return seenAtLeastOneCommit;
    }

    private Hash targetHash() throws IOException {
        if (cachedTargetHash == null) {
            cachedTargetHash = PullRequestUtils.targetHash(repo);
        } else {
            // main assumption for caching targetHash
            if (ReviewCoverage.class.desiredAssertionStatus()) {
                var latest = PullRequestUtils.targetHash(repo);
                assert cachedTargetHash.equals(latest) :
                        cachedTargetHash + " != " + latest;
            }
        }
        return cachedTargetHash;
    }
}
