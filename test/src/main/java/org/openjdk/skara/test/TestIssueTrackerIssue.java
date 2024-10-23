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
package org.openjdk.skara.test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.IssueTrackerIssue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.json.JSONValue;

/**
 * TestIssueTrackerIssue is the object returned from a TestHost when queried for
 * issues. It's backed by a TestIssueStore, which tracks the "server side" state
 * of the issue. A TestIssue object contains a snapshot of the server side state
 * for all data directly related to the issue. What data is snapshotted and what
 * is fetched on request should be the same as for JiraIssue.
 */
public class TestIssueTrackerIssue extends TestIssue implements IssueTrackerIssue {

    public TestIssueTrackerIssue(TestIssueTrackerIssueStore store, HostUser user) {
        super(store, user);
    }

    /**
     * Gives test code direct access to the backing store object to be able to
     * inspect and manipulate state directly.
     */
    public TestIssueTrackerIssueStore store() {
        return (TestIssueTrackerIssueStore) super.store();
    }
    private static final List<String> VALID_RESOLUTIONS = List.of("Fixed", "Delivered");

    @Override
    public void setState(State state) {
        super.setState(state);
        if (state == State.RESOLVED || state == State.CLOSED) {
            store().properties().put("resolution", JSON.object().put("name", JSON.of("Fixed")));
        }
    }

    @Override
    public String status() {
        return store().properties().get("status").get("name").asString();
    }

    @Override
    public Optional<String> resolution() {
        var resolution = store().properties().get("resolution");
        if (resolution != null && !resolution.isNull()) {
            var name = resolution.get("name");
            if (name != null && !name.isNull()) {
                return Optional.of(resolution.get("name").asString());
            }
        }
        return Optional.empty();
    }

    /**
     * This implementation mimics the JiraIssue definition of isFixed and is
     * needed to test handling of backports.
     */
    @Override
    public boolean isFixed() {
        if (super.isFixed()) {
            return resolution().map(VALID_RESOLUTIONS::contains).orElse(Boolean.FALSE);
        }
        return false;
    }

    /**
     * When links are returned, they need to contain fresh snapshots of any TestIssue.
     */
    @Override
    public List<Link> links() {
        return store().links().stream()
                .map(this::updateLinkIssue)
                .toList();
    }

    private Link updateLinkIssue(Link link) {
        if (link.issue().isPresent()) {
            var issue = (TestIssueTrackerIssue) link.issue().get();
            return Link.create(issue.copy(), link.relationship().orElseThrow()).build();
        } else {
            return link;
        }
    }

    protected TestIssueTrackerIssue copy() {
        return new TestIssueTrackerIssue(store(), user);
    }

    @Override
    public void addLink(Link link) {
        if (link.uri().isPresent()) {
            removeLink(link);
            store().links().add(link);
        } else if (link.issue().isPresent()) {
            var existing = store().links().stream()
                    .filter(l -> l.issue().isPresent() && l.issue().get().id().equals(link.issue().orElseThrow().id()))
                    .findAny();
            existing.ifPresent(store().links()::remove);
            store().links().add(link);
            if (existing.isEmpty()) {
                var map = Map.of("backported by", "backport of", "backport of", "backported by",
                        "csr for", "csr of", "csr of", "csr for",
                        "blocks", "is blocked by", "is blocked by", "blocks",
                        "clones", "is cloned by", "is cloned by", "clones");
                var reverseRelationship = map.getOrDefault(link.relationship().orElseThrow(), link.relationship().orElseThrow());
                var reverse = Link.create(this, reverseRelationship).build();
                link.issue().get().addLink(reverse);
            }
        } else {
            throw new IllegalArgumentException("Can't add unknown link type: " + link);
        }
        store().setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public void removeLink(Link link) {
        if (link.uri().isPresent()) {
            store().links().removeIf(l -> l.uri().equals(link.uri()));
        } else if (link.issue().isPresent()) {
            var existing = store().links().stream()
                    .filter(l -> l.issue().orElseThrow().id().equals(link.issue().orElseThrow().id()))
                    .findAny();
            if (existing.isPresent()) {
                store().links().remove(existing.get());
                var reverse = Link.create(this, "").build();
                link.issue().get().removeLink(reverse);
            }
        } else {
            throw new IllegalArgumentException("Can't remove unknown link type: " + link);
        }
        store().setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public Map<String, JSONValue> properties() {
        return store().properties();
    }

    @Override
    public void setProperty(String name, JSONValue value) {
        store().properties().put(name, value);
        store().setLastUpdate(ZonedDateTime.now());
    }

    @Override
    public void removeProperty(String name) {
        store().properties().remove(name);
        store().setLastUpdate(ZonedDateTime.now());
    }
}
