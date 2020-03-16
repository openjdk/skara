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
    private final Storage<UpdatedTag> tagStorage;
    private final Storage<UpdatedBranch> branchStorage;

    private Map<String, Hash> branchHashes;
    private Map<String, Boolean> tagRetries;

    private List<UpdatedBranch> parseSerializedBranch(String entry) {
        var parts = entry.split(" ");
        if (parts.length == 2) {
            // Transform legacy entry
            var issueEntry = new UpdatedBranch(new Branch(parts[0]), "issue", new Hash(parts[1]));
            var mlEntry = new UpdatedBranch(new Branch(parts[0]), "ml", new Hash(parts[1]));
            return List.of(issueEntry, mlEntry);
        }
        return List.of(new UpdatedBranch(new Branch(parts[0]), parts[1], new Hash(parts[2])));
    }

    private Set<UpdatedBranch> loadBranches(String current) {
        return current.lines()
                      .flatMap(line -> parseSerializedBranch(line).stream())
                      .collect(Collectors.toSet());
    }

    private String serializeBranch(UpdatedBranch entry) {
        return entry.branch().toString() + " " + entry.updater() + " " + entry.hash().toString();
    }

    private String serializeBranches(Collection<UpdatedBranch> added, Set<UpdatedBranch> existing) {
        var updatedBranches = existing.stream()
                                      .collect(Collectors.toMap(entry -> entry.branch().toString() + ":" + entry.updater(),
                                                                Function.identity()));
        added.forEach(a -> updatedBranches.put(a.branch().toString() + ":" + a.updater(), a));
        return updatedBranches.values().stream()
                              .map(this::serializeBranch)
                              .sorted()
                              .collect(Collectors.joining("\n"));
    }

    private List<UpdatedTag> parseSerializedTag(String entry) {
        var parts = entry.split(" ");
        if (parts.length == 1) {
            // Transform legacy entry
            var issueEntry = new UpdatedTag(new Tag(entry), "issue", false);
            var mlEntry = new UpdatedTag(new Tag(entry), "ml", false);
            return List.of(issueEntry, mlEntry);
        }
        return List.of(new UpdatedTag(new Tag(parts[0]), parts[1], parts[2].equals("retry")));
    }

    private Set<UpdatedTag> loadTags(String current) {
        return current.lines()
                      .flatMap(line -> parseSerializedTag(line).stream())
                      .collect(Collectors.toSet());
    }

    private String serializeTag(UpdatedTag entry) {
        return entry.tag().toString() + " " + entry.updater() + " " + (entry.shouldRetry() ? "retry" : "done");
    }

    private String serializeTags(Collection<UpdatedTag> added, Set<UpdatedTag> existing) {
        var updatedTags = existing.stream()
                                  .collect(Collectors.toMap(entry -> entry.tag().toString() + ":" + entry.updater(),
                                                            Function.identity()));
        added.forEach(a -> updatedTags.put(a.tag().toString() + ":" + a.updater(), a));
        return updatedTags.values().stream()
                          .map(this::serializeTag)
                          .sorted()
                          .collect(Collectors.joining("\n"));
    }

    private Map<String, Hash> currentBranchHashes() {
        return branchStorage.current().stream()
                            .collect(Collectors.toMap(rb -> rb.branch().toString() + " " + rb.updater(), UpdatedBranch::hash));
    }

    private Map<String, Boolean> currentTags() {
        return tagStorage.current().stream()
                         .collect(Collectors.toMap(u -> u.tag().toString() + " " + u.updater(), UpdatedTag::shouldRetry));
    }

    private UpdateHistory(StorageBuilder<UpdatedTag> tagStorageBuilder, Path tagLocation, StorageBuilder<UpdatedBranch> branchStorageBuilder, Path branchLocation) {
        this.tagStorage = tagStorageBuilder
                .serializer(this::serializeTags)
                .deserializer(this::loadTags)
                .materialize(tagLocation);

        this.branchStorage = branchStorageBuilder
                .serializer(this::serializeBranches)
                .deserializer(this::loadBranches)
                .materialize(branchLocation);

        tagRetries = currentTags();
        branchHashes = currentBranchHashes();
    }

    static UpdateHistory create(StorageBuilder<UpdatedTag> tagStorageBuilder, Path tagLocation, StorageBuilder<UpdatedBranch> branchStorageBuilder, Path branchLocation) {
        return new UpdateHistory(tagStorageBuilder, tagLocation, branchStorageBuilder, branchLocation);
    }

    void addTags(Collection<Tag> addedTags, String updater) {
        var newEntries = addedTags.stream()
                                  .map(t -> new UpdatedTag(t, updater, false))
                                  .collect(Collectors.toSet());
        tagStorage.put(newEntries);
        tagRetries = currentTags();
    }

    void retryTagUpdate(Tag tagToRetry, String updater) {
        var entry = new UpdatedTag(tagToRetry, updater, true);
        tagStorage.put(List.of(entry));
        tagRetries = currentTags();
    }

    boolean hasTag(Tag tag, String updater) {
        return tagRetries.containsKey(tag.toString() + " " + updater);
    }

    boolean shouldRetryTagUpdate(Tag tag, String updater) {
        return tagRetries.getOrDefault(tag.toString() + " " + updater, false);
    }

    void setBranchHash(Branch branch, String updater, Hash hash) {
        var entry = new UpdatedBranch(branch, updater, hash);

        branchStorage.put(entry);
        branchHashes = currentBranchHashes();
    }

    Optional<Hash> branchHash(Branch branch, String updater) {
        var entry = branchHashes.get(branch.toString() + " " + updater);
        return Optional.ofNullable(entry);
    }

    boolean isEmpty() {
        return branchHashes.isEmpty();
    }
}
