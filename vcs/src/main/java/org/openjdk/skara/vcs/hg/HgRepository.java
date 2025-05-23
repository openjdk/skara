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
package org.openjdk.skara.vcs.hg;

import org.openjdk.skara.process.Process;
import org.openjdk.skara.process.Execution;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.*;
import java.net.URI;

public class HgRepository implements Repository {
    private final static Map<String, String> NO_CONFIG_ENV = Map.of(
            "HGRCPATH", "",
            "HGPLAIN", "",
            "HGEDITOR", "",
            "EDITOR", "",
            "VISUAL", ""
    );
    private static Map<String, String> currentEnv = Collections.emptyMap();

    private static final String EXT_PY = "ext.py";
    private final Path dir;
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.hg");

    private static final Hash NULL_REVISION = new Hash("0".repeat(40));

    public static void ignoreConfiguration() {
        currentEnv = NO_CONFIG_ENV;
    }

    @Override
    public boolean isRemergeDiffEmpty(Hash mergeCommitHash) throws IOException {
        throw new UnsupportedOperationException();
    }

    private void copyResource(String name, Path p) throws IOException {
        Files.copy(this.getClass().getResourceAsStream("/" + name), p, StandardCopyOption.REPLACE_EXISTING);
    }

    private java.lang.Process start(String... cmd) throws IOException {
        return start(Arrays.asList(cmd));
    }

    private java.lang.Process start(List<String> cmd) throws IOException {
        log.fine("Executing " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().putAll(currentEnv);
        return pb.start();
    }

    private static void stop(java.lang.Process p) throws IOException {
        if (p != null && p.isAlive()) {
            var stream = p.getInputStream();
            var read = 0;
            var buf = new byte[128];
            while (read != -1) {
                read = stream.read(buf);
            }
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }

    private Execution capture(List<String> cmd) {
        return capture(cmd.toArray(new String[0]));
    }

    private Execution capture(String... cmd) {
        return capture(dir, cmd);
    }

    private static Execution capture(Path cwd, List<String> cmd) {
        return capture(cwd, cmd.toArray(new String[0]));
    }
    public static Execution capture(Path cwd, String... cmd) {
        return Process.capture(cmd)
                      .environ(currentEnv)
                      .workdir(cwd)
                      .execute();
    }

    private static Execution.Result await(Execution e) throws IOException {
        var result = e.await();
        if (result.status() != 0) {
            if (result.exception().isPresent()) {
                throw new IOException("Unexpected exit code\n" + result, result.exception().get());
            } else {
                throw new IOException("Unexpected exit code\n" + result);
            }
        }
        return result;
    }

    private static void await(java.lang.Process p) throws IOException {
        try {
            var res = p.waitFor();
            if (res != 0) {
                throw new IOException("Unexpected exit code: " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public HgRepository(Path dir) {
        this.dir = dir.toAbsolutePath();
    }

    @Override
    public List<Branch> branches() throws IOException {
        try (var p = capture("hg", "branches")) {
            return await(p).stdout()
                           .stream()
                           .map(line -> line.split("\\s")[0])
                           .map(Branch::new)
                           .collect(Collectors.toList());
        }
    }

    @Override
    public List<Branch> branches(String remote) throws IOException {
        // Mercurial does not have namespacing of branch names
        return branches();
    }

    @Override
    public List<Tag> tags() throws IOException {
        try (var p = capture("hg", "tags")) {
            return await(p).stdout()
                           .stream()
                           .map(line -> line.split("\\s")[0])
                           .map(Tag::new)
                           .collect(Collectors.toList());
        }
    }

    @Override
    public Path root() throws IOException {
        try (var p = capture("hg", "root")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output\n" + res);
            }
            return Paths.get(res.stdout().get(0));
        }
    }

    private void checkout(String ref, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "update"));
        if (!force) {
            cmd.add("--check");
        }
        cmd.add(ref);
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void checkout(Hash h, boolean force) throws IOException {
        checkout(h.hex(), force);
    }

    @Override
    public void checkout(Branch b, boolean force) throws IOException {
        checkout(b.name(), force);
    }

    @Override
    public Optional<Hash> resolve(String ref) throws IOException {
        try (var p = capture("hg", "--config", "defaults.log=", "log", "--rev=" + ref, "--template={node}\n")) {
            var res = p.await();
            if (res.status() == 0 && res.stdout().size() == 1) {
                return Optional.of(new Hash(res.stdout().get(0)));
            }
            return Optional.empty();
        }
    }

    @Override
    public Commits commits() throws IOException {
        return commits(null, -1, false);
    }

    @Override
    public Commits commits(boolean reverse) throws IOException {
        return commits(null, -1, reverse);
    }

    @Override
    public Commits commits(int n) throws IOException {
        return commits(null, n, false);
    }

    @Override
    public Commits commits(int n, boolean reverse) throws IOException {
        return commits(null, n, reverse);
    }

    @Override
    public Commits commits(String range) throws IOException {
        return commits(range, -1, false);
    }

    @Override
    public Commits commits(String range, int n) throws IOException {
        return commits(range, n, false);
    }

    @Override
    public Commits commits(String range, boolean reverse) throws IOException {
        return commits(range, -1, reverse);
    }

    @Override
    public Commits commits(String range, int n,  boolean reverse) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);
        return new HgCommits(dir, range, ext, reverse, n);
    }

    @Override
    public Commits commits(List<Hash> reachableFrom, List<Hash> unreachableFrom) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Hash h) throws IOException {
        try (var p = capture("hg", "log", "--rev=" + h.hex(), "--template={node}\n")) {
            var res = p.await();
            return res.status() == 0;
        }
    }

    @Override
    public Optional<Commit> lookup(Hash h) throws IOException {
        if (!contains(h)) {
            return Optional.empty();
        }
        var commits = commits(h.hex()).asList();
        if (commits.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(commits.get(0));
    }

    @Override
    public Optional<Commit> lookup(Branch b) throws IOException {
        var hash = resolve(b.name()).orElseThrow(() -> new IOException("Branch " + b.name() + " not found"));
        return lookup(hash);
    }

    @Override
    public Optional<Commit> lookup(Tag t) throws IOException {
        var hash = resolve(t.name()).orElseThrow(() -> new IOException("Tag " + t.name() + " not found"));
        return lookup(hash);
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range, List<Path> paths) throws IOException {
        return commitMetadata(range, paths, false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to, List<Path> paths) throws IOException {
        return commitMetadata(from.hex() + ":" + to.hex() + "-" + from.hex(), paths, false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to, List<Path> paths, boolean reverse) throws IOException {
        return commitMetadata(from.hex() + ":" + to.hex() + "-" + from.hex(), paths, reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range, List<Path> paths, boolean reverse) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "log", "--template", HgCommitMetadata.TEMPLATE));
        range = range == null ? "tip:0" : range;
        var revset = reverse ? "reverse(" + range + ")" : range;
        cmd.add("--rev");
        cmd.add(revset);
        if (paths != null && !paths.isEmpty()) {
            cmd.addAll(paths.stream().map(Path::toString).collect(Collectors.toList()));
        }
        return readMetadata(cmd);
    }

    @Override
    public List<CommitMetadata> commitMetadataFor(List<Branch> branches) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range) throws IOException {
        return commitMetadata(range, List.of(), false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(boolean reverse) throws IOException {
        return commitMetadata(null, List.of(), reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to) throws IOException {
        return commitMetadata(from.hex() + ":" + to.hex() + "-" + from.hex(), List.of(), false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range, boolean reverse) throws IOException {
        return commitMetadata(range, List.of(), reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to, boolean reverse) throws IOException {
        return commitMetadata(from.hex() + ":" + to.hex() + "-" + from.hex(), reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(List<Path> paths) throws IOException {
        return commitMetadata(null, paths, false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(List<Path> paths, boolean reverse) throws IOException {
        return commitMetadata(null, paths, reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata() throws IOException {
        return commitMetadata(null, List.of(), false);
    }

    @Override
    public List<CommitMetadata> follow(Path path) throws IOException {
        return follow(path, null, null);
    }

    private List<CommitMetadata> readMetadata(List<String> cmd) throws IOException {
        var p = start(cmd);
        var reader = new UnixStreamReader(p.getInputStream());
        var result = new ArrayList<CommitMetadata>();

        var line = reader.readLine();
        while (line != null) {
            result.add(HgCommitMetadata.read(reader));
            line = reader.readLine();
        }

        await(p);
        return result;
    }

    @Override
    public List<CommitMetadata> follow(Path path, Hash from, Hash to) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "log", "--follow", "--template", HgCommitMetadata.TEMPLATE));
        if (from != null && to != null) {
            cmd.add("--rev");
            cmd.add(from.hex() + ".." + to.hex() + " - " + from.hex());
        }
        cmd.add(path.toString());
        return readMetadata(cmd);
    }

    @Override
    public boolean isEmpty() throws IOException {
        var numBranches = branches().size();
        var numTags = tags().size();

        if (numBranches > 0 || numTags > 1) {
            return false;
        }

        var tip = resolve("tip");
        return tip.isEmpty() || tip.get().equals(Hash.zero());
    }

    @Override
    public boolean isHealthy() throws IOException {
        var root = root().toString();
        return !(Files.exists(Path.of(root, ".hg", "wlock")) ||
                 Files.exists(Path.of(root, ".hg", "store", "lock")));
    }

    @Override
    public void deleteUntrackedFiles() throws IOException {
        var root = root();
        try (var p = capture("hg", "status", "--unknown", "--no-status")) {
            var res = await(p);
            for (var line : res.stdout()) {
                Files.delete(root.resolve(line));
            }
        }
    }

    @Override
    public void clean() throws IOException {
        try (var p = capture("hg", "merge", "--abort")) {
            p.await();
        }

        try (var p = capture("hg", "recover")) {
            p.await();
        }

        try (var p = capture("hg", "status", "--ignored", "--no-status")) {
            var root = root().toString();
            for (var filename : await(p).stdout()) {
                Files.delete(Path.of(root, filename));
            }
        }

        try (var p = capture("hg", "status", "--unknown", "--no-status")) {
            var root = root().toString();
            for (var filename : await(p).stdout()) {
                Files.delete(Path.of(root, filename));
            }
        }

        try (var p = capture("hg", "revert", "--no-backup", "--all")) {
            await(p);
        }
    }

    @Override
    public void reset(Hash target, boolean hard) throws IOException {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public Repository reinitialize() throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);
        }

        return init();
    }

    @Override
    public Optional<Hash> fetch(URI uri, String refspec, boolean includeTags, boolean forceUpdateTags) throws IOException {
        // Ignore includeTags and forceUpdateTags, Mercurial always fetches tags
        return fetch(uri != null ? uri.toString() : null, refspec);
    }

    @Override
    public void fetchAll(URI uri, boolean includeTags) throws IOException {
        // Ignore includeTags, Mercurial always fetches tags
        var cmd = new ArrayList<String>();
        cmd.add("hg");
        cmd.add("pull");
        cmd.add(uri.toString());
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    private Optional<Hash> fetch(String from, String refspec) throws IOException {
        var oldHeads = new HashSet<>(heads());

        var cmd = new ArrayList<String>();
        cmd.add("hg");
        cmd.add("pull");
        if (refspec != null) {
            cmd.add("--rev");
            cmd.add(refspec);
        }
        if (from != null) {
            cmd.add(from);
        }
        try (var p = capture(cmd)) {
            await(p);
        }

        var newHeads = new HashSet<>(heads());
        newHeads.removeAll(oldHeads);

        if (newHeads.size() > 1) {
            throw new IllegalStateException("fetching multiple heads is not supported");
        } else if (newHeads.size() == 0) {
            // no new head was fetched, return current head
            return Optional.of(head());
        }
        return Optional.of(newHeads.iterator().next());
    }

    @Override
    public void fetchAllRemotes(boolean includeTags) throws IOException {
        // Ignore includeTags, Mercurial always fetches tags
        var pullPaths = new ArrayList<URI>();
        try (var p = capture("hg", "paths")) {
            var res = await(p);
            for (var line : res.stdout()) {
                var parts = line.split("=");
                var name = parts[0].trim();
                var uri = parts[1].trim();
                if (!name.endsWith("-push")) {
                    pullPaths.add(URI.create(uri));
                }
            }
        }

        for (var uri : pullPaths) {
            fetch(uri, null);
        }
    }

    @Override
    public void fetchRemote(String remote) throws IOException {
        fetch(remote, null);
    }

    @Override
    public void delete(Branch b) throws IOException {
        throw new RuntimeException("Branches cannot be deleted in Mercurial");
    }

    @Override
    public Repository init() throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        try (var p = capture("hg", "init")) {
            await(p);
            return this;
        }
    }

    @Override
    public void pushAll(URI uri, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "push", "--new-branch"));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(uri.toString());
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void pushTags(URI uri, boolean force) throws IOException {
        throw new RuntimeException("Cannot push only tags with Mercurial");
    }

    @Override
    public void push(Hash hash, URI uri, String ref, boolean force, boolean includeTags) throws IOException {
        // ignore includeTags, hg always pushes tags
        var cmd = new ArrayList<>(List.of("hg", "push", "--rev=" + hash.hex()));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(uri.toString() + "#" + ref);
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void push(Branch branch, String remote, boolean setUpstream) throws IOException {
        // ignore setUpstream, no such concept in Mercurial
        try (var p = capture("hg", "push", "--branch", branch.name(), remote)) {
            await(p);
        }
    }

    @Override
    public void push(Tag tag, URI uri, boolean force) throws IOException {
        var cmd = new ArrayList<>(List.of("hg", "push"));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(tag.name());
        cmd.add(uri.toString());
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void push(String refspec, URI uri) throws IOException {
        throw new RuntimeException("Refspec not supported with Mercurial");
    }

    @Override
    public boolean isClean() throws IOException {
        try (var p = capture("hg", "status")) {
            var output = await(p);
            return output.stdout().size() == 0;
        }
    }

    @Override
    public boolean exists() throws IOException {
        if (!Files.exists(dir)) {
            return false;
        }

        try {
            root();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void export(String revset, Path to) throws IOException {
        var cmd = List.of("hg", "export", "--git", "--rev", revset);
        log.fine("Executing " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectOutput(to.toFile());
        pb.environment().putAll(currentEnv);
        var p = pb.start();
        try {
            await(p);
        } catch (Throwable t) {
            if (p.isAlive()) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }

            throw new IOException(t);
        }
    }

    @Override
    public void squash(Hash h) throws IOException {
        var revset = ".:" + h.hex() + " and not .";
        var patch = Files.createTempFile("squash", ".patch");
        export(revset, patch);

        try (var p = capture("hg", "--config", "extensions.mq=", "strip", "--rev", revset)) {
            await(p);
        }

        try (var p = capture("hg", "import", "--no-commit", patch.toString())) {
            await(p);
        }
    }


    @Override
    public Hash commit(String message, String authorName, String authorEmail)  throws IOException {
        return commit(message, authorName, authorEmail, null);
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail, ZonedDateTime authorDate)  throws IOException {
        var user = authorEmail == null ? authorName : authorName + " <" + authorEmail + ">";
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "commit", "--message=" + message, "--user=" + user));
        if (authorDate != null) {
            var formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            cmd.add("--date=" + authorDate.format(formatter));
        }
        try (var p = capture(cmd)) {
            await(p);
        }
        return resolve("tip").orElseThrow(() -> new IOException("Could not resolve 'tip'"));
    }

    @Override
    public Hash commit(String message,
                       String authorName,
                       String authorEmail,
                       String committerName,
                       String committerEmail) throws IOException {
        return commit(message, authorName, authorEmail, null, committerName, committerEmail, null);
    }

    @Override
    public Hash commit(String message,
                       String authorName,
                       String authorEmail,
                       ZonedDateTime authorDate,
                       String committerName,
                       String committerEmail,
                       ZonedDateTime committerDate) throws IOException {
        if (!Objects.equals(authorName, committerName) ||
            !Objects.equals(authorEmail, committerEmail) ||
            !Objects.equals(authorDate, committerDate)) {
            throw new IllegalArgumentException("hg does not support different author and committer data");
        }

        return commit(message, authorName, authorEmail, authorDate);
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail, ZonedDateTime authorDate, String committerName, String committerEmail, ZonedDateTime committerDate, List<Hash> parents, Tree tree) throws IOException {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Hash amend(String message) throws IOException {
        try (var p = capture("hg", "commit", "--amend", "--message=" + message)) {
            await(p);
        }
        return resolve("tip").orElseThrow(() -> new IOException("Could not resolve 'tip'"));
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail) throws IOException {
        var user = authorEmail == null ? authorName : authorName + " <" + authorEmail + ">";
        try (var p = capture("hg", "commit", "--amend", "--message=" + message, "--user=" + user)) {
            await(p);
        }
        return resolve("tip").orElseThrow(() -> new IOException("Could not resolve 'tip'"));
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail, String committerName, String committerEmail) throws IOException {
        if (!Objects.equals(authorName, committerName) ||
            !Objects.equals(authorEmail, committerEmail)) {
            throw new IllegalArgumentException("hg does not support different author and committer data");
        }

        return amend(message, authorName, authorEmail);
    }

    @Override
    public Tag tag(Hash hash, String name, String message, String authorName, String authorEmail, ZonedDateTime date, boolean force) throws IOException {
        var user = authorEmail != null ?
            authorName + " <" + authorEmail + ">" :
            authorName;
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "tag",
                           "--message", message,
                           "--user", user,
                           "--rev", hash.hex()));
        if (date != null) {
            cmd.add("--date");
            cmd.add(date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        if (force) {
            cmd.add("--force");
        }
        cmd.add(name);
        try (var p = capture(cmd)) {
            await(p);
        }

        return new Tag(name);
    }

    @Override
    public Branch branch(Hash hash, String name) throws IOException {
        // Model a lightweight branch with a bookmark. Not ideal but the
        // closest to git branches.
        try (var p = capture("hg", "bookmark", "--rev", hash.hex(), name)) {
            await(p);
        }

        return new Branch(name);
    }

    @Override
    public void prune(Branch branch, String remote) throws IOException {
        try (var p = capture("hg", "bookmark", "--delete", branch.name())) {
            await(p);
        }
        try (var p = capture("hg", "push", "--bookmark", branch.name(), remote)) {
            await(p);
        }
    }

    @Override
    public Hash mergeBase(Hash first, Hash second) throws IOException {
        var revset = "ancestor(" + first.hex() + ", " + second.hex() + ")";
        try (var p = capture("hg", "log", "--rev=" + revset, "--template={node}\n")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output\n" + res);
            }
            return new Hash(res.stdout().get(0));
        }
    }

    @Override
    public boolean isAncestor(Hash ancestor, Hash descendant) throws IOException {
        return mergeBase(ancestor, descendant).equals(ancestor);
    }

    /**
     * Check if either a is an ancestor of b, or b is an ancestor of a.
     * @return true if a and b are related
     */
    private boolean isRelated(Hash a, Hash b) throws IOException {
        var base = mergeBase(a, b);
        return base.equals(a) || base.equals(b);
    }

    @Override
    public void rebase(Hash hash, String committerName, String committerEmail) throws IOException {
        var current = currentBranch().orElseThrow(() ->
                new IOException("No current branch to rebase upon")
        );
        try (var p = capture("hg", "--config", "extensions.rebase=",
                             "rebase", "--dest", hash.hex(), "--base", current.name())) {
            await(p);
        }
    }

    @Override
    public Optional<Branch> currentBranch() throws IOException {
        try (var p = capture("hg", "branch")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                return Optional.empty();
            }
            return Optional.of(new Branch(res.stdout().get(0)));
        }
    }

    @Override
    public Optional<Bookmark> currentBookmark() throws IOException {
        try (var p = capture("hg", "log", "-r", ".", "--template", "{activebookmark}\n")) {
            var res = await(p);
            if (res.stdout().size() == 1) {
                return Optional.of(new Bookmark(res.stdout().get(0)));
            }
            return Optional.empty();
        }
    }

    @Override
    public Branch defaultBranch() throws IOException {
        return Branch.defaultFor(VCS.HG);
    }

    @Override
    public Optional<Tag> defaultTag() throws IOException {
        return Optional.of(new Tag("tip"));
    }

    @Override
    public Optional<byte[]> show(Path path, Hash hash) throws IOException {
        var output = Files.createTempFile("hg-cat-rev-" + hash.abbreviate(), ".bin");
        try (var p = capture("hg", "cat", "--output=" + output, "--rev=" + hash.hex(), path.toString())) {
            var res = p.await();
            if (res.status() == 0 && Files.exists(output)) {
                var bytes = Files.readAllBytes(output);
                Files.delete(output);
                return Optional.of(bytes);
            }

            if (Files.exists(output)) {
                Files.delete(output);
            }
            return Optional.empty();
        }
    }

    private List<FileEntry> allFiles(Hash hash, List<Path> paths) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);

        var include = new HashSet<>(paths);

        try (var p = capture("hg", "--config", "extensions.ls-tree=" + ext, "ls-tree", hash.hex())) {
            var res = await(p);
            var entries = new ArrayList<FileEntry>();
            for (var line : res.stdout()) {
                var parts = line.split("\t");
                var metadata = parts[0].split(" ");
                var path = Path.of(parts[1]);
                if (include.isEmpty() || include.contains(path)) {
                    var entry = new FileEntry(hash,
                                              FileType.fromOctal(metadata[0]),
                                              new Hash(metadata[2]),
                                              path);
                    entries.add(entry);
                }
            }
            return entries;
        }
    }

    @Override
    public List<FileEntry> files(Hash hash, List<Path> paths) throws IOException {
        if (paths.isEmpty()) {
            return allFiles(hash, paths);
        }

        var entries = new ArrayList<FileEntry>();
        var batchSize = 64;
        var start = 0;
        while (start < paths.size()) {
            var end = start + batchSize;
            if (end > paths.size()) {
                end = paths.size();
            }
            entries.addAll(allFiles(hash, paths.subList(start, end)));
            start = end;
        }
        return entries;
    }

    @Override
    public List<StatusEntry> status(Hash from, Hash to) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);

        try (var p = capture("hg", "--config", "extensions.diff-git-raw=" + ext.toAbsolutePath().toString(),
                                               "diff-git-raw", from.hex(), to.hex())) {
            var res = await(p);
            var entries = new ArrayList<StatusEntry>();
            for (var line : res.stdout()) {
                entries.add(StatusEntry.fromRawLine(line));
            }
            return entries;
        }
    }

    @Override
    public List<StatusEntry> status() throws IOException {
        // TODO: can use merge.mergestate.read(repo) to implement diff-git-raw-workspace
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public void dump(FileEntry entry, Path to) throws IOException {
        var output = to.toAbsolutePath();
        try (var p = capture("hg", "cat", "--output=" + output.toString(),
                                          "--rev=" + entry.commit(),
                                          entry.path().toString())) {
            await(p);
        }
    }

    @Override
    public void revert(Hash parent) throws IOException {
        try (var p = capture("hg", "revert", "--no-backup", "--all", "--rev", parent.hex())) {
            await(p);
        }
    }

    @Override
    public Diff diff(Hash from, int similarity) throws IOException {
        return diff(from, List.of());
    }

    @Override
    public Diff diff(Hash from, List<Path> files, int similarity) throws IOException {
        return diff(from, null, files);
    }

    @Override
    public Diff diff(Hash from, Hash to, int similarity) throws IOException {
        return diff(from, to, List.of());
    }

    @Override
    public Diff diff(Hash from, Hash to, List<Path> files, int similarity) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);

        var cmd = new ArrayList<>(List.of("hg", "--config", "extensions.diff-git-raw=" + ext.toAbsolutePath(),
                                                "diff-git-raw", "--patch", from.hex()));
        if (to != null) {
            cmd.add(to.hex());
        }

        if (files != null) {
            var filenames = files.stream().map(Path::toString).collect(Collectors.toList());
            cmd.add("--files=" + String.join(",", filenames));
        }

        var p = start(cmd);
        try {
            var patches = GitRawDiffParser.parse(p.getInputStream());
            await(p);
            return new Diff(from, to, patches);
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    @Override
    public Optional<String> username() throws IOException {
        var lines = config("ui.username");
        return lines.size() == 1 ? Optional.of(lines.get(0)) : Optional.empty();
    }

    @Override
    public Hash head() throws IOException {
        return resolve(".").orElseThrow(() -> new IOException(". not available"));
    }

    private List<Hash> heads() throws IOException {
        var heads = new ArrayList<Hash>();
        try (var p = capture("hg", "heads", "--template={node}\n")) {
            var res = p.await();
            if (res.status() == 0) {
                for (var hash : res.stdout()) {
                    heads.add(new Hash(hash));
                }
            }
        }
        return heads;
    }

    @Override
    public List<String> config(String key) throws IOException {
        // Do not use HgRepository.capture() here, want to run *with*
        // hg configuration.
        try (var p = Process.capture("hg", "showconfig", key)
                            .workdir(dir)
                            .execute()) {
            var res = p.await();
            if (res.status() == 1) {
                return List.of();
            }
            return res.stdout();
        }
    }

    public static Optional<Repository> get(Path p) throws IOException {
        if (!Files.exists(p)) {
            return Optional.empty();
        }

        var r = new HgRepository(p);
        return r.exists() ? Optional.of(new HgRepository(r.root())) : Optional.empty();
    }

    @Override
    public Repository copyTo(Path destination) throws IOException {
        var from = root().toAbsolutePath().toString();
        var to = destination.toAbsolutePath().toString();
        try (var p = capture("hg", "clone", from, to)) {
            await(p);
        }

        return new HgRepository(destination.toAbsolutePath());
    }

    @Override
    public void merge(Hash h, FastForward ff) throws IOException {
        merge(h, null, ff);
    }

    @Override
    public void merge(Branch b, FastForward ff) throws IOException {
        var hash = resolve(b).orElseThrow(() ->
            new IOException("Can't lookup branch " + b.name())
        );
        merge(hash, null, ff);
    }

    @Override
    public void merge(Hash other, String strategy, FastForward ff) throws IOException {
        var head = head();
        List<String> cmd = null;

        var update = List.of("hg", "update", "--rev", other.hex());
        var debugsetparents = List.of("hg", "debugsetparents", head.hex(), other.hex());
        var merge = new ArrayList<>(List.of("hg", "merge", "--rev=" + other.hex()));
        if (strategy != null) {
            merge.add("--tool=" + strategy);
        }

        if (ff == FastForward.ONLY) {
            cmd = update;
        } else if (ff == FastForward.DISABLE) {
            if (isRelated(head, other)) {
                cmd = debugsetparents;
            } else {
                cmd = merge;
            }
        } else if (ff == FastForward.AUTO) {
            if (isAncestor(head, other)) {
                cmd = update;
            } else if (isAncestor(other, head)) {
                return;
            } else {
                cmd = merge;
            }
        } else {
            throw new IllegalArgumentException("Unexpected fast forward value: " + ff);
        }

        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void abortMerge() throws IOException {
        try (var p = capture("hg", "merge", "--abort")) {
            await(p);
        }

        try (var p = capture("hg", "status", "--unknown", "--no-status")) {
            var res = await(p);
            for (var path : res.stdout()) {
                if (path.toString().endsWith(".orig")) {
                    Files.delete(root().resolve(path));
                }
            }
        }
    }

    @Override
    public void addRemote(String name, String path) throws IOException {
        setPaths(name, path, path);
    }

    @Override
    public void setPaths(String remote, String pullPath, String pushPath) throws IOException {
        var hgrc = Path.of(root().toString(), ".hg", "hgrc");
        if (!Files.exists(hgrc)) {
            Files.createFile(hgrc);
        }

        var lines = Files.readAllLines(hgrc);
        var newLines = new ArrayList<String>();

        var isInPathsSection = false;
        var hasPathsSection = false;
        for (var line : lines) {
            var isSectionHeader = line.startsWith("[") && line.endsWith("]");
            if (isSectionHeader && !isInPathsSection) {
                isInPathsSection = line.equals("[paths]");
                if (isInPathsSection) {
                    newLines.add(line);
                    newLines.add(remote + " = " + (pullPath == null ? "" : pullPath));
                    newLines.add(remote + "-push = " + (pushPath == null ? "" : pushPath));
                    hasPathsSection = true;
                    continue;
                }
            }

            if (isInPathsSection && line.startsWith(remote)) {
                if (line.startsWith(remote + "-push")) {
                    // skip
                } else if (line.startsWith(remote + ":pushurl")) {
                    // skip
                } else if (line.startsWith(remote + " ") || line.startsWith(remote + "=")) {
                    // skip
                } else {
                    newLines.add(line);
                }
            } else {
                newLines.add(line);
            }
        }

        Files.write(hgrc, newLines, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        if (!hasPathsSection) {
            var section = List.of("[paths]",
                                  remote + " = " + (pullPath == null ? "" : pullPath),
                                  remote + "-push = " + (pushPath == null ? "" : pushPath));
            Files.write(hgrc, section, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
    }

    @Override
    public String pullPath(String remote) throws IOException {
        var lines = config("paths." + remote);
        if (lines.size() != 1) {
            throw new IOException("Pull path not found for remote: " + remote);
        }
        return lines.get(0);
    }

    @Override
    public String pushPath(String remote) throws IOException {
        var lines = config("paths." + remote + "-push");
        if (lines.size() != 1) {
            lines = config("paths." + remote + "@push");
        }
        if (lines.size() != 1) {
            return pullPath(remote);
        }
        return lines.get(0);
    }

    @Override
    public boolean isValidRevisionRange(String expression) throws IOException {
        try (var p = capture("hg", "log", "--template", " ", "--rev", expression)) {
            return p.await().status() == 0;
        }
    }

    private void setPermissions(Patch.Info target) throws IOException {
        if (target.path().isPresent() && target.type().isPresent()) {
            var perms = target.type().get().permissions();
            if (perms.isPresent()) {
                Files.setPosixFilePermissions(target.path().get(), perms.get());
            }
        }
    }

    @Override
    public void apply(Diff diff, boolean force) throws IOException {
        var patchFile = Files.createTempFile("import", ".patch");
        diff.toFile(patchFile);
        apply(patchFile, force);
        Files.delete(patchFile);
    }

    @Override
    public void apply(Path patchFile, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "import", "--no-commit"));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(patchFile.toAbsolutePath().toString());
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void copy(Path from, Path to) throws IOException {
        try (var p = capture("hg", "copy", from.toString(), to.toString())) {
            await(p);
        }
    }

    @Override
    public void move(Path from, Path to) throws IOException {
        try (var p = capture("hg", "move", from.toString(), to.toString())) {
            await(p);
        }
    }

    @FunctionalInterface
    private static interface Operation {
        void execute(List<Path> args) throws IOException;
    }

    private void batch(Operation op, List<Path> args) throws IOException {
        var batchSize = 64;
        var start = 0;
        while (start < args.size()) {
            var end = start + batchSize;
            if (end > args.size()) {
                end = args.size();
            }
            op.execute(args.subList(start, end));
            start = end;
        }
    }

    private void addAll(List<Path> paths) throws IOException {
        var cmd = new ArrayList<>(List.of("hg", "add"));
        for (var path : paths) {
            cmd.add(path.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    private void removeAll(List<Path> paths) throws IOException {
        var cmd = new ArrayList<>(List.of("hg", "rm"));
        for (var path : paths) {
            cmd.add(path.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }


    @Override
    public void remove(List<Path> paths) throws IOException {
        batch(this::removeAll, paths);
    }

    @Override
    public void add(List<Path> paths) throws IOException {
        batch(this::addAll, paths);
    }

    @Override
    public void addremove() throws IOException {
        try (var p = capture("hg", "addremove")) {
            await(p);
        }
    }

    @Override
    public Optional<String> upstreamFor(Branch b) throws IOException {
        // Mercurial doesn't have the concept of remotes like git,
        // a local branch must have the same name (if present) on the remote
        return Optional.of(b.name());
    }

    public static Repository clone(URI from, Path to, boolean isBare, Path seed) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "clone"));
        if (isBare) {
            cmd.add("--noupdate");
        }
        cmd.addAll(List.of(from.toString(), to.toString()));

        try (var p = capture(Path.of("").toAbsolutePath(), cmd)) {
            await(p);
        }
        return new HgRepository(to);
    }

    @Override
    public void pull(boolean includeTags) throws IOException {
        pull(null, null, includeTags);
    }

    @Override
    public void pull(String remote, boolean includeTags) throws IOException {
        pull(remote, null, includeTags);
    }

    @Override
    public void pull(String remote, String refspec, boolean includeTags) throws IOException {
        // ignore includeTags, hg always pulls tags
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("hg", "pull", "--update"));
        if (refspec != null) {
            cmd.add("--branch");
            cmd.add(refspec);
        }
        if (remote != null) {
            cmd.add(remote);
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public boolean contains(Branch b, Hash h) throws IOException {
        try (var p = capture("hg", "log", "--template", "{branch}", "-r", h.hex())) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output: " + String.join("\n", res.stdout()));
            }
            var line = res.stdout().get(0);
            return line.equals(b.name());
        }
    }

    @Override
    public List<Reference> remoteBranches(String remote) throws IOException {
        var refs = new ArrayList<Reference>();

        var ext = Files.createTempFile("ext", ".py");
        copyResource(EXT_PY, ext);

        try (var p = capture("hg", "--config", "extensions.ls-remote=" + ext, "ls-remote", remote)) {
            var res = await(p);
            for (var line : res.stdout()) {
                var parts = line.split("\t");
                refs.add(new Reference(parts[1], new Hash(parts[0])));
            }
        }
        return refs;
    }

    @Override
    public List<String> remotes() throws IOException {
        var remotes = new ArrayList<String>();
        try (var p = capture("hg", "paths")) {
            for (var line : await(p).stdout()) {
                var parts = line.split(" = ");
                var name = parts[0];
                if (name.endsWith("-push") || name.endsWith(":push")) {
                    continue;
                } else {
                    remotes.add(name);
                }
            }
        }
        return remotes;
    }

    @Override
    public void addSubmodule(String pullPath, Path path) throws IOException {
        var uri = Files.exists(Path.of(pullPath)) ? Path.of(pullPath).toUri().toString() : pullPath;
        HgRepository.clone(URI.create(uri), root().resolve(path).toAbsolutePath(), false, null);
        var hgSub = root().resolve(".hgsub");
        Files.writeString(hgSub, path.toString() + " = " + pullPath + "\n",
                          StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        add(List.of(hgSub));
    }

    @Override
    public void updateSubmodule(Path path) throws IOException {
        checkout(".", false);
    }

    @Override
    public List<Submodule> submodules() throws IOException {
        var hgSub = root().resolve(".hgsub");
        var hgSubState = root().resolve(".hgsubstate");
        if (!(Files.exists(hgSub) && Files.exists(hgSubState))) {
            return List.of();
        }

        var urls = new HashMap<String, String>();
        for (var line : Files.readAllLines(hgSub)) {
            var parts = line.split("=");
            var path = parts[0].trim();
            var url = parts[1].trim();
            urls.put(path, url);
        }

        var hashes = new HashMap<String, String>();
        for (var line : Files.readAllLines(hgSubState)) {
            var parts = line.split(" ");
            var hash = parts[0];
            var path = parts[1];
            hashes.put(path, hash);
        }

        var modules = new ArrayList<Submodule>();
        for (var path : urls.keySet()) {
            var url = urls.get(path);
            var hash = hashes.get(path);
            modules.add(new Submodule(new Hash(hash), Path.of(path), url));
        }

        return modules;
    }

    @Override
    public Tree tree(Hash h) throws IOException {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Optional<Tag.Annotated> annotate(Tag tag) throws IOException {
        var hgtags = root().resolve(".hgtags");
        if (!Files.exists(hgtags)) {
            return Optional.empty();
        }
        try (var p = capture("hg", "annotate", hgtags.toString())) {
            var reversed = new ArrayList<>(await(p).stdout());
            Collections.reverse(reversed);
            for (var line : reversed) {
                var parts = line.split(" ");
                var tagName = parts[2];
                if (tagName.equals(tag.name())) {
                    var target = new Hash(parts[1]);
                    var rev = parts[0].substring(0, parts[0].length() - 1).trim(); // skip last ':' and ev. whitespace
                    var hash = resolve(rev).orElseThrow(IOException::new);
                    var commit = lookup(hash).orElseThrow(IOException::new);
                    var message = String.join("\n", commit.message());
                    return Optional.of(new Tag.Annotated(tagName, target, commit.author(), commit.authored(), message));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void config(String section, String key, String value, boolean global) throws IOException {
        var hgrc = global ?
            Path.of(System.getProperty("user.home"), ".hgrc") :
            root().resolve(".hg").resolve("hgrc");

        var lines = List.of(
            "[" + section + "]",
            key + " = " + value
        );
        if (!Files.exists(hgrc)) {
            Files.createFile(hgrc);
        }
        Files.write(hgrc, lines, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    @Override
    public String range(Hash h) {
        return h.hex();
    }

    @Override
    public String rangeInclusive(Hash from, Hash to) {
        return from.hex() + ":" + to.hex();
    }

    @Override
    public String rangeExclusive(Hash from, Hash to) {
        return from.hex() + ":" + to.hex() + "-" + from.hex();
    }

    @Override
    public boolean cherryPick(Hash hash) throws IOException {
        try (var p = capture("hg", "graft", "--no-commit", "--force", hash.hex())) {
            return p.await().status() == 0;
        }
    }

    @Override
    public int commitCount() throws IOException {
        try (var p = capture("hg", "id", "--num", "--rev", "tip")) {
            return Integer.parseInt(await(p).stdout().get(0)) + 1;
        }
    }

    @Override
    public int commitCount(List<Branch> branches) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Hash initialHash() {
        return NULL_REVISION;
    }

    @Override
    public Optional<List<String>> stagedFileContents(Path p) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Commit staged() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Commit workingTree() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmptyCommit(Hash hash) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addNote(Hash hash,
                        List<String> lines,
                        String authorName,
                        String authorEmail,
                        String committerName,
                        String committerEmail) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> notes(Hash hash) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void pushNotes(URI uri) throws IOException {
        throw new UnsupportedOperationException();
    }
}
