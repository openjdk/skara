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
package org.openjdk.skara.vcs.git;

import org.openjdk.skara.process.*;
import org.openjdk.skara.process.Process;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GitRepository implements Repository {
    private final static Map<String, String> NO_CONFIG_ENV = Map.of(
            "HOME", "/this-does-not-exist-and-if-you-create-it-you-are-in-trouble",
            "XDG_CONFIG_HOME", "/this-does-not-exist-and-if-you-create-it-you-are-in-trouble",
            "GIT_CONFIG_NOSYSTEM", "true"
    );

    public static Map<String, String> currentEnv = Collections.emptyMap();
    private final Path dir;
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.git");
    private Path cachedRoot = null;
    private static final Hash EMPTY_TREE = new Hash("4b825dc642cb6eb9a060e54bf8d69288fbee4904");

    public static void ignoreConfiguration() {
        currentEnv = NO_CONFIG_ENV;
    }

    private java.lang.Process start(String... cmd) throws IOException {
        return start(Arrays.asList(cmd));
    }

    private java.lang.Process start(List<String> cmd) throws IOException {
        log.fine("Executing " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.environment().putAll(currentEnv);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
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

    private static Execution capture(Path cwd, Map<String, String> env, List<String> cmd) {
        return capture(cwd, env, cmd.toArray(new String[0]));
    }

    private Execution capture(String... cmd) {
        return capture(dir, cmd);
    }

    public static Execution capture(Path cwd, String... cmd) {
        return capture(cwd, currentEnv, cmd);
    }

    private static Execution capture(Path cwd, Map<String, String> env, String... cmd) {
        return Process.capture(cmd)
                      .workdir(cwd)
                      .environ(env)
                      .execute();
    }

    private static Execution capture(Path cwd, List<String> cmd) {
        return capture(cwd, cmd.toArray(new String[0]));
    }

    private static Execution.Result await(Execution e) throws IOException {
        var result = e.await();
        if (result.status() != 0) {
            throw new IOException("Unexpected exit code\n" + result);
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

    public GitRepository(Path dir) {
        this.dir = dir.toAbsolutePath();
    }

    public List<Branch> branches() throws IOException {
        try (var p = capture("git", "for-each-ref", "--format=%(refname:short)", "refs/heads")) {
            return await(p).stdout()
                           .stream()
                           .map(Branch::new)
                           .collect(Collectors.toList());
        }
    }

    public List<Branch> branches(String remote) throws IOException {
        try (var p = capture("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/" + remote + "/")) {
            return await(p).stdout()
                           .stream()
                           .map(Branch::new)
                           .collect(Collectors.toList());
        }
    }

    public List<Tag> tags() throws IOException {
        try (var p = capture("git", "for-each-ref", "--format=%(refname:short)", "refs/tags")) {
            return await(p).stdout()
                           .stream()
                           .map(Tag::new)
                           .collect(Collectors.toList());
        }
    }

    @Override
    public Commits commits() throws IOException {
        return new GitCommits(dir, "--all", false, -1);
    }

    @Override
    public Commits commits(int n) throws IOException {
        return new GitCommits(dir, "--all", false, n);
    }

    @Override
    public Commits commits(boolean reverse) throws IOException {
        return new GitCommits(dir, "--all", reverse, -1);
    }

    @Override
    public Commits commits(int n, boolean reverse) throws IOException {
        return new GitCommits(dir, "--all", reverse, n);
    }

    @Override
    public Commits commits(List<Hash> reachableFrom, List<Hash> unreachableFrom) throws IOException {
        return new GitCommits(dir, reachableFrom, unreachableFrom);
    }

    @Override
    public Commits commits(String range) throws IOException {
        return new GitCommits(dir, range, false, -1);
    }

    @Override
    public Commits commits(String range, int n) throws IOException {
        return new GitCommits(dir, range, false, n);
    }

    @Override
    public Commits commits(String range, boolean reverse) throws IOException {
        return new GitCommits(dir, range, reverse, -1);
    }

    @Override
    public Commits commits(String range, int n, boolean reverse) throws IOException {
        return new GitCommits(dir, range, reverse, n);
    }

    @Override
    public boolean contains(Hash h) throws IOException {
        try (var p = capture("git", "cat-file", "-e", h.hex())) {
            var res = p.await();
            return res.status() == 0;
        }
    }

    @Override
    public Optional<Commit> lookup(Hash h) throws IOException {
        if (!contains(h)) {
            return Optional.empty();
        }

        var commits = commits(h.hex(), 1).asList();
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
    public List<CommitMetadata> commitMetadata(String range, List<Path> paths, boolean reverse) throws IOException {
        var args = new ArrayList<String>();
        args.addAll(List.of("git", "rev-list",
                                   "--format=" + GitCommitMetadata.FORMAT,
                                   "--topo-order",
                                   "--no-abbrev",
                                   "--no-color",
                                   range));
        if (reverse) {
            args.add("--reverse");
        }
        if (paths != null && !paths.isEmpty()) {
            args.add("--");
            for (var path : paths) {
                args.add(path.toString());
            }
        }
        return readMetadata(args, "commit ");
    }

    @Override
    public List<CommitMetadata> commitMetadataFor(List<Branch> branches) throws IOException {
        var args = new ArrayList<String>();
        args.addAll(List.of("git", "rev-list",
                                   "--format=" + GitCommitMetadata.FORMAT,
                                   "--topo-order",
                                   "--no-abbrev",
                                   "--no-color"));
        args.addAll(branches.stream().map(Branch::name).collect(Collectors.toList()));
        args.add("--");
        return readMetadata(args, "commit ");
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to, List<Path> paths, boolean reverse) throws IOException {
        return commitMetadata(from.hex() + ".." + to.hex(), paths, reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range, List<Path> paths) throws IOException {
        return commitMetadata(range, paths, false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to, List<Path> paths) throws IOException {
        return commitMetadata(from.hex() + ".." + to.hex(), paths, false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(boolean reverse) throws IOException {
        return commitMetadata("--all", List.of(), reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range) throws IOException {
        return commitMetadata(range, List.of(), false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to) throws IOException {
        return commitMetadata(from.hex() + ".." + to.hex(), List.of(), false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(String range, boolean reverse) throws IOException {
        return commitMetadata(range, List.of(), reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(Hash from, Hash to, boolean reverse) throws IOException {
        return commitMetadata(from.hex() + ".." + to.hex(), List.of(), reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata(List<Path> paths) throws IOException {
        return commitMetadata("--all", paths, false);
    }

    @Override
    public List<CommitMetadata> commitMetadata(List<Path> paths, boolean reverse) throws IOException {
        return commitMetadata("--all", paths, reverse);
    }

    @Override
    public List<CommitMetadata> commitMetadata() throws IOException {
        return commitMetadata("--all");
    }

    private List<CommitMetadata> readMetadata(List<String> cmd, String delimiter) throws IOException {
        var p = start(cmd);
        var reader = new UnixStreamReader(p.getInputStream());
        var result = new ArrayList<CommitMetadata>();

        var line = reader.readLine();
        while (line != null) {
            if (!line.startsWith(delimiter)) {
                throw new IOException("Unexpected line: " + line);
            }

            result.add(GitCommitMetadata.read(reader));
            line = reader.readLine();
        }

        await(p);
        return result;
    }

    @Override
    public List<CommitMetadata> follow(Path path) throws IOException {
        return follow(path, null, null);
    }

    @Override
    public List<CommitMetadata> follow(Path path, Hash from, Hash to) throws IOException {
        var delimiter = "#@!_-=&";
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "log",
                                  "-c",
                                  "--no-patch",
                                  "--full-history",
                                  "--follow",
                                  "--format=" + delimiter + "\n" + GitCommitMetadata.FORMAT,
                                  "--topo-order",
                                  "--no-abbrev",
                                  "--no-color"));
        if (from != null && to != null) {
            cmd.add(from.hex() + ".." + to.hex());
        }
        cmd.add("--");
        cmd.add(path.toString());
        return readMetadata(cmd, delimiter);
    }


    private List<Hash> refs() throws IOException {
        try (var p = capture("git", "show-ref", "--hash", "--abbrev")) {
            var res = p.await();
            if (res.status() == -1) {
                if (res.stdout().size() != 0) {
                    throw new IOException("Unexpected output\n" + res);
                }
                return new ArrayList<>();
            } else {
                return res.stdout().stream()
                          .map(Hash::new)
                          .collect(Collectors.toList());
            }
        }
    }

    @Override
    public boolean isEmpty() throws IOException {
        int numLooseObjects = -1;
        int numPackedObjects = -1;

        try (var p = capture("git", "count-objects", "-v")) {
            var res = await(p);
            var stdout = res.stdout();

            for (var line : stdout) {
                if (line.startsWith("count: ")) {
                    try {
                        numLooseObjects = Integer.parseUnsignedInt(line.split(" ")[1]);
                    } catch (NumberFormatException e) {
                        throw new IOException("Unexpected 'count' value\n" + res, e);
                    }

                } else if (line.startsWith("in-pack: ")) {
                    try {
                        numPackedObjects = Integer.parseUnsignedInt(line.split(" ")[1]);
                    } catch (NumberFormatException e) {
                        throw new IOException("Unexpected 'in-pack' value\n" + res, e);
                    }
                }
            }
        }

        return numLooseObjects == 0 && numPackedObjects == 0 && refs().size() == 0;
    }

    @Override

    public boolean isHealthy() throws IOException {
        try (var p = capture("git", "fsck", "--connectivity-only")) {
            if (p.await().status() != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void clean() throws IOException {
        cachedRoot = null;

        try (var p = capture("git", "clean", "-x", "-d", "--force", "--force")) {
            await(p);
        }

        try (var p = capture("git", "reset", "--hard")) {
            await(p);
        }

        try (var p = capture("git", "rebase", "--quit")) {
            p.await(); // Don't care about the result.
        }
    }

    @Override
    public void deleteUntrackedFiles() throws IOException {
        var root = root();
        try (var p = capture("git", "ls-files", "--full-name", "--other")) {
            var res = await(p);
            for (var line : res.stdout()) {
                Files.delete(root.resolve(line));
            }
        }
    }

    @Override
    public void reset(Hash target, boolean hard) throws IOException {
        var cmd = new ArrayList<>(List.of("git", "reset"));
        if (hard) {
           cmd.add("--hard");
        }
        cmd.add(target.hex());

        try (var p = capture(cmd)) {
            await(p);
        }
    }


    @Override
    public void revert(Hash h) throws IOException {
        try (var p = capture("git", "restore", "--recurse-submodules", "--source", h.hex(), "--", ".")) {
            await(p);
        }
    }

    @Override
    public Repository reinitialize() throws IOException {
        cachedRoot = null;

        try (var paths = Files.walk(dir)) {
            paths.map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);
        }

        return init();
    }

    @Override
    public Optional<Hash> fetch(URI uri, String refspec, boolean includeTags, boolean forceUpdateTags) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "fetch", "--recurse-submodules=on-demand"));
        if (includeTags) {
            cmd.add("--tags");
            if (forceUpdateTags) {
                cmd.add("--force");
            }
        } else {
            cmd.add("--no-tags");
        }
        cmd.add(uri.toString());
        cmd.add(refspec);
        try (var p = capture(cmd)) {
            await(p);
            return resolve("FETCH_HEAD");
        }
    }

    @Override
    public void fetchAll(URI uri, boolean includeTags) throws IOException {
        var cmd = new ArrayList<>(List.of("git", "fetch", "--recurse-submodules=on-demand", "--prune", uri.toString()));
        cmd.add("+refs/heads/*:refs/heads/*");
        if (includeTags) {
            cmd.add("+refs/tags/*:refs/tags/*");
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void fetchAllRemotes(boolean includeTags) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "fetch", "--recurse-submodules=on-demand"));
        cmd.add("--prune");
        if (includeTags) {
            cmd.add("--tags");
            cmd.add("--prune-tags");
        } else {
            cmd.add("--no-tags");
        }
        cmd.add("--all");
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void fetchRemote(String remote) throws IOException {
        var lines = config("remote." + remote + ".fetch");
        var refspec = lines.size() == 1 ? lines.get(0) : "+refs/heads/*:refs/remotes/" + remote + "/*";
        try (var p = capture("git", "fetch", "--recurse-submodules=on-demand", "--prune", remote, refspec, "+refs/tags/*:refs/tags/*")) {
            await(p);
        }
    }

    private void checkout(String ref, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "advice.detachedHead=false", "checkout", "--recurse-submodules"));
        if (force) {
            cmd.add("--force");
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
    public Repository init() throws IOException {
        cachedRoot = null;

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        try (var p = capture("git", "init")) {
            await(p);
            return this;
        }
    }

    @Override
    public void pushAll(URI uri, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "push", "--mirror"));
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
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "push", "--tags"));
        if (force) {
            cmd.add("--force");
        }
        cmd.add(uri.toString());
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void push(Hash hash, URI uri, String ref, boolean force, boolean includeTags) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "push"));

        if (includeTags) {
            cmd.add("--tags");
            if (force) {
                cmd.add("--force");
            }
        }

        cmd.add(uri.toString());

        /*
         * https://git-scm.com/docs/git-push
         * Specify what destination ref to update with what source object.
         * The format of a <refspec> parameter is an optional plus +, followed by
         * the source object, followed by a colon : and finally by the destination
         * ref.
         */
        String refspec = force ? "+" : "";
        if (!ref.startsWith("refs/")) {
            ref = "refs/heads/" + ref;
        }
        refspec += hash.hex() + ":" + ref;
        cmd.add(refspec);

        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void push(Tag tag, URI uri, boolean force) throws IOException {
        var refspec = force ? "+" : "";
        refspec += "refs/tags/" + tag.name() + ":refs/tags/" + tag.name();

        try (var p = capture("git", "push", uri.toString(), refspec)) {
            await(p);
        }
    }

    @Override
    public void push(String refspec, URI uri) throws IOException {
        try (var p = capture("git", "push", uri.toString(), refspec)) {
            await(p);
        }
    }

    @Override
    public void push(Branch branch, String remote, boolean setUpstream) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "push", remote, branch.name()));
        if (setUpstream) {
            cmd.add("--set-upstream");
        }

        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public boolean isClean() throws IOException {
        try (var p = capture("git", "status", "--porcelain")) {
            var output = await(p);
            return output.stdout().size() == 0;
        }
    }

    @Override
    public boolean exists() throws IOException {
        if (!Files.exists(dir)) {
            return false;
        }

        try (var p = capture("git", "rev-parse", "--git-dir")) {
            return p.await().status() == 0;
        }
    }

    @Override
    public Path root() throws IOException {
        if (cachedRoot != null) {
            return cachedRoot;
        }

        try (var p = capture("git", "rev-parse", "--show-toplevel")) {
            var res = p.await();
            if (res.status() != 0 || res.stdout().size() != 1) {
                // Perhaps this is a bare repository
                try (var p2 = capture("git", "rev-parse", "--git-dir")) {
                    var res2 = await(p2);
                    if (res2.stdout().size() != 1) {
                        throw new IOException("Unexpected output\n" + res2);
                    }
                    cachedRoot = dir.resolve(Path.of(res2.stdout().get(0)));
                    return cachedRoot;
                }
            }

            cachedRoot = Path.of(res.stdout().get(0));
            return cachedRoot;
        }
    }

    @Override
    public void squash(Hash h) throws IOException {
        try (var p = capture("git", "merge", "--squash", h.hex())) {
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
        var cmd = new ArrayList<>(List.of("git", "add"));
        for (var path : paths) {
            cmd.add(path.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void add(List<Path> paths) throws IOException {
        batch(this::addAll, paths);
    }

    private void removeAll(List<Path> paths) throws IOException {
        var cmd = new ArrayList<>(List.of("git", "rm"));
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
    public void delete(Branch b) throws IOException {
        try (var p = capture("git", "branch", "-D", b.name())) {
            await(p);
        }
    }

    @Override
    public void addremove() throws IOException {
        try (var p = capture("git", "add", "--all")) {
            await(p);
        }
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail)  throws IOException {
        return commit(message, authorName, authorEmail, null);
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail, ZonedDateTime authorDate)  throws IOException {
        return commit(message, authorName, authorEmail, authorDate, authorName, authorEmail, authorDate);
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
        var cmd = Process.capture("git", "commit", "--message=" + message)
                         .workdir(dir)
                         .environ(currentEnv)
                         .environ("GIT_AUTHOR_NAME", authorName)
                         .environ("GIT_AUTHOR_EMAIL", authorEmail)
                         .environ("GIT_COMMITTER_NAME", committerName)
                         .environ("GIT_COMMITTER_EMAIL", committerEmail);
        if (authorDate != null) {
            cmd = cmd.environ("GIT_AUTHOR_DATE",
                              authorDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        if (committerDate != null) {
            cmd = cmd.environ("GIT_COMMITTER_DATE",
                              committerDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        try (var p = cmd.execute()) {
            await(p);
            return head();
        }
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail, ZonedDateTime authorDate, String committerName, String committerEmail, ZonedDateTime committerDate, List<Hash> parents, Tree tree) throws IOException {
        // Ensure we don't create identical commits
        if (parents.size() == 1) {
            var parentTree = tree(parents.get(0));
            if (parentTree.equals(tree)) {
                return parents.get(0);
            }
        }

        var cmdLine = new ArrayList<>(List.of("git", "commit-tree", tree.hash().hex(), "-m", message));
        for (var parent : parents) {
            cmdLine.add("-p");
            cmdLine.add(parent.hex());
        }
        var cmd = Process.capture(cmdLine.toArray(new String[0]))
                .workdir(dir)
                .environ(currentEnv)
                .environ("GIT_AUTHOR_NAME", authorName)
                .environ("GIT_AUTHOR_EMAIL", authorEmail)
                .environ("GIT_COMMITTER_NAME", committerName)
                .environ("GIT_COMMITTER_EMAIL", committerEmail);
        if (authorDate != null) {
            cmd = cmd.environ("GIT_AUTHOR_DATE",
                    authorDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        if (committerDate != null) {
            cmd = cmd.environ("GIT_COMMITTER_DATE",
                    committerDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        try (var p = cmd.execute()) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output: " + res.stdout());
            }
            var commitHash = res.stdout().get(0).trim();
            if (commitHash.length() != 40) {
                throw new IOException("Unexpected output: " + commitHash);
            }
            return new Hash(commitHash);
        }
    }

    @Override
    public Hash amend(String message) throws IOException {
        return amend(message, null, null, null, null);
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail) throws IOException {
        return amend(message, authorName, authorEmail, null, null);
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail, String committerName, String committerEmail) throws IOException {
        if (authorName == null || authorEmail == null) {
            var head = lookup(head()).orElseThrow();
            if (authorName == null) {
                authorName = head.author().name();
            }
            if (authorEmail == null) {
                authorEmail = head.author().email();
            }
        }
        if (committerName == null) {
            committerName = authorName;
            committerEmail = authorEmail;
        }
        var cmd = Process.capture("git", "commit", "--amend", "--reset-author", "--message=" + message)
                         .workdir(dir)
                         .environ(currentEnv)
                         .environ("GIT_AUTHOR_NAME", authorName)
                         .environ("GIT_AUTHOR_EMAIL", authorEmail)
                         .environ("GIT_COMMITTER_NAME", committerName)
                         .environ("GIT_COMMITTER_EMAIL", committerEmail);
        try (var p = cmd.execute()) {
            await(p);
            return head();
        }
    }

    @Override
    public Tag tag(Hash hash, String name, String message, String authorName, String authorEmail, ZonedDateTime date, boolean force) throws IOException {
        var cmdLine = new ArrayList<>(List.of("git", "tag", "--annotate", "--message=" + message, name, hash.hex()));
        if (force) {
            cmdLine.add("--force");
        }
        var cmd = Process.capture(cmdLine.toArray(new String[0]))
                         .workdir(dir)
                         .environ(currentEnv)
                         .environ("GIT_AUTHOR_NAME", authorName)
                         .environ("GIT_AUTHOR_EMAIL", authorEmail)
                         .environ("GIT_COMMITTER_NAME", authorName)
                         .environ("GIT_COMMITTER_EMAIL", authorEmail);
        if (date != null) {
            cmd = cmd.environ("GIT_AUTHOR_DATE", date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            cmd = cmd.environ("GIT_COMMITTER_DATE", date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        try (var p = cmd.execute()) {
            await(p);
        }

        return new Tag(name);
    }

    @Override
    public Branch branch(Hash hash, String name) throws IOException {
        try (var p = capture("git", "branch", name, hash.hex())) {
            await(p);
        }

        return new Branch(name);
    }

    @Override
    public void prune(Branch branch, String remote) throws IOException {
        try (var p = capture("git", "push", "--delete", remote, branch.name())) {
            await(p);
        }
        try (var p = capture("git", "branch", "--delete", "--force", branch.name())) {
            await(p);
        }
    }

    @Override
    public Optional<Hash> mergeBaseOptional(Hash first, Hash second) throws IOException {
        try (var p = capture("git", "merge-base", first.hex(), second.hex())) {
            var res = p.await();
            if (res.status() == 1 && res.stdout().size() == 0) {
                return Optional.empty();
            }
            if (res.status() != 0) {
                throw new IOException("Unexpected exit code: " + res);
            }
            if (res.stdout().size() != 1) {
                 throw new IOException("Unexpected output\n" + res);
            }
            return Optional.of(new Hash(res.stdout().get(0)));
        }
    }

    @Override
    public Hash mergeBase(Hash first, Hash second) throws IOException {
        return mergeBaseOptional(first, second)
                .orElseThrow(() -> new IOException("Could not find merge-base between " + first + " and " + second));
    }

    @Override
    public boolean isAncestor(Hash ancestor, Hash descendant) throws IOException {
        try (var p = capture("git", "merge-base", "--is-ancestor", ancestor.hex(), descendant.hex())) {
            var res = p.await();
            return res.status() == 0;
        }
    }

    @Override
    public void rebase(Hash hash, String committerName, String committerEmail) throws IOException {
        try (var p = Process.capture("git", "rebase", "--onto", hash.hex(), "--root")
                            .environ("GIT_COMMITTER_NAME", committerName)
                            .environ("GIT_COMMITTER_EMAIL", committerEmail)
                            .workdir(dir)
                            .environ(currentEnv)
                            .execute()) {
            await(p);
        }
    }

    @Override
    public boolean isRemergeDiffEmpty(Hash mergeCommitHash) throws IOException {
        // requires git 2.36 or newer
        try (var p = Process.capture("git", "show", "--remerge-diff", "--format=%b", mergeCommitHash.hex())
                .workdir(dir)
                .environ(currentEnv)
                .execute()) {
            return String.join("", await(p).stdout()).isEmpty();
        }
    }

    @Override
    public Optional<Hash> resolve(String ref) throws IOException {
        try (var p = capture("git", "rev-parse", ref + "^{commit}")) {
            var res = p.await();
            if (res.status() == 0 && res.stdout().size() == 1) {
                return Optional.of(new Hash(res.stdout().get(0)));
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<Branch> currentBranch() throws IOException {
        try (var p = capture("git", "symbolic-ref", "--short", "HEAD")) {
            var res = p.await();
            if (res.status() == 0 && res.stdout().size() == 1) {
                return Optional.of(new Branch(res.stdout().get(0)));
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<Bookmark> currentBookmark() throws IOException {
        throw new RuntimeException("git does not have bookmarks");
    }

    @Override
    public Branch defaultBranch() throws IOException {
        try (var p = capture("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")) {
            var res = p.await();
            if (res.status() == 0 && res.stdout().size() == 1) {
                var ref = res.stdout().get(0).substring("origin/".length());
                return new Branch(ref);
            } else {
                return Branch.defaultFor(VCS.GIT);
            }
        }
    }

    @Override
    public Optional<Tag> defaultTag() throws IOException {
        return Optional.empty();
    }

    @Override
    public Optional<String> username() throws IOException {
        var lines = config("user.name");
        return lines.size() == 1 ? Optional.of(lines.get(0)) : Optional.empty();
    }

    private Optional<String> email() throws IOException {
        var lines = config("user.email");
        return lines.size() == 1 ? Optional.of(lines.get(0)) : Optional.empty();
    }

    private String treeEntry(Path path, Hash hash) throws IOException {
        try (var p = Process.capture("git", "-c", "core.quotePath=false", "ls-tree", hash.hex(), path.toString())
                            .workdir(root())
                            .execute()) {
            var res = await(p);
            if (res.stdout().size() == 0) {
                return null;
            }
            if (res.stdout().size() > 1) {
                throw new IOException("Unexpected output\n" + res);
            }
            return res.stdout().get(0);
        }
    }

    private List<FileEntry> allFiles(Hash hash, List<Path> paths) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "core.quotePath=false", "ls-tree", "-r"));
        cmd.add(hash.hex());
        cmd.addAll(paths.stream().map(Path::toString).collect(Collectors.toList()));
        try (var p = Process.capture(cmd.toArray(new String[0]))
                            .workdir(root())
                            .execute()) {
            var res = await(p);
            var entries = new ArrayList<FileEntry>();
            for (var line : res.stdout()) {
                var parts = line.split("\t");
                var metadata = parts[0].split(" ");
                var filename = parts[1];

                var entry = new FileEntry(hash,
                                          FileType.fromOctal(metadata[0]),
                                          new Hash(metadata[2]),
                                          Path.of(filename));
                entries.add(entry);
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

    private Path unpackFile(String blob) throws IOException {
        try (var p = capture("git", "unpack-file", blob)) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output\n" + res);
            }

            return Path.of(root().toString(), res.stdout().get(0));
        }
    }

    @Override
    public Optional<byte[]> show(Path path, Hash hash) throws IOException {
        var entries = files(hash, path);
        if (entries.size() == 0) {
            return Optional.empty();
        } else if (entries.size() > 1) {
            throw new IOException("Multiple files match path " + path.toString() + " in commit " + hash.hex());
        }

        var entry = entries.get(0);
        var type = entry.type();
        if (type.isVCSLink()) {
            var content = "Subproject commit " + entry.hash().hex() + " " + entry.path().toString();
            return Optional.of(content.getBytes(StandardCharsets.UTF_8));
        } else if (type.isRegular()) {
            var tmp = unpackFile(entry.hash().hex());
            var content = Files.readAllBytes(tmp);
            Files.delete(tmp);
            return Optional.of(content);
        }

        return Optional.empty();
    }

    @Override
    public void dump(FileEntry entry, Path to) throws IOException {
        var type = entry.type();
        if (type.isRegular()) {
            var path = unpackFile(entry.hash().hex());
            Files.createDirectories(to.getParent());
            Files.move(path, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public List<StatusEntry> status(Hash from, Hash to) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "core.quotePath=false", "diff", "--raw",
                                          "--find-renames=90%",
                                          "--find-copies=90%",
                                          "--find-copies-harder",
                                          "--no-abbrev",
                                          "--no-color"));
        if (from != null) {
            if (from.equals(Hash.zero())) {
                cmd.add(EMPTY_TREE.hex());
            } else {
                cmd.add(from.hex());
            }
        }
        if (to != null) {
            cmd.add(to.hex());
        }
        try (var p = capture(cmd)) {
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
        return status(null, null);
    }

    @Override
    public Diff diff(Hash from, int similarity) throws IOException {
        return diff(from, List.of(), similarity);
    }

    @Override
    public Diff diff(Hash from, List<Path> files, int similarity) throws IOException {
        return diff(from, null, files, similarity);
    }

    @Override
    public Diff diff(Hash from, Hash to, int similarity) throws IOException {
        return diff(from, to, List.of(), similarity);
    }

    @Override
    public Diff diff(Hash from, Hash to, List<Path> files, int similarity) throws IOException {
        if (similarity < 0 || similarity > 100) {
            throw new IllegalArgumentException("similarity must be between 0 and 100, is: "  + similarity);
        }
        var cmd = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "diff", "--patch",
                                                         "--find-renames=" + similarity + "%",
                                                         "--find-copies=" + similarity + "%",
                                                         "--find-copies-harder",
                                                         "--binary",
                                                         "--raw",
                                                         "--no-abbrev",
                                                         "--unified=0",
                                                         "--no-color"));
        if (from != null) {
            if (from.equals(Hash.zero())) {
                cmd.add(EMPTY_TREE.hex());
            } else {
                cmd.add(from.hex());
            }
        }
        if (to != null) {
            cmd.add(to.hex());
        }

        if (files != null && !files.isEmpty()) {
            cmd.add("--");
            for (var file : files) {
                cmd.add(file.toString());
            }
        }

        var p = start(cmd);
        try {
            var patches = GitRawDiffParser.parse(p.getInputStream());
            await(p);
            return new Diff(from, to, patches);
        } catch (Throwable t) {
            stop(p);
            throw t;
        }
    }

    @Override
    public List<String> config(String key) throws IOException {
        // We must explicitly do this *with* the user's .gitconfig, so override NO_CONFIG_ENV
        try (var p = capture(dir, Collections.emptyMap(), "git", "config", key)) {
            var res = p.await();
            return res.status() == 0 ? res.stdout() : List.of();
        }
    }

    @Override
    public Hash head() throws IOException {
        return resolve("HEAD").orElseThrow(() -> new IllegalStateException("HEAD ref is not present"));
    }

    public static Optional<Repository> get(Path p) throws IOException {
        if (!Files.exists(p)) {
            return Optional.empty();
        }

        var r = new GitRepository(p);
        return r.exists() ? Optional.of(new GitRepository(r.root())) : Optional.empty();
    }

    @Override
    public Repository copyTo(Path destination) throws IOException {
        try (var p = capture("git", "clone", "--recurse-submodules", root().toString(), destination.toString())) {
            await(p);
        }

        return new GitRepository(destination);
    }

    @Override
    public void merge(Hash h, FastForward ff) throws IOException {
        merge(h.hex(), null, ff);
    }

    @Override
    public void merge(Branch b, FastForward ff) throws IOException {
        merge(b.name(), null, ff);
    }

    @Override
    public void merge(Hash h, String strategy, FastForward ff) throws IOException {
        merge(h.hex(), strategy, ff);
    }

    private void merge(String ref, String strategy, FastForward ff) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "user.name=unused", "-c", "user.email=unused",
                           "merge", "--no-commit"));

        if (ff == FastForward.AUTO) {
            cmd.add("--ff");
        } else if (ff == FastForward.DISABLE) {
            cmd.add("--no-ff");
        } else if (ff == FastForward.ONLY) {
            cmd.add("--ff-only");
        } else {
            throw new IllegalArgumentException("Unexpected fast forward value: " + ff);
        }

        if (strategy != null) {
            cmd.add("-s");
            cmd.add(strategy);
        }

        cmd.add(ref);
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void abortMerge() throws IOException {
        try (var p = capture("git", "merge", "--abort")) {
            await(p);
        }
    }

    @Override
    public void addRemote(String name, String pullPath) throws IOException {
        try (var p = capture("git", "remote", "add", name, pullPath)) {
            await(p);
        }
    }

    @Override
    public void setPaths(String remote, String pullPath, String pushPath) throws IOException {
        pullPath = pullPath == null ? "" : pullPath;
        try (var p = capture("git", "config", "remote." + remote + ".url", pullPath)) {
            await(p);
        }

        pushPath = pushPath == null ? "" : pushPath;
        try (var p = capture("git", "config", "remote." + remote + ".pushurl", pushPath)) {
            await(p);
        }
    }

    @Override
    public String pullPath(String remote) throws IOException {
        var lines = config("remote." + remote + ".url");
        if (lines.size() != 1) {
            throw new IOException("No pull path found for remote " + remote);
        }
        return lines.get(0);
    }

    @Override
    public String pushPath(String remote) throws IOException {
        var lines = config("remote." + remote + ".pushurl");
        if (lines.size() != 1) {
            return pullPath(remote);
        }
        return lines.get(0);
    }

    @Override
    public boolean isValidRevisionRange(String expression) throws IOException {
        try (var p = capture("git", "rev-parse", expression)) {
            return p.await().status() == 0;
        }
    }

    private void applyPatch(Patch patch) throws IOException {
        if (patch.isEmpty()) {
            return;
        }

        if (patch.isTextual()) {
        } else {
            throw new IllegalArgumentException("Cannot handle binary patches yet");
        }
    }

    @Override
    public void apply(Diff diff, boolean force) throws IOException {
        // ignore force, no such concept in git
        var patchFile = Files.createTempFile("apply", ".patch");
        diff.toFile(patchFile);
        apply(patchFile, force);
        Files.delete(patchFile);
    }

    @Override
    public void apply(Path patchFile, boolean force)  throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "apply", "--index", "--unidiff-zero"));
        cmd.add(patchFile.toAbsolutePath().toString());
        try (var p = capture(cmd)) {
            await(p);
            Files.delete(patchFile);
        }
    }

    @Override
    public void copy(Path from, Path to) throws IOException {
        Files.copy(from, to);
        add(to);
    }

    @Override
    public void move(Path from, Path to) throws IOException {
        try (var p = capture("git", "mv", from.toString(), to.toString())) {
            await(p);
        }
    }

    @Override
    public Optional<String> upstreamFor(Branch b) throws IOException {
        try (var p = capture("git", "for-each-ref", "--format=%(upstream:short)", "refs/heads/" + b.name())) {
            var lines = await(p).stdout();
            return lines.size() == 1 && !lines.get(0).isEmpty()? Optional.of(lines.get(0)) : Optional.empty();
        }
    }

    public static Repository clone(URI from, Path to, boolean isBare, Path seed) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "clone"));
        if (isBare) {
            cmd.add("--bare");
        } else {
            cmd.add("--recurse-submodules");
        }
        if (seed != null) {
            cmd.add("--reference-if-able");
            cmd.add(seed.toString());
            // It's not safe to keep an alternates pointer back to the seed repo as we sometimes
            // delete objects, which will cause clones to become corrupt.
            cmd.add("--dissociate");
        }
        cmd.addAll(List.of(from.toString(), to.toString()));
        try (var p = capture(Path.of("").toAbsolutePath(), cmd)) {
            await(p);
        }
        return new GitRepository(to);
    }

    public static Repository mirror(URI from, Path to) throws IOException {
        var cwd = Path.of("").toAbsolutePath();
        try (var p = capture(cwd, "git", "clone", "--mirror", from.toString(), to.toString())) {
            await(p);
        }
        return new GitRepository(to);
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
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.add("pull");
        cmd.add("--recurse-submodules");
        if (includeTags) {
            cmd.add("--tags");
        } else {
            cmd.add("--no-tags");
        }
        if (remote != null) {
            cmd.add(remote);
        }
        if (refspec != null) {
            cmd.add(refspec);
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public boolean contains(Branch b, Hash h) throws IOException {
        try (var p = capture("git", "for-each-ref", "--contains", h.hex(), "--format", "%(refname:short)")) {
            var res = await(p);
            for (var line : res.stdout()) {
                if (line.equals(b.name())) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public List<Reference> remoteBranches(String remote) throws IOException {
        var refs = new ArrayList<Reference>();
        try (var p = capture("git", "ls-remote", "--heads", "--refs", remote)) {
            for (var line : await(p).stdout()) {
                var parts = line.split("\t");
                var name = parts[1].replace("refs/heads/", "");
                refs.add(new Reference(name, new Hash(parts[0])));
            }
        }
        return refs;
    }

    @Override
    public List<String> remotes() throws IOException {
        var remotes = new ArrayList<String>();
        try (var p = capture("git", "remote")) {
            for (var line : await(p).stdout()) {
                remotes.add(line);
            }
        }
        return remotes;
    }

    @Override
    public void updateSubmodule(Path path) throws IOException {
        try (var p = capture("git", "submodule", "update", path.toString())) {
            await(p);
        }
    }

    @Override
    public void addSubmodule(String pullPath, Path path) throws IOException {
        try (var p = capture("git", "-c", "protocol.file.allow=always", "submodule", "add", pullPath, path.toString())) {
            await(p);
        }
    }

    @Override
    public List<Submodule> submodules() throws IOException {
        var gitModules = root().resolve(".gitmodules");
        if (!Files.exists(gitModules)) {
            return List.of();
        }

        var urls = new HashMap<String, String>();
        var paths = new HashMap<String, String>();
        try (var p = capture("git", "config", "--file", gitModules.toAbsolutePath().toString(),
                                              "--list")) {
            for (var line : await(p).stdout()) {
                if (line.startsWith("submodule.")) {
                    line = line.substring("submodule.".length());
                    var parts = line.split("=");
                    var nameAndProperty = parts[0].split("\\.");
                    var name = nameAndProperty[0];
                    var prop = nameAndProperty[1];
                    var value = parts[1];
                    if (prop.equals("path")) {
                        paths.put(name, value);
                    } else if (prop.equals("url")) {
                        urls.put(name, value);
                    } else {
                        throw new IOException("Unexpected submodule property: " + prop);
                    }
                }
            }
        }

        var hashes = new HashMap<String, String>();
        try (var p = capture("git", "submodule", "status")) {
            for (var line : await(p).stdout()) {
                var parts = line.substring(1).split(" ");
                var hash = parts[0];
                var path = parts[1];
                hashes.put(path, hash);
            }
        }

        var modules = new ArrayList<Submodule>();
        for (var name : paths.keySet()) {
            var url = urls.get(name);
            var path = paths.get(name);
            var hash = hashes.get(path);

            modules.add(new Submodule(new Hash(hash), Path.of(path), url));
        }

        return modules;
    }

    @Override
    public Tree tree(Hash h) throws IOException {
        String treeHash;
        try (var p = capture("git", "cat-file", "-p", h.hex())) {
            var res = p.await();
            if (res.stdout().size() > 0) {
                var line = res.stdout().get(0);
                if (line.startsWith("tree ")) {
                    treeHash = line.substring(5).trim();
                    if (treeHash.length() != 40) {
                        throw new IOException("Unexpected output: " + treeHash);
                    }
                } else {
                    throw new IOException("Unexpected output: " + line);
                }
            } else {
                throw new IOException("Unexpected output: " + res.stderr());
            }
        }
        return new Tree(new Hash(treeHash));
    }

    @Override
    public Optional<Tag.Annotated> annotate(Tag tag) throws IOException {
        var ref = "refs/tags/" + tag.name();
        var format = "%(refname:short)%0a%(*objectname)%0a%(taggername) %(taggeremail)%0a%(taggerdate:iso-strict)%0a%(contents)";
        try (var p = capture("git", "for-each-ref", "--format", format, ref)) {
            var lines = await(p).stdout();
            if (lines.size() >= 4) {
                var name = lines.get(0);
                var targetLine = lines.get(1);
                var authorLine = lines.get(2);
                var dateLine = lines.get(3);

                if (targetLine.isEmpty() && authorLine.equals(" ") && dateLine.isEmpty()) {
                    // Must be a lightweight tag, no metadata present
                    return Optional.empty();
                }

                var target = new Hash(targetLine);
                var author = Author.fromString(authorLine);
                var formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
                var date = ZonedDateTime.parse(dateLine, formatter);
                var message = String.join("\n", lines.subList(4, lines.size() - 1)); // Git adds newline

                return Optional.of(new Tag.Annotated(name, target, author, date, message));
            }
            return Optional.empty();
        }
    }

    @Override
    public void config(String section, String key, String value, boolean global) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "config"));
        if (global) {
            cmd.add("--global");
        }
        cmd.add(section + "." + key);
        cmd.add(value);
        // We must explicitly do this *with* the user's .gitconfig, so override NO_CONFIG_ENV
        try (var p = capture(dir, Collections.emptyMap(), cmd)) {
            await(p);
        }
    }

    @Override
    public String range(Hash h) {
        return h.hex() + "^!";
    }

    @Override
    public String rangeInclusive(Hash from, Hash to) {
        return from.hex() + "^.." + to.hex();
    }

    @Override
    public String rangeExclusive(Hash from, Hash to) {
        return from.hex() + ".." + to.hex();
    }

    @Override
    public boolean cherryPick(Hash hash) throws IOException {
        try (var p = capture("git", "cherry-pick", "--no-commit",
                                                   "--keep-redundant-commits",
                                                   "--strategy=recursive",
                                                   "--strategy-option=patience",
                                                   hash.hex())) {
            return p.await().status() == 0;
        }
    }

    @Override
    public int commitCount() throws IOException {
        try (var p = capture("git", "rev-list", "--all", "--count")) {
            return Integer.parseInt(await(p).stdout().get(0));
        }
    }

    @Override
    public int commitCount(List<Branch> branches) throws IOException {
        var args = new ArrayList<String>();
        args.addAll(List.of("git", "rev-list", "--count"));
        args.addAll(branches.stream().map(Branch::name).toList());
        try (var p = capture(args)) {
            return Integer.parseInt(await(p).stdout().getFirst());
        }
    }

    @Override
    public Hash initialHash() {
        return EMPTY_TREE;
    }

    @Override
    public Optional<List<String>> stagedFileContents(Path path) {
        try (var p = capture("git", "cat-file", "-p", ":" + path.toString())) {
            var res = p.await();
            if (res.status() == 0) {
                return Optional.of(res.stdout());
            }
        }
        return Optional.empty();
    }

    /**
     * Creates a fake Commit instance representing the currently staged diff.
     */
    @Override
    public Commit staged() throws IOException {
        var author = new Author(username().orElse("jcheck"), email().orElse("jcheck@none.none"));
        var commitMetaData = new CommitMetadata(new Hash("staged"), List.of(head()), author, ZonedDateTime.now(),
                author, ZonedDateTime.now(), List.of("Fake commit message for staged"));
        return new Commit(commitMetaData, List.of(diffStaged()));
    }

    /**
     * Creates a fake Commit instance representing the current working tree.
     */
    @Override
    public Commit workingTree() throws IOException {
        var author = new Author(username().orElse("jcheck"), email().orElse("jcheck@none.none"));
        var commitMetaData = new CommitMetadata(new Hash("working-tree"), List.of(head()), author, ZonedDateTime.now(),
                author, ZonedDateTime.now(), List.of("Fake commit message for working-tree"));
        return new Commit(commitMetaData, List.of(diff(head())));
    }

    private Diff diffStaged() throws IOException {
        var cmd = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "diff", "--patch", "--cached",
                "--find-renames=" + "90" + "%",
                "--find-copies=" + "90" + "%",
                "--find-copies-harder",
                "--binary",
                "--raw",
                "--no-abbrev",
                "--unified=0",
                "--no-color"));
        cmd.add(head().hex());

        var p = start(cmd);
        try {
            var patches = GitRawDiffParser.parse(p.getInputStream());
            await(p);
            return new Diff(head(), null, patches);
        } catch (Throwable t) {
            stop(p);
            throw t;
        }
    }

    @Override
    public boolean isEmptyCommit(Hash hash) {
        try (var p = capture("git", "show", "--cc", "--pretty=format:%b", hash.hex())) {
            var res = p.await();
            if (res.status() != 0) {
                return false;
            }
            var lines = res.stdout();
            for (int i = 0; i < lines.size() - 1; i++) {
                if (lines.get(i).startsWith("diff") && lines.get(i + 1).startsWith("index")) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void addNote(Hash hash,
                        List<String> lines,
                        String authorName,
                        String authorEmail,
                        String committerName,
                        String committerEmail) throws IOException {
        var existing = notes(hash);
        if (!existing.isEmpty()) {
            throw new IllegalStateException("A note already exists for " + hash.hex());
        }

        var cmd = Process.capture("git", "notes", "add", "-m", String.join("\n", lines), hash.hex())
                         .workdir(dir)
                         .environ(currentEnv)
                         .environ("GIT_AUTHOR_NAME", authorName)
                         .environ("GIT_AUTHOR_EMAIL", authorEmail)
                         .environ("GIT_COMMITTER_NAME", committerName)
                         .environ("GIT_COMMITTER_EMAIL", committerEmail);
        try (var p = cmd.execute()) {
            await(p);
        }
    }

    @Override
    public List<String> notes(Hash hash) throws IOException {
        try (var p = capture("git", "notes", "show", hash.hex())) {
            var res = p.await();
            if (res.status() != 0) {
                return List.of();
            }
            return res.stdout();
        }
    }

    @Override
    public void pushNotes(URI uri) throws IOException {
        push("refs/notes/*:refs/notes/*", uri);
    }
}
