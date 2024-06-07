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
package org.openjdk.skara.forge;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PullRequestTests {

    @Test
    void calculateReviewTargetRefsSimple() {
        assertEquals(List.of(), PullRequest.calculateReviewTargetRefs(List.of(), List.of()));
        var review1 = newReview(ZonedDateTime.now(), "1", "master");
        assertEquals(List.of(review1), PullRequest.calculateReviewTargetRefs(List.of(review1), List.of()));
    }

    @Test
    void calculateReviewTargetRefs2Changes() {
        var now = ZonedDateTime.now();

        var refChange1 = new ReferenceChange("first", "second", now.minus(Duration.ofMinutes(4)));
        var refChange2 = new ReferenceChange("second", "third", now.minus(Duration.ofMinutes(2)));

        var review1 = newReview(now.minus(Duration.ofMinutes(5)), "1", "third");
        var review2 = newReview(now.minus(Duration.ofMinutes(3)), "2", "third");
        var review3 = newReview(now.minus(Duration.ofMinutes(1)), "3", "third");

        var reviews = PullRequest.calculateReviewTargetRefs(List.of(review1, review2, review3), List.of(refChange2, refChange1));

        assertEquals(3, reviews.size());
        assertEquals("first", reviews.get(0).targetRef());
        assertEquals("second", reviews.get(1).targetRef());
        assertEquals("third", reviews.get(2).targetRef());
    }

    @Test
    void calculateReviewTargetRefsPreIntegrationBranch() {
        var now = ZonedDateTime.now();

        var refChange1 = new ReferenceChange("first", "pr/4711", now.minus(Duration.ofMinutes(4)));
        var refChange2 = new ReferenceChange("pr/4711", "third", now.minus(Duration.ofMinutes(2)));

        var review1 = newReview(now.minus(Duration.ofMinutes(5)), "1", "");
        var review2 = newReview(now.minus(Duration.ofMinutes(3)), "2", "");
        var review3 = newReview(now.minus(Duration.ofMinutes(1)), "3", "third");

        var reviews = PullRequest.calculateReviewTargetRefs(List.of(review1, review2, review3), List.of(refChange1, refChange2));

        assertEquals(3, reviews.size());
        assertEquals("first", reviews.get(0).targetRef());
        assertEquals("third", reviews.get(1).targetRef());
        assertEquals("third", reviews.get(2).targetRef());
    }

    @Test
    void calculateReviewTargetRefsPreIntegrationBranchLast() {
        var now = ZonedDateTime.now();

        var refChange1 = new ReferenceChange("first", "pr/4711", now.minus(Duration.ofMinutes(4)));
        var refChange2 = new ReferenceChange("pr/4711", "pr/4712", now.minus(Duration.ofMinutes(2)));

        var review1 = newReview(now.minus(Duration.ofMinutes(5)), "1", "");
        var review2 = newReview(now.minus(Duration.ofMinutes(3)), "2", "foo");
        var review3 = newReview(now.minus(Duration.ofMinutes(1)), "3", "pr/4712");

        var reviews = PullRequest.calculateReviewTargetRefs(List.of(review1, review2, review3), List.of(refChange1, refChange2));

        assertEquals(3, reviews.size());
        assertEquals("first", reviews.get(0).targetRef());
        assertEquals("pr/4712", reviews.get(1).targetRef());
        assertEquals("pr/4712", reviews.get(2).targetRef());
    }

    /**
     * Creates a new review with just the relevant fields.
     */
    private Review newReview(ZonedDateTime createdAt, String id, String targetRef) {
        return new Review(createdAt, null, Review.Verdict.APPROVED, null, id, null, targetRef);
    }
}
