/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Objects;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.vcs.Hash;

import java.time.ZonedDateTime;
import java.util.Optional;

public class Review {
    private final ZonedDateTime createdAt;
    private final HostUser reviewer;
    private final Verdict verdict;
    private final Hash hash;
    private final String id;
    private final String body;
    private final String targetRef;

    public Review(ZonedDateTime createdAt, HostUser reviewer, Verdict verdict, Hash hash, String id, String body,
            String targetRef) {
        this.createdAt = createdAt;
        this.reviewer = reviewer;
        this.verdict = verdict;
        this.hash = hash;
        this.id = id;
        this.body = body;
        this.targetRef = targetRef;
    }

    public Review withTargetRef(String targetRef) {
        return new Review(createdAt, reviewer, verdict, hash, id, body, targetRef);
    }

    public ZonedDateTime createdAt() {
        return createdAt;
    }

    public HostUser reviewer() {
        return reviewer;
    }

    public Verdict verdict() {
        return verdict;
    }

    /**
     * The hash for the commit for which this review was created. Can be empty if the commit
     * no longer exists.
     */
    public Optional<Hash> hash() {
        return Optional.ofNullable(hash);
    }

    public String id() {
        return id;
    }

    public Optional<String> body() {
        return Optional.ofNullable(body);
    }

    public String targetRef() {
        return targetRef;
    }

    public enum Verdict {
        NONE,
        APPROVED,
        DISAPPROVED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Review review = (Review) o;
        return id == review.id &&
                Objects.equals(createdAt, review.createdAt) &&
                Objects.equals(reviewer, review.reviewer) &&
                verdict == review.verdict &&
                Objects.equals(hash, review.hash) &&
                Objects.equals(body, review.body) &&
                Objects.equals(targetRef, review.targetRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(createdAt, reviewer, verdict, hash, id, body, targetRef);
    }
}
