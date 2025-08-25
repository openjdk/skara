/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.vcs.Hash;

import java.util.*;

/**
 * Backing store for TestPullRequest. Represents the "server side" state of a
 * pull request.
 */
public class TestPullRequestStore extends TestIssueStore {
    private TestHostedRepository sourceRepository;
    private String targetRef;
    private String sourceRef;
    private final List<ReviewComment> reviewComments = new ArrayList<>();
    private final Set<Check> checks = new HashSet<>();
    private final List<Review> reviews = new ArrayList<>();
    private boolean draft;
    private ZonedDateTime lastForcePushTime;
    private Hash headHash;
    private final List<ReferenceChange> targetReferenceChanges = new ArrayList<>();

    private ZonedDateTime lastMarkedAsReadyTime;
    private ZonedDateTime lastMarkedAsDraftTime;
    private boolean returnCompleteDiff;

    public TestPullRequestStore(String id, HostUser author, String title, List<String> body,
            TestHostedRepository sourceRepository, String targetRef, String sourceRef, boolean draft) {
        super(id, null, author, title, body);
        this.sourceRepository = sourceRepository;
        this.targetRef = targetRef;
        this.sourceRef = sourceRef;
        this.draft = draft;
        if (draft) {
            lastMarkedAsDraftTime = ZonedDateTime.now();
        } else {
            lastMarkedAsReadyTime = ZonedDateTime.now();
        }
        this.returnCompleteDiff = true;
    }

    public TestHostedRepository sourceRepository() {
        return sourceRepository;
    }

    /**
     * Gets the current headHash from the underlying repository. If it has
     * changed since last time, updates the lastUpdated timestamp.
     */
    public Hash headHash() {
        try {
            var headHash = sourceRepository.localRepository().resolve(sourceRef);
            if (headHash.isPresent() && !headHash.get().equals(this.headHash)) {
                this.headHash = headHash.get();
                setLastUpdate(ZonedDateTime.now());
                setLastTouchedTime(ZonedDateTime.now());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this.headHash;
    }

    public String targetRef() {
        return targetRef;
    }

    public String sourceRef() {
        return sourceRef;
    }

    public List<ReviewComment> reviewComments() {
        return reviewComments;
    }

    public Set<Check> checks() {
        return checks;
    }

    public List<Review> reviews() {
        return reviews;
    }

    public boolean draft() {
        return draft;
    }

    public ZonedDateTime lastForcePushTime() {
        return lastForcePushTime;
    }

    public boolean returnCompleteDiff(){
        return returnCompleteDiff;
    }

    public void setSourceRepository(TestHostedRepository sourceRepository) {
        this.sourceRepository = sourceRepository;
    }

    public void setTargetRef(String targetRef) {
        targetReferenceChanges.add(new ReferenceChange(this.targetRef, targetRef, ZonedDateTime.now()));
        this.targetRef = targetRef;
    }

    public List<ReferenceChange> targetRefChanges() {
        return targetReferenceChanges;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
        setLastUpdate(ZonedDateTime.now());
        setLastTouchedTime(ZonedDateTime.now());
        if (draft) {
            lastMarkedAsDraftTime = ZonedDateTime.now();
        } else {
            lastMarkedAsReadyTime = ZonedDateTime.now();
        }
    }

    public void setLastForcePushTime(ZonedDateTime lastForcePushTime) {
        this.lastForcePushTime = lastForcePushTime;
    }

    public ZonedDateTime lastMarkedAsReadyTime() {
        return lastMarkedAsReadyTime;
    }

    public ZonedDateTime lastMarkedAsDraftTime() {
        return lastMarkedAsDraftTime;
    }

    public void setReturnCompleteDiff(boolean returnCompleteDiff) {
        this.returnCompleteDiff = returnCompleteDiff;
    }
}
