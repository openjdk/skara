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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

class TestRepository implements ReadOnlyRepository {
    private Branch currentBranch = null;
    private Branch defaultBranch = null;
    private List<Branch> branches = new ArrayList<Branch>();

    private Tag defaultTag = null;
    private List<Tag> tags = new ArrayList<Tag>();

    public Optional<Branch> currentBranch() throws IOException {
        return Optional.empty();
    }

    void setCurrentBranch(Branch branch) {
        currentBranch = branch;
    }

    public Optional<Bookmark> currentBookmark() {
        return Optional.empty();
    }

    public Branch defaultBranch() throws IOException {
        return defaultBranch;
    }

    void setDefaultBranch(Branch branch) throws IOException {
        defaultBranch = branch;
    }

    public List<Branch> branches() throws IOException {
        return branches;
    }

    @Override
    public List<Branch> branches(String remote) throws IOException {
        return branches;
    }

    void setBranches(List<Branch> branches) {
        this.branches = branches;
    }

    public Optional<Tag> defaultTag() throws IOException {
        return Optional.ofNullable(defaultTag);
    }

    void setDefaultTag(Tag tag) {
        defaultTag = tag;
    }

    public List<Tag> tags() throws IOException {
        return tags;
    }

    void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public Hash head() throws IOException {
        return null;
    }

    public Commits commits() throws IOException {
        return null;
    }

    @Override
    public Commits commits(int n) throws IOException {
        return null;
    }

    public Commits commits(boolean reverse) throws IOException {
        return null;
    }

    @Override
    public Commits commits(int n, boolean reverse) throws IOException {
        return null;
    }

    public Commits commits(String range) throws IOException {
        return null;
    }

    public Commits commits(String range, boolean reverse) throws IOException {
        return null;
    }

    @Override
    public Commits commits(String range, int n) throws IOException {
        return null;
    }

    @Override
    public Commits commits(String range, int n, boolean reverse) throws IOException {
        return null;
    }

    public Optional<Commit> lookup(Hash h) throws IOException {
        return Optional.empty();
    }

    public Optional<Commit> lookup(Branch b) throws IOException {
        return Optional.empty();
    }

    public Optional<Commit> lookup(Tag t) throws IOException {
        return Optional.empty();
    }

    public List<CommitMetadata> commitMetadata(String range) throws IOException {
        return List.of();
    }

    public List<CommitMetadata> commitMetadata() throws IOException {
        return List.of();
    }

    public Path root() throws IOException {
        return null;
    }

    public boolean exists() throws IOException {
        return false;
    }

    public boolean isHealthy() throws IOException {
        return false;
    }

    public boolean isEmpty() throws IOException {
        return true;
    }

    @Override
    public boolean isClean() throws IOException {
        return true;
    }

    public Hash mergeBase(Hash first, Hash second) throws IOException {
        return null;
    }

    @Override
    public boolean isAncestor(Hash ancestor, Hash descendant) throws IOException {
        return false;
    }

    public Optional<Hash> resolve(String ref) throws IOException {
        return Optional.empty();
    }

    public Optional<String> username() throws IOException {
        return Optional.empty();
    }

    public Optional<byte[]> show(Path p, Hash h) throws IOException {
        return Optional.of(new byte[0]);
    }

    public List<FileEntry> files(Hash h, List<Path> paths) throws IOException {
        return List.of();
    }

    public void dump(FileEntry entry, Path to) throws IOException {
    }

    public Diff diff(Hash base, Hash head) throws IOException {
        return null;
    }

    public Diff diff(Hash base, Hash head, List<Path> files) throws IOException {
        return null;
    }

    public Diff diff(Hash head) throws IOException {
        return null;
    }

    public Diff diff(Hash head, List<Path> files) throws IOException {
        return null;
    }

    public List<String> config(String key) throws IOException {
        return null;
    }

    public Repository copyTo(Path destination) throws IOException {
        return null;
    }

    public String pullPath(String remote) throws IOException {
        return null;
    }

    public String pushPath(String remote) throws IOException {
        return null;
    }

    public boolean isValidRevisionRange(String expression) throws IOException {
        return false;
    }

    public Optional<String> upstreamFor(Branch b) throws IOException {
        return Optional.empty();
    }

    public List<StatusEntry> status(Hash from, Hash to) throws IOException {
        return Collections.emptyList();
    }

    public boolean contains(Branch b, Hash h) throws IOException {
        return false;
    }

    public List<Reference> remoteBranches(String remote) throws IOException {
        return null;
    }

    public List<String> remotes() throws IOException {
        return null;
    }

    public void addSubmodule(String pullPath, Path path) throws IOException {
    }

    public List<Submodule> submodules() throws IOException {
        return null;
    }

    public Optional<Tag.Annotated> annotate(Tag tag) throws IOException {
        return null;
    }
}
