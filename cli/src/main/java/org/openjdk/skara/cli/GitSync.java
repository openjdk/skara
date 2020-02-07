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
package org.openjdk.skara.cli;

import org.openjdk.skara.args.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

public class GitSync {
    private static IOException die(String message) {
        System.err.println(message);
        System.exit(1);
        return new IOException("will never reach here");
    }

    private static int pull(Repository repo) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "pull");
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    private static int mergeFastForward(Repository repo, String ref) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "merge", "--ff-only", "--quiet", ref);
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    private static int moveBranch(Repository repo, Branch branch, Hash to) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "branch", "--force", branch.name(), to.hex());
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    static void sync(Repository repo, String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Option.shortcut("")
                  .fullname("from")
                  .describe("REMOTE")
                  .helptext("Fetch changes from this remote")
                  .optional(),
            Option.shortcut("")
                  .fullname("to")
                  .describe("REMOTE")
                  .helptext("Push changes to this remote")
                  .optional(),
            Option.shortcut("")
                  .fullname("branches")
                  .describe("BRANCHES")
                  .helptext("Comma separated list of branches to sync")
                  .optional(),
            Switch.shortcut("")
                  .fullname("pull")
                  .helptext("Pull current branch from origin after successful sync")
                  .optional(),
            Switch.shortcut("ff")
                  .fullname("fast-forward")
                  .helptext("Fast forward all local branches where possible")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Force use of mercurial")
                  .optional(),
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional()
        );

        var parser = new ArgumentParser("git sync", flags);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-sync version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }


        HttpProxy.setup();

        var remotes = repo.remotes();

        String from = null;
        if (arguments.contains("from")) {
            from = arguments.get("from").asString();
        } else {
            var lines = repo.config("sync.from");
            if (lines.size() == 1 && remotes.contains(lines.get(0))) {
                from = lines.get(0);
            } else {
                if (remotes.contains("upstream")) {
                    from = "upstream";
                } else if (remotes.contains("origin")) {
                    if (remotes.contains("fork")) {
                        from = "origin";
                    } else {
                        var originPullPath = repo.pullPath("origin");
                        try {
                            var uri = Remote.toWebURI(originPullPath);
                            from = Forge.from(uri)
                                        .flatMap(f -> f.repository(uri.getPath().substring(1)))
                                        .flatMap(r -> r.parent())
                                        .map(p -> p.webUrl().toString())
                                        .orElse(null);
                        } catch (IllegalArgumentException e) {
                            from = null;
                        }
                    }
                }
            }
        }

        if (from == null) {
            die("Could not find repository to sync from, please specify one with --from");
        }

        var fromPullPath = remotes.contains(from) ?
            Remote.toURI(repo.pullPath(from)) : URI.create(from);

        String to = null;
        if (arguments.contains("to")) {
            to = arguments.get("to").asString();
        } else {
            var lines = repo.config("sync.to");
            if (lines.size() == 1) {
                if (!remotes.contains(lines.get(0))) {
                    die("The given remote to push to, " + lines.get(0) + ", does not exist");
                } else {
                    to = lines.get(0);
                }
            } else {
                if (remotes.contains("fork")) {
                    to = "fork";
                } else {
                    to = "origin";
                }
            }
        }

        var toPushPath = remotes.contains(to) ?
            Remote.toURI(repo.pullPath(to)) : URI.create(to);

        var branches = new HashSet<String>();
        if (arguments.contains("branches")) {
            var requested = arguments.get("branches").asString().split(",");
            for (var branch : requested) {
                branches.add(branch.trim());
            }
        } else {
            var lines = repo.config("sync.branches");
            if (lines.size() == 1) {
                var requested = lines.get(0).split(",");
                for (var branch : requested) {
                    branches.add(branch.trim());
                }
            }
        }

        var remoteBranches = repo.remoteBranches(from);
        for (var branch : remoteBranches) {
            var name = branch.name();
            if (!branches.isEmpty() && !branches.contains(name)) {
                if (arguments.contains("verbose") || arguments.contains("debug")) {
                    System.out.println("Skipping branch " + name);
                }
                continue;
            }
            System.out.print("Syncing " + from + "/" + name + " to " + to + "/" + name + "... ");
            System.out.flush();
            var fetchHead = repo.fetch(fromPullPath, branch.name());
            repo.push(fetchHead, toPushPath, name);
            System.out.println("done");
        }

        var shouldPull = arguments.contains("pull");
        if (!shouldPull) {
            var lines = repo.config("sync.pull");
            shouldPull = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
        }
        if (shouldPull) {
            var currentBranch = repo.currentBranch();
            if (currentBranch.isPresent()) {
                var upstreamBranch = repo.upstreamFor(currentBranch.get());
                if (upstreamBranch.isPresent()) {
                    int err = pull(repo);
                    if (err != 0) {
                        System.exit(err);
                    }
                }
            }
        }

        var shouldFastForward = arguments.contains("fast-forward");
        if (!shouldFastForward) {
            var lines = repo.config("sync.fast-forward");
            shouldFastForward = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
        }
        if (shouldFastForward) {
            if (!remotes.contains(to)) {
                die("error: --fast-forward can only be used when --to is the name of a remote");
            }
            repo.fetchRemote(to);

            var remoteBranchNames = new HashSet<String>();
            for (var branch : remoteBranches) {
                remoteBranchNames.add(to + "/" + branch.name());
            }

            var currentBranch = repo.currentBranch();
            var localBranches = repo.branches();
            for (var branch : localBranches) {
                var upstreamBranch = repo.upstreamFor(branch);
                if (upstreamBranch.isPresent() && remoteBranchNames.contains(upstreamBranch.get())) {
                    var localHash = repo.resolve(branch);
                    var upstreamHash = repo.resolve(upstreamBranch.get());
                    if (localHash.isPresent() && upstreamHash.isPresent() &&
                        !upstreamHash.equals(localHash) &&
                        repo.isAncestor(localHash.get(), upstreamHash.get())) {
                        var err = currentBranch.isPresent() && branch.equals(currentBranch.get()) ?
                            mergeFastForward(repo, upstreamBranch.get()) :
                            moveBranch(repo, branch, upstreamHash.get());
                        if (err != 0) {
                            System.exit(1);
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var cwd = Paths.get("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() ->
                die("error: no repository found at " + cwd.toString())
        );

        sync(repo, args);
    }
}
