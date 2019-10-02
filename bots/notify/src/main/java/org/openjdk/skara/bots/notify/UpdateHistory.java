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

import org.openjdk.skara.storage.*;
import org.openjdk.skara.vcs.*;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

class UpdateHistory {

    private final Storage<Tag> tagStorage;
    private final Storage<ResolvedBranch> branchStorage;

    private Map<Branch, Hash> branches;
    private Set<Tag> tags;

    private Set<ResolvedBranch> loadBranches(String current) {
        return current.lines()
                      .map(line -> line.split(" "))
                      .map(entry -> new ResolvedBranch(new Branch(entry[0]), new Hash(entry[1])))
                      .collect(Collectors.toSet());
    }

    private String serializeBranches(Collection<ResolvedBranch> added, Set<ResolvedBranch> existing) {
        var updatedBranches = existing.stream()
                                      .collect(Collectors.toMap(ResolvedBranch::branch,
                                                                ResolvedBranch::hash));
        added.forEach(a -> updatedBranches.put(a.branch(), a.hash()));
        return updatedBranches.entrySet().stream()
                              .map(entry -> entry.getKey().toString() + " " + entry.getValue().toString())
                              .sorted()
                              .collect(Collectors.joining("\n"));
    }

    private Set<Tag> loadTags(String current) {
        return current.lines()
                      .map(Tag::new)
                      .collect(Collectors.toSet());
    }

    private String serializeTags(Collection<Tag> added, Set<Tag> existing) {
        return Stream.concat(existing.stream(),
                             added.stream())
                     .map(Tag::toString)
                     .sorted()
                     .collect(Collectors.joining("\n"));
    }

    private Set<Tag> currentTags() {
        return tagStorage.current();
    }

    private Map<Branch, Hash> currentBranchHashes() {
        return branchStorage.current().stream()
                .collect(Collectors.toMap(ResolvedBranch::branch, ResolvedBranch::hash));
    }

    private UpdateHistory(StorageBuilder<Tag> tagStorageBuilder, Path tagLocation, StorageBuilder<ResolvedBranch> branchStorageBuilder, Path branchLocation) {
        this.tagStorage = tagStorageBuilder
                .serializer(this::serializeTags)
                .deserializer(this::loadTags)
                .materialize(tagLocation);

        this.branchStorage = branchStorageBuilder
                .serializer(this::serializeBranches)
                .deserializer(this::loadBranches)
                .materialize(branchLocation);

        tags = currentTags();
        branches = currentBranchHashes();
    }

    static UpdateHistory create(StorageBuilder<Tag> tagStorageBuilder, Path tagLocation, StorageBuilder<ResolvedBranch> branchStorageBuilder, Path branchLocation) {
        return new UpdateHistory(tagStorageBuilder, tagLocation, branchStorageBuilder, branchLocation);
    }

    void addTags(Collection<Tag> addedTags) {
        tagStorage.put(addedTags);
        var newTags = currentTags();

        if (addedTags != null) {
            for (var existingTag : addedTags) {
                if (!newTags.contains(existingTag)) {
                    throw new RuntimeException("Tag '" + existingTag + "' has been removed");
                }
            }
        }

        tags = currentTags();
    }

    boolean hasTag(Tag tag) {
        return tags.contains(tag);
    }

    void setBranchHash(Branch branch, Hash hash) {
        var entry = new ResolvedBranch(branch, hash);

        branchStorage.put(entry);
        var newBranchHashes = currentBranchHashes();

        // Sanity check
        if (branches != null) {
            for (var existingBranch : branches.keySet()) {
                if (!newBranchHashes.containsKey(existingBranch)) {
                    throw new RuntimeException("Hash information for branch '" + existingBranch + "' is missing");
                }
            }
        }
        branches = newBranchHashes;
    }

    Optional<Hash> branchHash(Branch branch) {
        var hash = branches.get(branch);
        return Optional.ofNullable(hash);
    }
}
