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
package org.openjdk.skara.vcs;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.*;

public interface ReadOnlyRepository {
    Hash head() throws IOException;
    Optional<Branch> currentBranch() throws IOException;
    Optional<Bookmark> currentBookmark() throws IOException;
    Branch defaultBranch() throws IOException;
    List<Branch> branches() throws IOException;
    List<Branch> branches(String remote) throws IOException;
    Optional<Tag> defaultTag() throws IOException;
    List<Tag> tags() throws IOException;
    Commits commits() throws IOException;
    Commits commits(int n) throws IOException;
    Commits commits(boolean reverse) throws IOException;
    Commits commits(int n, boolean reverse) throws IOException;
    Commits commits(String range) throws IOException;
    Commits commits(String range, boolean reverse) throws IOException;
    Commits commits(String range, int n) throws IOException;
    Commits commits(String range, int n, boolean reverse) throws IOException;
    Commits commits(List<Hash> reachableFrom, List<Hash> unreachableFrom) throws IOException;
    Optional<Commit> lookup(Hash h) throws IOException;
    Optional<Commit> lookup(Branch b) throws IOException;
    Optional<Commit> lookup(Tag t) throws IOException;
    List<CommitMetadata> commitMetadata() throws IOException;
    default Optional<CommitMetadata> commitMetadata(Hash hash) throws IOException {
        var l = commitMetadata(range(hash));
        if (l.size() > 1) {
            throw new IllegalStateException("More than one commit for hash: " + hash.hex());
        }
        if (l.size() == 0) {
            return Optional.empty();
        }
        return Optional.of(l.get(0));
    }
    List<CommitMetadata> commitMetadata(boolean reverse) throws IOException;
    List<CommitMetadata> commitMetadata(String range) throws IOException;
    List<CommitMetadata> commitMetadata(Hash from, Hash to) throws IOException;
    List<CommitMetadata> commitMetadata(String range, boolean reverse) throws IOException;
    List<CommitMetadata> commitMetadata(Hash from, Hash to, boolean reverse) throws IOException;
    List<CommitMetadata> commitMetadata(List<Path> paths) throws IOException;
    List<CommitMetadata> commitMetadata(List<Path> paths, boolean reverse) throws IOException;
    List<CommitMetadata> commitMetadata(String range, List<Path> paths) throws IOException;
    List<CommitMetadata> commitMetadata(Hash from, Hash to, List<Path> paths) throws IOException;
    List<CommitMetadata> commitMetadata(String range, List<Path> paths, boolean reverse) throws IOException;
    List<CommitMetadata> commitMetadata(Hash from, Hash to, List<Path> paths, boolean reverse) throws IOException;

    // Can't overload on both List<Path> and List<Branch>
    List<CommitMetadata> commitMetadataFor(List<Branch> branches) throws IOException;

    String range(Hash h);
    String rangeInclusive(Hash from, Hash to);
    String rangeExclusive(Hash from, Hash to);
    Path root() throws IOException;
    boolean exists() throws IOException;
    boolean isHealthy() throws IOException;
    boolean isEmpty() throws IOException;
    boolean isClean() throws IOException;

    /**
     * Finds the hash of the merge-base commit for the two given hashes. Returns
     * empty if the two hashes do not share any history.
     */
    default Optional<Hash> mergeBaseOptional(Hash first, Hash second) throws IOException {
        return Optional.of(mergeBase(first, second));
    }

    /**
     * Finds the hash of the merge base commit for the two given hashes. Throws
     * IOException if it can't be found.
     */
    Hash mergeBase(Hash first, Hash second) throws IOException;
    boolean isAncestor(Hash ancestor, Hash descendant) throws IOException;
    Optional<Hash> resolve(String ref) throws IOException;
    default Optional<Hash> resolve(Tag t) throws IOException {
        return resolve(t.name());
    }
    default Optional<Hash> resolve(Branch b) throws IOException {
        return resolve(b.name());
    }
    boolean contains(Branch b, Hash h) throws IOException;
    boolean contains(Hash h) throws IOException;
    Optional<String> username() throws IOException;
    Optional<byte[]> show(Path p, Hash h) throws IOException;
    default Optional<List<String>> lines(Path p, Hash h) throws IOException {
        return show(p, h).map(bytes -> new String(bytes, StandardCharsets.UTF_8).lines().collect(Collectors.toList()));
    }

    List<FileEntry> files(Hash h, List<Path> paths) throws IOException;
    default List<FileEntry> files(Hash h, Path... paths) throws IOException {
        return files(h, Arrays.asList(paths));
    }

    void dump(FileEntry entry, Path to) throws IOException;
    List<StatusEntry> status(Hash from, Hash to) throws IOException;
    List<StatusEntry> status() throws IOException;

    static final int DEFAULT_SIMILARITY = 90;
    default Diff diff(Hash base, Hash head) throws IOException {
        return diff(base, head, DEFAULT_SIMILARITY);
    }
    Diff diff(Hash base, Hash head, int similarity) throws IOException;
    default Diff diff(Hash base, Hash head, List<Path> files) throws IOException {
        return diff(base, head, files, DEFAULT_SIMILARITY);
    }
    Diff diff(Hash base, Hash head, List<Path> files, int similarity) throws IOException;
    default Diff diff(Hash head) throws IOException {
        return diff(head, DEFAULT_SIMILARITY);
    }
    Diff diff(Hash head, int similarity) throws IOException;
    default Diff diff(Hash head, List<Path> files) throws IOException {
        return diff(head, files, DEFAULT_SIMILARITY);
    }

    List<CommitMetadata> follow(Path path) throws IOException;
    List<CommitMetadata> follow(Path path, Hash base, Hash head) throws IOException;
    Diff diff(Hash head, List<Path> files, int similarity) throws IOException;
    List<String> config(String key) throws IOException;
    Repository copyTo(Path destination) throws IOException;
    String pullPath(String remote) throws IOException;
    String pushPath(String remote) throws IOException;
    boolean isValidRevisionRange(String expression) throws IOException;
    Optional<String> upstreamFor(Branch branch) throws IOException;
    List<Reference> remoteBranches(String remote) throws IOException;
    List<String> remotes() throws IOException;
    List<Submodule> submodules() throws IOException;
    Tree tree(Hash h) throws IOException;
    default Tree tree(Commit c) throws IOException {
        return tree(c.hash());
    }
    default Tree tree(CommitMetadata c) throws IOException {
        return tree(c.hash());
    }

    static Optional<ReadOnlyRepository> get(Path p) throws IOException {
        return Repository.get(p).map(r -> r);
    }

    static boolean exists(Path p) throws IOException {
        return Repository.exists(p);
    }

    Optional<Tag.Annotated> annotate(Tag tag) throws IOException;

    int commitCount() throws IOException;

    int commitCount(List<Branch> branches) throws IOException;

    /**
     * Returns the special hash that references the virtual commit before the first real commit in a repository.
     */
    Hash initialHash();

    Optional<List<String>> stagedFileContents(Path p);

    Commit staged() throws IOException;

    Commit workingTree() throws IOException;
}
