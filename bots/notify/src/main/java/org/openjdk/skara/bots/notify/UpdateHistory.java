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
import java.util.function.Function;
import java.util.stream.*;

class UpdateHistory {
    private final Storage<Tag> tagStorage;
    private final Storage<ResolvedBranch> branchStorage;

    private Map<String, Hash> branchHashes;
    private Set<Tag> tags;

    private List<ResolvedBranch> parseSerializedEntry(String entry) {
        var parts = entry.split(" ");
        if (parts.length == 2) {
            // Transform legacy entry
            var issueEntry = new ResolvedBranch(new Branch(parts[0]), "issue", new Hash(parts[1]));
            var mlEntry = new ResolvedBranch(new Branch(parts[0]), "ml", new Hash(parts[1]));
            return List.of(issueEntry, mlEntry);
        }
        return List.of(new ResolvedBranch(new Branch(parts[0]), parts[1], new Hash(parts[2])));
    }

    private Set<ResolvedBranch> loadBranches(String current) {
        return current.lines()
                      .flatMap(line -> parseSerializedEntry(line).stream())
                      .collect(Collectors.toSet());
    }

    private String serializeEntry(ResolvedBranch entry) {
        return entry.branch().toString() + " " + entry.updater() + " " + entry.hash().toString();
    }

    private String serializeBranches(Collection<ResolvedBranch> added, Set<ResolvedBranch> existing) {
        var updatedBranches = existing.stream()
                                      .collect(Collectors.toMap(entry -> entry.branch().toString() + ":" + entry.updater(),
                                                                Function.identity()));
        added.forEach(a -> updatedBranches.put(a.branch().toString() + ":" + a.updater(), a));
        return updatedBranches.values().stream()
                              .map(this::serializeEntry)
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

    private Map<String, Hash> currentBranchHashes() {
        return branchStorage.current().stream()
                .collect(Collectors.toMap(rb -> rb.branch().toString() + " " + rb.updater(), ResolvedBranch::hash));
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
        branchHashes = currentBranchHashes();
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

    void setBranchHash(Branch branch, String updater, Hash hash) {
        var entry = new ResolvedBranch(branch, updater, hash);

        branchStorage.put(entry);
        var newBranchHashes = currentBranchHashes();

        // Sanity check
        if (branchHashes != null) {
            for (var existingBranch : branchHashes.keySet()) {
                if (!newBranchHashes.containsKey(existingBranch)) {
                    throw new RuntimeException("Hash information for branch '" + existingBranch + "' is missing");
                }
            }
        }
        branchHashes = newBranchHashes;
    }

    Optional<Hash> branchHash(Branch branch, String updater) {
        var entry = branchHashes.get(branch.toString() + " " + updater);
        return Optional.ofNullable(entry);
    }

    boolean isEmpty() {
        return branchHashes.isEmpty();
    }
}
