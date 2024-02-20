/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.host.Credential;
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
    private final Repository repo;
    private final Arguments arguments;
    private final List<String> remotes;
    private final boolean isDryRun;
    private String targetName;
    private URI targetURI;
    private String sourceName;
    private URI sourceURI;

    private GitSync(Repository repo, Arguments arguments) throws IOException {
        this.repo = repo;
        this.arguments = arguments;
        this.remotes = repo.remotes();
        this.isDryRun = arguments.contains("dry-run");
    }

    private void logVerbose(String message) {
        if (arguments.contains("verbose") || arguments.contains("debug")) {
            System.out.println(message);
        }
    }

    private URI getRemoteURI(String name) throws IOException {
        if (name != null) {
            if (remotes.contains(name)) {
                return Remote.toURI(repo.pullPath(name));
            } else {
                try {
                    return Remote.toURI(name);
                } catch (IOException e) {
                    die(name + " is not a known git remote, nor a proper git URI");
                }
            }
        }
        return null;
    }

    private String getOption(String name) throws IOException {
        var arg = ForgeUtils.getOption(name, arguments);
        if (arg != null) {
            return arg;
        }
        var lines = repo.config("sync." + name);
        return lines.size() == 1 ? lines.get(0) : null;
    }

    private void syncBranch(String name) throws IOException {
        Hash fetchHead = null;
        logVerbose("Fetching branch " + name + " from  " + sourceURI);
        if (!isDryRun) {
            fetchHead = repo.fetch(sourceURI, name).orElseThrow();
        }
        logVerbose("Pushing to " + targetURI);
        if (!isDryRun) {
            repo.push(fetchHead, targetURI, name);
        }
    }

    private void fetchTarget() throws IOException {
        if (isDryRun) return;

        repo.fetchRemote(targetName);
    }

    private void pull() throws IOException, InterruptedException {
        if (isDryRun) return;

        var pb = new ProcessBuilder("git", "pull");
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        var result = pb.start().waitFor();
        if (result != 0) {
            die("Failure running git pull, exit code " + result);
        }
    }

    private void mergeFastForward(String ref) throws IOException, InterruptedException {
        if (isDryRun) return;

        var pb = new ProcessBuilder("git", "merge", "--ff-only", "--quiet", ref);
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        var result = pb.start().waitFor();

        if (result != 0) {
            die("Failure running git merge, exit code " + result);
        }
    }

    private void moveBranch(Branch branch, Hash to) throws IOException, InterruptedException {
        if (isDryRun) return;

        var pb = new ProcessBuilder("git", "branch", "--force", branch.name(), to.hex());
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        var result = pb.start().waitFor();

        if (result != 0) {
            die("Failure running git branch, exit code " + result);
        }
    }

    private void setupTargetAndSource() throws IOException {
        String targetFromOptions = getOption("to");
        URI targetFromOptionsURI = getRemoteURI(targetFromOptions);

        String sourceFromOptions = getOption("from");
        URI sourceFromOptionsURI = getRemoteURI(sourceFromOptions);

        // Find push target repo
        if (!remotes.contains("origin")) {
            if (targetFromOptions != null) {
                // If 'origin' is missing but we have command line arguments, use these instead
                targetName = targetFromOptions;
                targetURI = targetFromOptionsURI;
            } else {
                die("repo does not have an 'origin' remote defined");
            }
        } else {
            targetName = "origin";
            targetURI = Remote.toURI(repo.pullPath(targetName));
            if (targetFromOptions != null) {
                if (!equalsCanonicalized(targetFromOptionsURI, targetURI)) {
                    logVerbose("Overriding target 'origin' with " + targetFromOptions);
                    targetName = targetFromOptions;
                    targetURI = targetFromOptionsURI;
                }
            }
        }

        // Find pull source as given by command line options
        if (sourceFromOptions != null) {
            if (!sameHost(sourceFromOptionsURI, targetURI)) {
                if (!arguments.contains("force")) {
                    System.err.println("error: The from and to remote repositories are hosted on different forges");
                    System.err.println("       The from remote is " + sourceFromOptionsURI);
                    System.err.println("       The to remote is " + targetURI);
                    System.err.println("       Rerun with --force if this was intended");
                    System.exit(1);
                }
            }
            logVerbose("Replacing source repo with " + sourceFromOptionsURI + " from command line options");
            sourceName = sourceFromOptions;
            sourceURI = sourceFromOptionsURI;
        } else {
            // This may return null, if so, we fall back on just comparing hostnames further down
            var remoteForkParentURI = findRemoteForkParent();

            if (remotes.contains("upstream")) {
                // Find pull source as given by Git's 'upstream' remote
                var sourceUpstreamURI = Remote.toURI(repo.pullPath("upstream"));
                if (remoteForkParentURI != null) {
                    if (!equalsCanonicalized(sourceUpstreamURI, remoteForkParentURI)) {
                        System.err.println("error: git 'upstream' remote and the parent fork given by the Git Forge differ");
                        System.err.println("       Git 'upstream' remote is " + sourceUpstreamURI);
                        System.err.println("       Git Forge parent is " + remoteForkParentURI);
                        System.err.println("       Remove incorrect 'upstream' remote with 'git remote remove upstream'");
                        System.err.println("       or run with --force to use 'upstream' remote anyway");
                        System.exit(1);
                    }
                } else {
                    if (!sameHost(sourceUpstreamURI, targetURI)) {
                        if (!arguments.contains("force")) {
                            System.err.println("error: The from and to remote repositories are hosted on different forges");
                            System.err.println("       The from remote is " + sourceUpstreamURI);
                            System.err.println("       The to remote is " + targetURI);
                            System.err.println("       Rerun with --force if this was intended");
                            System.exit(1);
                        }
                    }
                }
                sourceName = "upstream";
                sourceURI = sourceUpstreamURI;
            } else if (remoteForkParentURI != null) {
                // Repo is badly configured, fix it unless instructed not to
                if (!arguments.contains("no-remote")) {
                    System.out.println("Setting 'upstream' remote to " + remoteForkParentURI);
                    if (!isDryRun) {
                        repo.addRemote("upstream", remoteForkParentURI.toString());
                    }
                }
                sourceName = "upstream";
                sourceURI = remoteForkParentURI;
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
        setupSourceCredentials();
    }

    private URI findRemoteForkParent() throws IOException {
        var targetScheme = targetURI.getScheme();
        if (!arguments.contains("force") && targetScheme.equals("https") || targetScheme.equals("http")) {
            var credentials = setupTargetCredentials();

            // Find pull source as given by the Git Forge as the repository's parent
            var forgeWebURI = Remote.toWebURI(targetURI.toString());
            try {
                var sourceParentURI = ForgeUtils.from(forgeWebURI, credentials)
                        .flatMap(f -> f.repository(forgeWebURI.getPath().substring(1)))
                        .flatMap(HostedRepository::parent)
                        .map(HostedRepository::webUrl);

                if (sourceParentURI.isPresent()) {
                    logVerbose("Git Forge reports upstream parent is " + sourceParentURI.get());
                    return sourceParentURI.get();
                }
            } catch (UncheckedIOException e) {
                System.err.println("Failed to contact target forge: " + targetURI);
                var message = e.getCause().getMessage();
                if (message != null) {
                    System.err.println(message);
                }
                System.err.println("Skipping remote fork parent check");
            }
        }
        return null;
    }

    private void setupSourceCredentials() throws IOException {
        var sourceScheme = sourceURI.getScheme();
        if (sourceScheme.equals("https") || sourceScheme.equals("http")) {
            var token = System.getenv("GIT_TOKEN");
            var username = getOption("username");
            var credentials = GitCredentials.fill(sourceURI.getHost(),
                    sourceURI.getPath(),
                    username,
                    token,
                    sourceScheme);
            if (credentials.password() != null && credentials.username() != null && token != null) {
                sourceURI = URI.create(sourceScheme + "://" + credentials.username() + ":" + credentials.password() + "@" + sourceURI.getHost() + sourceURI.getPath());
            }
        }
    }

    private Credential setupTargetCredentials() throws IOException {
        var targetScheme = targetURI.getScheme();
        if (targetScheme.equals("https") || targetScheme.equals("http")) {
            var token = System.getenv("GIT_TOKEN");
            var username = getOption("username");
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
            return new Credential(credentials.username(), credentials.password());
        }
        return null;
    }

    public void sync() throws IOException, InterruptedException {
        if (arguments.contains("version")) {
            System.out.println("git-sync version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        if (isDryRun) {
            System.out.println("Running in dry-run mode. No actual changes will be performed");
        }

        HttpProxy.setup();

        // Setup source (from, upstream) and target (to, origin) repo names and URIs
        setupTargetAndSource();
        System.out.println("Will sync changes from " + sourceURI + " to " + targetURI);

        var branches = new HashSet<String>();
        var branchesArg = getOption("branches");
        if (branchesArg != null) {
            var requested = branchesArg.split(",");
            for (var branch : requested) {
                branches.add(branch.trim());
            }
        }

        var ignore = Pattern.compile("pr/.*");
        var ignoreArg = getOption("ignore");
        if (ignoreArg != null) {
            ignore = Pattern.compile(ignoreArg);
        }

        var remoteBranches = repo.remoteBranches(sourceName);
        for (var branch : remoteBranches) {
            var name = branch.name();
            if (!branches.isEmpty() && !branches.contains(name)) {
                logVerbose("Skipping branch " + name);
                continue;
            }
            if (!branches.contains(name) && ignore.matcher(name).matches()) {
                logVerbose("Skipping branch " + name);
                continue;
            }

            System.out.println("Syncing " + sourceName + "/" + name + " to " + targetName + "/" + name + "... ");
            syncBranch(name);
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
                    logVerbose("Pulling from " + repo);
                    pull();
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
            logVerbose("Fetching from remote " + targetName);
            fetchTarget();

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
                            logVerbose("Fast-forwarding current branch");
                            mergeFastForward(upstreamBranch.get());
                        } else {
                            logVerbose("Fast-forwarding branch " + upstreamBranch.get());
                            moveBranch(branch, upstreamHash.get());
                        }
                    }
                }
            }
        }
    }

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

    private static boolean sameHost(URI sourceUpstreamURI, URI targetURI) {
        return sourceUpstreamURI.getHost().equals(targetURI.getHost());
    }


    private static Arguments parseArguments(String[] args) {
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
            Switch.shortcut("")
                   .fullname("no-remote")
                   .helptext("Do not add an additional git remote")
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
        return parser.parse(args);
    }

    public static void sync(Repository repo, String[] args) throws IOException, InterruptedException {
        GitSync commandExecutor = new GitSync(repo, parseArguments(args));
        commandExecutor.sync();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var cwd = Paths.get("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() ->
                die("no repository found at " + cwd)
        );

        GitSync commandExecutor = new GitSync(repo, parseArguments(args));
        commandExecutor.sync();
    }
}

