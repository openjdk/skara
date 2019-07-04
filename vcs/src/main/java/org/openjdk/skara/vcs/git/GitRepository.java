/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GitRepository implements Repository {
    private final Path dir;
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.git");

    private java.lang.Process start(String... cmd) throws IOException {
        return start(Arrays.asList(cmd));
    }

    private java.lang.Process start(List<String> cmd) throws IOException {
        log.fine("Executing " + String.join(" ", cmd));
        var pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
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

    private Execution capture(String... cmd) {
        return capture(dir, cmd);
    }

    private static Execution capture(Path cwd, String... cmd) {
        return Process.capture(cmd)
                      .workdir(cwd)
                      .execute();
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
    public Optional<Commit> lookup(Hash h) throws IOException {
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

    public List<CommitMetadata> commitMetadata() throws IOException {
        var revisions = "--all";
        var p = start("git", "rev-list", "--format=" + GitCommitMetadata.FORMAT, "--no-abbrev", "--reverse", "--no-color", revisions);
        var reader = new UnixStreamReader(p.getInputStream());
        var result = new ArrayList<CommitMetadata>();

        var line = reader.readLine();
        while (line != null) {
            if (!line.startsWith("commit")) {
                throw new IOException("Unexpected line: " + line);
            }

            result.add(GitCommitMetadata.read(reader));
            line = reader.readLine();
        }

        await(p);
        return result;
    }

    @Override
    public boolean isEmpty() throws IOException {
        int numLooseObjects = -1;
        int numPackedObjects = -1;
        int numRefs = -1;

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

        try (var p = capture("git", "show-ref", "--hash", "--abbrev")) {
            var res = p.await();
            if (res.status() == -1) {
                if (res.stdout().size() != 0) {
                    throw new IOException("Unexpected output\n" + res);
                }
                numRefs = 0;
            } else {
                numRefs = res.stdout().size();
            }
        }

        return numLooseObjects == 0 && numPackedObjects == 0 && numRefs == 0;
    }

    @Override
    public boolean isHealthy() throws IOException {
        if (isEmpty()) {
            return true;
        }

        var name = "health-check";
        try (var p = capture("git", "branch", name, "HEAD")) {
            if (p.await().status() != 0) {
                return false;
            }
        }
        try (var p = capture("git", "branch", "-D", name)) {
            if (p.await().status() != 0) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void clean() throws IOException {
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
    public Repository reinitialize() throws IOException {
        Files.walk(dir)
             .map(Path::toFile)
             .sorted(Comparator.reverseOrder())
             .forEach(File::delete);

        return init();
    }

    @Override
    public Hash fetch(URI uri, String refspec) throws IOException {
        try (var p = capture("git", "fetch", "--tags", uri.toString(), refspec)) {
            await(p);
            return resolve("FETCH_HEAD").get();
        }
    }

    private void checkout(String ref, boolean force) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "advice.detachedHead=false", "checkout"));
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
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        try (var p = capture("git", "init")) {
            await(p);
            return this;
        }
    }

    @Override
    public void pushAll(URI uri) throws IOException {
        try (var p = capture("git", "push", "--mirror", uri.toString())) {
            await(p);
        }
    }

    @Override
    public void push(Hash hash, URI uri, String ref, boolean force) throws IOException {
        String refspec = force ? "+" : "";
        if (!ref.startsWith("refs/")) {
            ref = "refs/heads/" + ref;
        }
        refspec += hash.hex() + ":" + ref;

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
        try (var p = capture("git", "rev-parse", "--show-toplevel")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                // Perhaps this is a bare repository
                try (var p2 = capture("git", "rev-parse", "--git-dir")) {
                    var res2 = await(p2);
                    if (res2.stdout().size() != 1) {
                        throw new IOException("Unexpected output\n" + res2);
                    }
                    return dir.resolve(Path.of(res2.stdout().get(0)));
                }
            }
            return Path.of(res.stdout().get(0));
        }
    }

    @Override
    public void squash(Hash h) throws IOException {
        try (var p = capture("git", "merge", "--squash", h.hex())) {
            await(p);
        }
    }

    @Override
    public void add(Path... paths) throws IOException {
        var cmd = new ArrayList<>(List.of("git", "add"));
        for (var path : paths) {
            cmd.add(path.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public void remove(Path... paths) throws IOException {
        var cmd = new ArrayList<>(List.of("git", "rm"));
        for (var path : paths) {
            cmd.add(path.toString());
        }
        try (var p = capture(cmd)) {
            await(p);
        }
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail)  throws IOException {
        return commit(message, authorName, authorEmail, null);
    }

    @Override
    public Hash commit(String message, String authorName, String authorEmail, Instant authorDate)  throws IOException {
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
                       Instant authorDate,
                       String committerName,
                       String committerEmail,
                       Instant committerDate) throws IOException {
        var cmd = Process.capture("git", "commit", "--message=" + message)
                         .workdir(dir)
                         .environ("GIT_AUTHOR_NAME", authorName)
                         .environ("GIT_AUTHOR_EMAIL", authorEmail)
                         .environ("GIT_COMMITTER_NAME", committerName)
                         .environ("GIT_COMMITTER_EMAIL", committerEmail);
        if (authorDate != null) {
            var epochSecond = ZonedDateTime.ofInstant(authorDate, ZoneOffset.UTC);
            cmd = cmd.environ("GIT_AUTHOR_DATE", epochSecond + " +0000");
        }
        if (committerDate != null) {
            var epochSecond = ZonedDateTime.ofInstant(committerDate, ZoneOffset.UTC);
            cmd = cmd.environ("GIT_COMMITTER_DATE", epochSecond + " +0000");
        }
        try (var p = cmd.execute()) {
            await(p);
            return head();
        }
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail) throws IOException {
        return amend(message, authorName, authorEmail, null, null);
    }

    @Override
    public Hash amend(String message, String authorName, String authorEmail, String committerName, String committerEmail) throws IOException {
        if (committerName == null) {
            committerName = authorName;
            committerEmail = authorEmail;
        }
        var cmd = Process.capture("git", "commit", "--amend", "--reset-author", "--message=" + message)
                         .workdir(dir)
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
    public Tag tag(Hash hash, String name, String message, String authorName, String authorEmail) throws IOException {
        var cmd = Process.capture("git", "tag", "--annotate", "--message=" + message, name, hash.hex())
                         .workdir(dir)
                         .environ("GIT_AUTHOR_NAME", authorName)
                         .environ("GIT_AUTHOR_EMAIL", authorEmail)
                         .environ("GIT_COMMITTER_NAME", authorName)
                         .environ("GIT_COMMITTER_EMAIL", authorEmail);
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
    public Hash mergeBase(Hash first, Hash second) throws IOException {
        try (var p = capture("git", "merge-base", first.hex(), second.hex())) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                 throw new IOException("Unexpected output\n" + res);
            }
            return new Hash(res.stdout().get(0));
        }
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
        try (var p = Process.capture("git", "rebase", "--onto", hash.hex(), "--root", "--rebase-merges")
                            .environ("GIT_COMMITTER_NAME", committerName)
                            .environ("GIT_COMMITTER_EMAIL", committerEmail)
                            .workdir(dir)
                            .execute()) {
            await(p);
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
    public Branch currentBranch() throws IOException {
        try (var p = capture("git", "symbolic-ref", "--short", "HEAD")) {
            var res = await(p);
            if (res.stdout().size() != 1) {
                throw new IOException("Unexpected output\n" + res);
            }
            return new Branch(res.stdout().get(0));
        }
    }

    @Override
    public Branch defaultBranch() throws IOException {
        try (var p = capture("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")) {
            var res = p.await();
            if (res.status() == 0 && res.stdout().size() == 1) {
                var ref = res.stdout().get(0).substring("origin/".length());
                return new Branch(ref);
            } else {
                return new Branch("master");
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

    private String treeEntry(Path path, Hash hash) throws IOException {
        try (var p = Process.capture("git", "ls-tree", hash.hex(), path.toString())
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

    @Override
    public Optional<byte[]> show(Path path, Hash hash) throws IOException {
        var entry = treeEntry(path, hash);
        if (entry == null) {
            return Optional.empty();
        }

        var parts = entry.split(" ");
        var mode = parts[0];
        if (mode.equals("160000")) {
            // submodule
            var hashAndName = parts[2].split("\t");
            return Optional.of(("Subproject commit " + hashAndName[0]).getBytes(StandardCharsets.UTF_8));
        } else if (mode.equals("100644") || mode.equals("100755")) {
            // blob
            var blobAndName = parts[2].split("\t");
            var blob = blobAndName[0];
            try (var p = capture("git", "unpack-file", blob)) {
                var res = await(p);
                if (res.stdout().size() != 1) {
                    throw new IOException("Unexpected output\n" + res);
                }

                var file = Path.of(root().toString(), res.stdout().get(0));
                if (Files.exists(file)) {
                    var bytes = Files.readAllBytes(file);
                    Files.delete(file);
                    return Optional.of(bytes);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Diff diff(Hash from) throws IOException {
        return diff(from, null);
    }

    @Override
    public Diff diff(Hash from, Hash to) throws IOException {
        var cmd = new ArrayList<>(List.of("git", "diff", "--patch",
                                                         "--find-renames=99%",
                                                         "--find-copies=99%",
                                                         "--find-copies-harder",
                                                         "--binary",
                                                         "--raw",
                                                         "--no-abbrev",
                                                         "--unified=0",
                                                         "--no-color",
                                                         from.hex()));
        if (to != null) {
            cmd.add(to.hex());
        }

        var p = start(cmd);
        try {
            var patches = UnifiedDiffParser.parseGitRaw(p.getInputStream());
            await(p);
            return new Diff(from, to, patches);
        } catch (Throwable t) {
            stop(p);
            throw t;
        }
    }

    @Override
    public List<String> config(String key) throws IOException {
        try (var p = capture("git", "config", key)) {
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
        try (var p = capture("git", "clone", root().toString(), destination.toString())) {
            await(p);
        }

        return new GitRepository(destination);
    }

    @Override
    public void merge(Hash h) throws IOException {
        merge(h, null);
    }

    @Override
    public void merge(Hash h, String strategy) throws IOException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "-c", "user.name=unused", "-c", "user.email=unused",
                           "merge", "--no-commit"));
        if (strategy != null) {
            cmd.add("-s");
            cmd.add(strategy);
        }
        cmd.add(h.hex());
        try (var p = capture(cmd)) {
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

    public static Repository clone(URI from, Path to) throws IOException {
        try (var p = capture(Path.of("").toAbsolutePath(), "git", "clone", from.toString(), to.toString())) {
            await(p);
        }
        return new GitRepository(to);
    }

    @Override
    public void pull() throws IOException {
        pull("origin", "master");
    }

    @Override
    public void pull(String remote) throws IOException {
        pull(remote, "master");
    }


    @Override
    public void pull(String remote, String refspec) throws IOException {
        try (var p = capture("git", "pull", remote, refspec)) {
            await(p);
        }
    }
}
