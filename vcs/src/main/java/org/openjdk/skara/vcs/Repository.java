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

import org.openjdk.skara.vcs.git.GitRepository;
import org.openjdk.skara.vcs.hg.HgRepository;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;

public interface Repository extends ReadOnlyRepository {
    Repository init() throws IOException;
    void checkout(Hash h, boolean force) throws IOException;
    default void checkout(Hash h) throws IOException {
        checkout(h, false);
    }
    void checkout(Branch b, boolean force) throws IOException;
    default void checkout(Branch b) throws IOException {
        checkout(b, false);
    }
    default Optional<Hash> fetch(URI uri, String refspec) throws IOException {
        return fetch(uri, refspec, true);
    }
    default Optional<Hash> fetch(URI uri, String refspec, boolean includeTags) throws IOException {
        return fetch(uri, refspec, includeTags, false);
    }
    Optional<Hash> fetch(URI uri, String refspec, boolean includeTags, boolean forceUpdateTags) throws IOException;
    default void fetchAll(URI uri) throws IOException {
        fetchAll(uri, true);
    }
    void fetchAll(URI uri, boolean includeTags) throws IOException;
    default void fetchAllRemotes() throws IOException {
        fetchAllRemotes(false);
    }
    void fetchAllRemotes(boolean includeTags) throws IOException;
    void fetchRemote(String remote) throws IOException;
    void pushAll(URI uri, boolean force) throws IOException;
    default void pushAll(URI uri) throws IOException {
        pushAll(uri, false);
    }
    void pushTags(URI uri, boolean force) throws IOException;
    default void pushTags(URI uri) throws IOException {
        pushTags(uri, false);
    }
    default void push(Hash hash, URI uri, String ref, boolean force) throws IOException {
        push(hash, uri, ref, force, false);
    }
    void push(Hash hash, URI uri, String ref, boolean force, boolean includeTags) throws IOException;
    void push(Branch branch, String remote, boolean setUpstream) throws IOException;
    void push(Tag tag, URI uri, boolean force) throws IOException;
    void push(String refspec, URI uri) throws IOException;
    void clean() throws IOException;
    void reset(Hash target, boolean hard) throws IOException;
    void revert(Hash parent) throws IOException;
    Repository reinitialize() throws IOException;
    void squash(Hash h) throws IOException;
    void add(List<Path> files) throws IOException;
    default void add(Path... files) throws IOException {
        add(Arrays.asList(files));
    }
    void remove(List<Path> files) throws IOException;
    default void remove(Path... files) throws IOException {
        remove(Arrays.asList(files));
    }

    void pull(boolean includeTags) throws IOException;
    default void pull() throws IOException {
        pull(false);
    }

    void pull(String remote, boolean includeTags) throws IOException;
    default void pull(String remote) throws IOException {
        pull(remote, false);
    }

    void pull(String remote, String refspec, boolean includeTags) throws IOException;
    default void pull(String remote, String refspec) throws IOException {
        pull(remote, refspec, false);
    }

    void addremove() throws IOException;
    void config(String section, String key, String value, boolean global) throws IOException;
    default void config(String section, String key, String value) throws IOException {
        config(section, key, value, false);
    }
    Hash commit(String message,
                String authorName,
                String authorEmail) throws IOException;
    Hash commit(String message,
                String authorName,
                String authorEmail,
                ZonedDateTime date) throws IOException;
    Hash commit(String message,
                String authorName,
                String authorEmail,
                String committerName,
                String committerEmail) throws IOException;
    Hash commit(String message,
                String authorName,
                String authorEmail,
                ZonedDateTime authorDate,
                String committerName,
                String committerEmail,
                ZonedDateTime committerDate) throws IOException;
    Hash commit(String message,
                String authorName,
                String authorEmail,
                ZonedDateTime authorDate,
                String committerName,
                String committerEmail,
                ZonedDateTime committerDate,
                List<Hash> parents,
                Tree tree) throws IOException;
    Hash amend(String message) throws IOException;
    Hash amend(String message,
               String authorName,
               String authorEmail) throws IOException;
    Hash amend(String message,
               String authorName,
               String authorEmail,
               String committerName,
               String committerEmail) throws IOException;
    default Tag tag(Hash hash, String tagName, String message, String authorName, String authorEmail) throws IOException {
        return tag(hash, tagName, message, authorName, authorEmail, null);
    }
    default Tag tag(Hash hash, String tagName, String message, String authorName, String authorEmail, ZonedDateTime date) throws IOException {
        return tag(hash, tagName, message, authorName, authorEmail, date, false);
    }
    Tag tag(Hash hash, String tagName, String message, String authorName, String authorEmail, ZonedDateTime date, boolean force) throws IOException;
    Branch branch(Hash hash, String branchName) throws IOException;
    void prune(Branch branch, String remote) throws IOException;
    void delete(Branch b) throws IOException;
    void rebase(Hash hash, String committerName, String committerEmail) throws IOException;

    enum FastForward {
        ONLY,
        DISABLE,
        AUTO
    }

    default void merge(Hash hash) throws IOException {
        merge(hash, FastForward.AUTO);
    }
    void merge(Hash hash, FastForward ff) throws IOException;
    default void merge(Branch branch) throws IOException {
        merge(branch, FastForward.AUTO);
    }
    void merge(Branch branch, FastForward ff) throws IOException;
    default void merge(Hash hash, String strategy) throws IOException {
        merge(hash, strategy, FastForward.AUTO);
    }
    void merge(Hash hash, String strategy, FastForward ff) throws IOException;
    void abortMerge() throws IOException;
    void addRemote(String name, String path) throws IOException;
    void setPaths(String remote, String pullPath, String pushPath) throws IOException;
    void apply(Diff diff, boolean force) throws IOException;
    void apply(Path patchFile, boolean force)  throws IOException;
    boolean cherryPick(Hash hash) throws IOException;
    void copy(Path from, Path to) throws IOException;
    void move(Path from, Path to) throws IOException;
    default void setPaths(String remote, String pullPath) throws IOException {
        setPaths(remote, pullPath, null);
    }
    void addSubmodule(String pullPath, Path path) throws IOException;
    void updateSubmodule(Path path) throws IOException;
    void deleteUntrackedFiles() throws IOException;
    default void updateSubmodule(Submodule s) throws IOException {
        updateSubmodule(s.path());
    }

    void addNote(Hash hash,
                 List<String> lines,
                 String authorName,
                 String authorEmail,
                 String committerName,
                 String committerEmail) throws IOException;
    default void addNote(Hash hash, List<String> lines, String authorName, String authorEmail) throws IOException {
        addNote(hash, lines, authorName, authorEmail, authorName, authorEmail);
    }
    List<String> notes(Hash hash) throws IOException;
    void pushNotes(URI uri) throws IOException;

    /**
     * Check whether this commit is empty.
     * For a merge commit, it will be considered as empty if it has no merge resolutions.
     */
    boolean isEmptyCommit(Hash hash);

    default void push(Hash hash, URI uri, String ref) throws IOException {
        push(hash, uri, ref, false);
    }

    default ReadOnlyRepository readOnly() {
        return this;
    }

    static Repository init(Path p, VCS vcs) throws IOException {
        switch (vcs) {
            case GIT:
                return new GitRepository(p).init();
            case HG:
                return new HgRepository(p).init();
            default:
                throw new IllegalArgumentException("Invalid enum value: " + vcs);
        }
    }

    /**
     * Turn on a static flag of all repository providers to ignore local configuration.
     */
    static void ignoreConfiguration() {
        GitRepository.ignoreConfiguration();
        HgRepository.ignoreConfiguration();
    }

    boolean isRemergeDiffEmpty(Hash mergeCommitHash) throws IOException;

    static Optional<Repository> get(Path p) throws IOException {
        var gitRepo = GitRepository.get(p);
        var hgRepo = HgRepository.get(p);
        if (gitRepo.isPresent() && hgRepo.isEmpty()) {
            return gitRepo;
        } else if (gitRepo.isEmpty() && hgRepo.isPresent()) {
            return hgRepo;
        } else if (gitRepo.isPresent() && hgRepo.isPresent()) {
            // Nested repositories
            var gitRoot = gitRepo.get().root();
            var hgRoot = hgRepo.get().root();
            if (gitRoot.equals(hgRoot)) {
                throw new IOException(p.toString() + " contains both a hg and git repository");
            }
            if (hgRoot.startsWith(gitRoot)) {
                return hgRepo;
            } else {
                return gitRepo;
            }
        }
        return Optional.empty();
    }

    static boolean exists(Path p) throws IOException {
        return get(p).isPresent();
    }

    static Repository materialize(Path p, URI remote, String ref) throws IOException {
        return materialize(p, remote, ref, true);
    }

    static Repository materialize(Path p, URI remote, String ref, boolean checkout) throws IOException {
        var localRepo = remote.getPath().endsWith(".git") ?
            Repository.init(p, VCS.GIT) : Repository.init(p, VCS.HG);
        if (!localRepo.exists()) {
            localRepo.init();
        } else if (!localRepo.isHealthy()) {
            localRepo.reinitialize();
        } else {
            try {
                localRepo.clean();
            } catch (IOException e) {
                localRepo.reinitialize();
            }
        }

        var baseHash = localRepo.fetch(remote, ref).orElseThrow();

        if (checkout) {
            try {
                localRepo.checkout(baseHash, true);
            } catch (IOException e) {
                localRepo.reinitialize();
                baseHash = localRepo.fetch(remote, ref).orElseThrow();
                localRepo.checkout(baseHash, true);
            }
        }

        return localRepo;
    }

    static Repository clone(URI from) throws IOException {
        var to = Path.of(from).getFileName();
        if (to.toString().endsWith(".git")) {
            to = Path.of(to.toString().replace(".git", ""));
        }
        return clone(from, to);
    }

    static Repository clone(URI from, Path to) throws IOException {
        return clone(from, to, false);
    }

    static Repository clone(URI from, Path to, boolean isBare) throws IOException {
        return clone(from, to, isBare, null);
    }

    static Repository clone(URI from, Path to, boolean isBare, Path seed) throws IOException {
        return from.getPath().endsWith(".git") ?
            GitRepository.clone(from, to, isBare, seed) : HgRepository.clone(from, to, isBare, seed);
    }

    static Repository mirror(URI from, Path to) throws IOException {
        return from.getPath().toString().endsWith(".git") ?
            GitRepository.mirror(from, to) :
            HgRepository.clone(from, to, true, null); // hg does not have concept of "mirror"
    }
}
