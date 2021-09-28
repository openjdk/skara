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
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.version.Version;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;

public class GitFork {
    private final Arguments arguments;
    private final boolean isDryRun;
    private final String sourceArg;

    public GitFork(Arguments arguments) {
        this.arguments = arguments;
        this.isDryRun = arguments.contains("dry-run");
        this.sourceArg = arguments.at(0).asString();
    }

    private String gitConfig(String key) {
        try {
            var pb = new ProcessBuilder("git", "config", key);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();

            var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var res = p.waitFor();
            if (res != 0) {
                return null;
            }

            return output.replace("\n", "");
        } catch (InterruptedException | IOException e) {
            return null;
        }
    }

    private String getOption(String name) {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        var subsectionSpecific = gitConfig("fork." + sourceArg + "." + name);
        if (subsectionSpecific != null) {
            return subsectionSpecific;
        }

        return gitConfig("fork." + name);
    }

    private boolean getSwitch(String name) {
        var option = getOption(name);
        return option != null && option.equalsIgnoreCase("true");
    }

    private URI getURIFromArgs() {
        var hostname = getOption("host");

        try {
            if (hostname != null) {
                // Assume command line argument is just the path component
                var extraSlash = sourceArg.startsWith("/") ? "" : "/";
                return new URI("https://" + hostname + extraSlash + sourceArg);
            } else {
                var uri = new URI(sourceArg);
                if (uri.getScheme() == null) {
                    return new URI("https://" + uri.getHost() + uri.getPath());
                } else {
                    return uri;
                }
            }
        } catch (URISyntaxException e) {
            exit("error: could not form a valid URI from argument: " + sourceArg);
            return null; // make compiler quiet
        }
    }

    private Path getTargetDir(URI cloneURI) {
        if (arguments.at(1).isPresent()) {
            // If user provided an explicit name for target dir, use it
            return Path.of(arguments.at(1).asString());
        } else {
            // Otherwise get the base name from the URI
            var targetDir = Path.of(cloneURI.getPath()).getFileName();
            var targetDirStr = targetDir.toString();

            if (targetDirStr.endsWith(".git")) {
                return Path.of(targetDirStr.substring(0, targetDirStr.length() - ".git".length()));
            } else {
                return targetDir;
            }
        }
    }

    private Repository clone(List<String> args, URI cloneURI, Path targetDir) throws IOException {
        try {
            var command = new ArrayList<String>();
            command.add("git");
            command.add("clone");
            command.addAll(args);
            command.add(cloneURI.toString());
            command.add(targetDir.toString());
            if (!isDryRun) {
                var pb = new ProcessBuilder(command);
                pb.inheritIO();
                var p = pb.start();
                var res = p.waitFor();
                if (res != 0) {
                    exit("error: '" + "git" + " clone " + String.join(" ", args) + "' failed with exit code: " + res);
                }
            }
            return Repository.get(targetDir).orElseThrow(() -> new IOException("Could not find repository"));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public void fork() throws IOException, InterruptedException {
        if (arguments.contains("version")) {
            System.out.println("git-fork version: " + Version.fromManifest().orElse("unknown"));
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

        // Get the upstream repo user specified on the command line
        var upstreamURI = getURIFromArgs();
        var upstreamWebURI = Remote.toWebURI(upstreamURI.toString());
        var credentials = setupCredentials(upstreamWebURI);

        var gitForge = ForgeUtils.from(upstreamWebURI, credentials);
        if (gitForge.isEmpty()) {
            exit("error: could not connect to host " + upstreamWebURI.getHost());
        }

        var repositoryPath = getTrimmedPath(upstreamWebURI);
        var upstreamHostedRepo = gitForge.get().repository(repositoryPath).orElseThrow(() ->
            new IOException("Could not find repository at " + upstreamWebURI)
        );

        // Create personal fork ("origin" from now on) at Git Forge
        var originHostedRepo = upstreamHostedRepo.fork();
        var originWebURI = originHostedRepo.webUrl();
        System.out.println("Fork available at: " + originWebURI);

        if (getSwitch("no-clone")) {
            // We're done here, if we should not create a local clone
            return;
        }

        // Create a local clone
        var cloneURI = getCloneURI(originWebURI);
        System.out.println("Cloning " + cloneURI + "...");
        var repo = clone(getCloneArgs(), cloneURI, getTargetDir(cloneURI));

        // Setup git remote
        if (!getSwitch("no-remote")) {
            System.out.print("Adding remote 'upstream' for " + upstreamWebURI + "...");
            if (!isDryRun) {
                repo.addRemote("upstream", upstreamWebURI.toString());
            }
            System.out.println("done");
        }

        // Sync the fork from upstream
        if (getSwitch("sync")) {
            var syncArgs = new ArrayList<String>();
            syncArgs.add("--fast-forward");
            if (getSwitch("no-remote")) {
                // Propagate --no-remote; and also specify the remote for git sync to work
                syncArgs.add("--no-remote");
                syncArgs.add("--from");
                syncArgs.add(upstreamWebURI.toString());
            }
            if (!isDryRun) {
                GitSync.sync(repo, (String[]) syncArgs.toArray());
            }
        }

        // Setup jcheck hooks
        if (getSwitch("setup-pre-push-hook")) {
            if (!isDryRun) {
                var res = GitJCheck.run(repo, new String[] {"--setup-pre-push-hook"});
                if (res != 0) {
                    System.exit(res);
                }
            }
        }
    }

    private Credential setupCredentials(URI upstreamWebURI) throws IOException {
        var token = System.getenv("GIT_TOKEN");
        var username = getOption("username");

        var credentials = GitCredentials.fill(upstreamWebURI.getHost(), upstreamWebURI.getPath(), username, token, upstreamWebURI.getScheme());

        if (credentials.password() == null) {
            exit("error: no personal access token found, use git-credentials or the environment variable GIT_TOKEN");
        }
        if (credentials.username() == null) {
            exit("error: no username for " + upstreamWebURI.getHost() + " found, use git-credentials or the flag --username");
        }
        if (token == null) {
            GitCredentials.approve(credentials);
        }
        return new Credential(credentials.username(), credentials.password());
    }

    private URI getCloneURI(URI originWebURI) {
        if (getSwitch("ssh")) {
            return URI.create("ssh://git@" + originWebURI.getHost() + originWebURI.getPath() + ".git");
        } else {
            return originWebURI;
        }
    }

    private ArrayList<String> getCloneArgs() {
        var cloneArgs = new ArrayList<String>();

        var reference = getOption("reference");
        if (reference != null) {
            cloneArgs.add("--reference-if-able=" + expandPath(reference));
            cloneArgs.add("--dissociate");
        }

        var depth = getOption("depth");
        if (depth != null) {
            cloneArgs.add("--depth=" + depth);
        }

        var shallowSince = getOption("shallow-since");
        if (shallowSince != null) {
            cloneArgs.add("--shallow-since=" + shallowSince);
        }

        return cloneArgs;
    }

    private static String expandPath(String path) {
        // FIXME: Why is this not done from the shell? It should not be needed.
        if (path.startsWith("~" + File.separator)) {
            return System.getProperty("user.home") + path.substring(1);
        } else {
            return path;
        }
    }

    private static String getTrimmedPath(URI uri) {
        var repositoryPath = uri.getPath().substring(1);

        if (repositoryPath.endsWith("/")) {
            return repositoryPath.substring(0, repositoryPath.length() - 1);
        } else {
            return repositoryPath;
        }
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static <T> Supplier<T> die(String message) {
        return () -> {
            exit(message);
            return null;
        };
    }

    private static Arguments parseArguments(String[] args) {
        var flags = List.of(
                Option.shortcut("u")
                        .fullname("username")
                        .describe("NAME")
                        .helptext("Username on host")
                        .optional(),
                Option.shortcut("")
                        .fullname("reference")
                        .describe("DIR")
                        .helptext("Same as the 'git clone' flags 'reference-if-able' + 'dissociate'")
                        .optional(),
                Option.shortcut("")
                        .fullname("depth")
                        .describe("N")
                        .helptext("Same as the 'git clone' flag 'depth'")
                        .optional(),
                Option.shortcut("")
                        .fullname("shallow-since")
                        .describe("DATE")
                        .helptext("Same as the 'git clone' flag 'shallow-since'")
                        .optional(),
                Switch.shortcut("")
                        .fullname("setup-pre-push-hook")
                        .helptext("Setup a pre-push hook that runs git-jcheck")
                        .optional(),
                Option.shortcut("")
                        .fullname("host")
                        .describe("HOSTNAME")
                        .helptext("Hostname for the forge")
                        .optional(),
                Switch.shortcut("")
                        .fullname("no-clone")
                        .helptext("Just fork the repository, do not clone it")
                        .optional(),
                Switch.shortcut("")
                        .fullname("no-remote")
                        .helptext("Do not add an additional git remote")
                        .optional(),
                Switch.shortcut("")
                        .fullname("ssh")
                        .helptext("Use the ssh:// protocol when cloning (instead of https)")
                        .optional(),
                Switch.shortcut("")
                        .fullname("sync")
                        .helptext("Sync with the upstream repository after successful fork")
                        .optional(),
                Switch.shortcut("n")
                        .fullname("dry-run")
                        .helptext("Only simulate behavior, do no actual changes")
                        .optional(),
                Switch.shortcut("")
                        .fullname("verbose")
                        .helptext("Turn on verbose output")
                        .optional(),
                Switch.shortcut("")
                        .fullname("debug")
                        .helptext("Turn on debugging output")
                        .optional(),
                Switch.shortcut("")
                        .fullname("version")
                        .helptext("Print the version of this tool")
                        .optional());

        var inputs = List.of(
                Input.position(0)
                        .describe("URI")
                        .singular()
                        .required(),
                Input.position(1)
                        .describe("NAME")
                        .singular()
                        .optional());

        var parser = new ArgumentParser("git fork", flags, inputs);
        return parser.parse(args);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        GitFork commandExecutor = new GitFork(parseArguments(args));
        commandExecutor.fork();
    }
}
