/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.*;

public class GitSync {
    private static IOException die(String message) {
        System.err.println("error: " + message);
        System.exit(1);
        return new IOException("will never reach here");
    }

    private static boolean equalsCanonicalized(URI a, URI b) throws IOException {
        if (a == null || b == null) {
            if (a == null && b == null) {
                return true;
            }
            return false;
        }

        var canonicalA = Remote.toWebURI(Remote.canonicalize(a).toString());
        var canonicalB = Remote.toWebURI(Remote.canonicalize(b).toString());
        return canonicalA.equals(canonicalB);
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

    private static String getOption(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        var lines = repo.config("sync." + name);
        return lines.size() == 1 ? lines.get(0) : null;
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
            Option.shortcut("")
                  .fullname("ignore")
                  .describe("PATTERN")
                  .helptext("Regular expression of branches to ignore")
                  .optional(),
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Username on forge")
                  .optional(),
            Switch.shortcut("")
                  .fullname("pull")
                  .helptext("Pull current branch from origin after successful sync")
                  .optional(),
            Switch.shortcut("ff")
                  .fullname("fast-forward")
                  .helptext("Fast forward all local branches where possible")
                  .optional(),
            Switch.shortcut("n")
                   .fullname("dry-run")
                   .helptext("Only simulate behavior, do no actual changes")
                   .optional(),
            Switch.shortcut("")
                   .fullname("force")
                   .helptext("Force syncing even between unrelated repos (beware!)")
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

        String targetFromOptions = null;
        if (arguments.contains("to")) {
            targetFromOptions = arguments.get("to").asString();
        } else {
            var lines = repo.config("sync.to");
            if (lines.size() == 1) {
                if (!remotes.contains(lines.get(0))) {
                    die("the given remote to push to, " + lines.get(0) + ", does not exist");
                } else {
                    targetFromOptions = lines.get(0);
                }
            }
        }

        String sourceFromOptions = null;
        if (arguments.contains("from")) {
            sourceFromOptions = arguments.get("from").asString();
        } else {
            var lines = repo.config("sync.from");
            if (lines.size() == 1 && remotes.contains(lines.get(0))) {
                sourceFromOptions = lines.get(0);
            }
        }

        // Find push target repo
        String targetName;
        URI targetURI;
        URI targetFromOptionsURI = null;
        if (targetFromOptions != null) {
            if (remotes.contains(targetFromOptions)) {
                targetFromOptionsURI = Remote.toURI(repo.pullPath(targetFromOptions));

            } else {
                targetFromOptionsURI = Remote.toURI(targetFromOptions);
            }
        }

        if (!remotes.contains("origin")) {
            if (targetFromOptions != null) {
                // If 'origin' is missing but we have command line arguments, use these instead
                targetName = targetFromOptions;
                targetURI = targetFromOptionsURI;
            } else {
                die("repo does not have an 'origin' remote defined");
                targetName = null; // Make compiler quiet
                targetURI = null;
            }
        } else {
            targetName = "origin";
            targetURI = Remote.toURI(repo.pullPath(targetName));
            if (targetFromOptions != null) {
                if (!equalsCanonicalized(targetFromOptionsURI, targetURI)) {
                    if (arguments.contains("force")) {
                        if (arguments.contains("verbose") || arguments.contains("debug")) {
                            System.out.println("Overriding target 'origin' with " + targetFromOptions + " due to --force");
                        }
                        targetName = targetFromOptions;
                        targetURI = targetFromOptionsURI;
                    } else {
                        die("git 'origin' remote and '--to' argument differ. Consider using --force.");
                    }
                }
            }
        }

        // Find pull source as given by the Git Forge as the repository's parent
        var forgeWebURI = Remote.toWebURI(targetURI.toString());
        URI sourceParentURI;
        String sourceParentName;
        try {
            sourceParentURI = ForgeUtils.from(forgeWebURI)
                    .flatMap(f -> f.repository(forgeWebURI.getPath().substring(1)))
                    .flatMap(r -> r.parent())
                    .map(p -> p.webUrl())
                    .orElse(null);
            sourceParentName = sourceParentURI.toString();
            if (arguments.contains("verbose") || arguments.contains("debug")) {
                System.out.println("Git Forge reports upstream parent is " + sourceParentURI);
            }
        } catch (Throwable e) {
            if (arguments.contains("debug")) {
                e.printStackTrace();
            }
            if (!arguments.contains("force")) {
                // Unless we force a different recipient repo, we are not allowed to have an error here
                die("cannot get parent repo from Git Forge provider for " + forgeWebURI);
            }
            sourceParentURI = null; // Make compiler quiet
            sourceParentName = null;
        }

        var sourceURI = sourceParentURI;
        var sourceName = sourceParentName;

        // Find pull source as given by Git's 'upstream' remote
        if (remotes.contains("upstream")) {
            sourceName = "upstream";
            var sourceUpstreamURI = Remote.toURI(repo.pullPath("upstream"));
            if (!equalsCanonicalized(sourceUpstreamURI, sourceParentURI)) {
                if (arguments.contains("force")) {
                    sourceURI = sourceUpstreamURI;
                    if (arguments.contains("verbose") || arguments.contains("debug")) {
                        System.out.println("Replacing Git Forge parent with " + sourceUpstreamURI + " from 'upstream' remote");
                    }
                } else {
                    System.err.println("error: git 'upstream' remote and the parent fork given by the Git Forge differ");
                    System.err.println("       Git 'upstream' remote is " + sourceUpstreamURI);
                    System.err.println("       Git Forge parent is " + sourceParentURI);
                    System.err.println("       Remove incorrect 'upstream' remote with 'git remote remove upstream'");
                    System.err.println("       or run with --force to use 'upstream' remote anyway");
                    System.exit(1);
                }
            }
        }

        // Find pull source as given by command line options
        if (sourceFromOptions != null) {
            var sourceFromOptionsURI = repo.remotes().contains(sourceFromOptions) ?
                    Remote.toURI(repo.pullPath(sourceFromOptions)) : Remote.toURI(sourceFromOptions);

            if (!equalsCanonicalized(sourceFromOptionsURI, sourceURI)) {
                if (arguments.contains("force")) {
                    // Use the value from the option instead
                    sourceName = sourceFromOptions;
                    sourceURI = sourceFromOptionsURI;
                    if (arguments.contains("verbose") || arguments.contains("debug")) {
                        System.out.println("Replacing source repo with " + sourceFromOptionsURI + " from command line options");
                    }
                } else {
                    die("Git Forge parent and git sync '--from' option do not match");
                }
            }
        }

        if (sourceURI == null) {
            System.err.println("error: could not find repository to sync from, please specify one with --from");
            System.err.println("       or add a remote named 'upstream'");
            System.exit(1);
        }

        if (equalsCanonicalized(targetURI, sourceURI)) {
            System.err.println("error: --from and --to refer to the same repository: " + targetURI);
            System.exit(1);
        }

        System.out.println("Will sync changes from " + sourceURI + " to " + targetURI);

        // Assure we have proper credentials for pull and push operations
        var sourceScheme = sourceURI.getScheme();
        if (sourceScheme.equals("https") || sourceScheme.equals("http")) {
            var token = System.getenv("GIT_TOKEN");
            var username = getOption("username", arguments, repo);
            var credentials = GitCredentials.fill(sourceURI.getHost(),
                                                  sourceURI.getPath(),
                                                  username,
                                                  token,
                                                  sourceScheme);
            if (credentials.password() != null && credentials.username() != null && token != null) {
                sourceURI = URI.create(sourceScheme + "://" + credentials.username() + ":" + credentials.password() + "@" + sourceURI.getHost() + sourceURI.getPath());
            }
        }

        var targetScheme = targetURI.getScheme();
        if (targetScheme.equals("https") || targetScheme.equals("http")) {
            var token = System.getenv("GIT_TOKEN");
            var username = getOption("username", arguments, repo);
            var credentials = GitCredentials.fill(targetURI.getHost(),
                                                  targetURI.getPath(),
                                                  username,
                                                  token,
                                                  targetScheme);
            if (credentials.password() == null) {
                die("no personal access token found, use git-credentials or the environment variable GIT_TOKEN");
            }
            if (credentials.username() == null) {
                die("no username for " + targetURI.getHost() + " found, use git-credentials or the flag --username");
            }
            if (token != null) {
                targetURI = URI.create(targetScheme + "://" + credentials.username() + ":" + credentials.password() + "@" +
                                        targetURI.getHost() + targetURI.getPath());
            } else {
                GitCredentials.approve(credentials);
            }
        }

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

        var ignore = Pattern.compile("pr/.*");
        if (arguments.contains("ignore")) {
            ignore = Pattern.compile(arguments.get("ignore").asString());
        } else {
            var lines = repo.config("sync.ignore");
            if (lines.size() == 1) {
                ignore = Pattern.compile(lines.get(0));
            }
        }

        System.out.println("source name is " + sourceName);
        var remoteBranches = repo.remoteBranches(sourceName);
        for (var branch : remoteBranches) {
            var name = branch.name();
            if (!branches.isEmpty() && !branches.contains(name)) {
                if (arguments.contains("verbose") || arguments.contains("debug")) {
                    System.out.println("Skipping branch " + name);
                }
                continue;
            }
            if (ignore.matcher(name).matches()) {
                if (arguments.contains("verbose") || arguments.contains("debug")) {
                    System.out.println("Skipping branch " + name);
                }
                continue;
            }

            System.out.println("Syncing " + sourceName + "/" + name + " to " + targetName + "/" + name + "... ");
            System.out.flush();

            Hash fetchHead = null;
            if (arguments.contains("verbose")) {
                System.out.println("Fetching branch " + branch.name() + " from  " + sourceURI);
            }
            if (!arguments.contains("dry-run")) {
                fetchHead = repo.fetch(sourceURI, branch.name());
            }
            if (arguments.contains("verbose")) {
                System.out.println("Pushing to " + targetURI);
            }
            if (!arguments.contains("dry-run")) {
                repo.push(fetchHead, targetURI, name);
            }
            System.out.println("Done syncing");
        }

        var shouldPull = arguments.contains("pull");
        if (!shouldPull) {
            var lines = repo.config("sync.pull");
            shouldPull = lines.size() == 1 && lines.get(0).equalsIgnoreCase("true");
        }
        if (shouldPull) {
            var currentBranch = repo.currentBranch();
            if (currentBranch.isPresent()) {
                var upstreamBranch = repo.upstreamFor(currentBranch.get());
                if (upstreamBranch.isPresent()) {
                    if (arguments.contains("verbose")) {
                        System.out.println("Pulling from " + repo);
                    }
                    if (!arguments.contains("dry-run")) {
                        int err = pull(repo);
                        if (err != 0) {
                            System.exit(err);
                        }
                    }
                }
            }
        }

        var shouldFastForward = arguments.contains("fast-forward");
        if (!shouldFastForward) {
            var lines = repo.config("sync.fast-forward");
            shouldFastForward = lines.size() == 1 && lines.get(0).equalsIgnoreCase("true");
        }
        if (shouldFastForward) {
            if (!remotes.contains(targetName)) {
                die("--fast-forward can only be used when --to is the name of a remote");
            }
            if (arguments.contains("verbose")) {
                System.out.println("Fetching from remote " + targetName);
            }
            if (!arguments.contains("dry-run")) {
                repo.fetchRemote(targetName);
            }

            var remoteBranchNames = new HashSet<String>();
            for (var branch : remoteBranches) {
                remoteBranchNames.add(targetName + "/" + branch.name());
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
                        if (currentBranch.isPresent() && branch.equals(currentBranch.get())) {
                            if (!arguments.contains("dry-run")) {
                                if (arguments.contains("verbose")) {
                                    System.out.println("Fast-forwarding current branch");
                                }
                                var err = mergeFastForward(repo, upstreamBranch.get());
                                if (err != 0) {
                                    System.exit(1);
                                }
                            }
                        } else {
                            if (!arguments.contains("dry-run")) {
                                if (arguments.contains("verbose")) {
                                    System.out.println("Fast-forwarding branch " + upstreamBranch.get());
                                }
                                var err = moveBranch(repo, branch, upstreamHash.get());
                                if (err != 0) {
                                    System.exit(1);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var cwd = Paths.get("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() ->
                die("no repository found at " + cwd)
        );

        sync(repo, args);
    }
}
