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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;

public class GitFork {
    private final Arguments arguments;
    private final boolean isDryRun;
    private final String sourceUri;

    public GitFork(Arguments arguments) {
        this.arguments = arguments;
        this.isDryRun = arguments.contains("dry-run");
        this.sourceUri = arguments.at(0).asString();
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
        } catch (InterruptedException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private String getOption(String name, String subsection) {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        if (subsection != null && !subsection.isEmpty()) {
            var subsectionSpecific = gitConfig("fork." + subsection + "." + name);
            if (subsectionSpecific != null) {
                return subsectionSpecific;
            }
        }

        return gitConfig("fork." + name);
    }

    private boolean getSwitch(String name, String subsection) {
        var option = getOption(name, subsection);
        return option != null && option.equalsIgnoreCase("true");
    }

    private URI getUriFromArgs(String subsection) {
        URI uri;
        var hostname = getOption("host", subsection);

        var uriArg = sourceUri;
        if (hostname != null) {
            var extraSlash = uriArg.startsWith("/") ? "" : "/";
            uri = URI.create("https://" + hostname + extraSlash + uriArg);
        } else {
            var argURI = URI.create(uriArg);
            uri = argURI.getScheme() == null ?
                    URI.create("https://" + argURI.getHost() + argURI.getPath()) :
                    argURI;
        }

        if (uri == null) {
            exit("error: not a valid URI: " + uri);
        }
        return uri;
    }

    private String getTargetDir(URI cloneURI) {
        var defaultTargetDir = Path.of(cloneURI.getPath()).getFileName().toString();
        if (defaultTargetDir.endsWith(".git")) {
            defaultTargetDir = defaultTargetDir.substring(0, defaultTargetDir.length() - ".git".length());
        }
        String targetDir = arguments.at(1).isPresent() ?
                arguments.at(1).asString() :
                defaultTargetDir;
        return targetDir;
    }

    private Repository clone(List<String> args, URI cloneURI, String targetDir) throws IOException {
        try {
            var command = new ArrayList<String>();
            command.add("git");
            command.add("clone");
            command.addAll(args);
            command.add(cloneURI.toString());
            command.add(targetDir);
            if (!isDryRun) {
                var pb = new ProcessBuilder(command);
                pb.inheritIO();
                var p = pb.start();
                var res = p.waitFor();
                if (res != 0) {
                    exit("error: '" + "git" + " clone " + String.join(" ", args) + "' failed with exit code: " + res);
                }
            }
            return Repository.get(Path.of(targetDir)).orElseThrow(() -> new IOException("Could not find repository"));
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

        var subsection = sourceUri;

        URI upstreamURI = getUriFromArgs(subsection);

        var upstreamWebURI = Remote.toWebURI(upstreamURI.toString());
        var repositoryPath = upstreamWebURI.getPath().substring(1);

        if (repositoryPath.endsWith("/")) {
            repositoryPath = repositoryPath.substring(0, repositoryPath.length() - 1);
        }

        var token = System.getenv("GIT_TOKEN");
        var username = getOption("username", subsection);

        var credentials = GitCredentials.fill(upstreamWebURI.getHost(), upstreamWebURI.getPath(), username, token, upstreamWebURI.getScheme());

        if (credentials.password() == null) {
            exit("error: no personal access token found, use git-credentials or the environment variable GIT_TOKEN");
        }
        if (credentials.username() == null) {
            exit("error: no username for " + upstreamWebURI.getHost() + " found, use git-credentials or the flag --username");
        }

        var host = ForgeUtils.from(upstreamWebURI, new Credential(credentials.username(), credentials.password()));
        if (host.isEmpty()) {
            exit("error: could not connect to host " + upstreamWebURI.getHost());
        }

        var upstreamHostedRepo = host.get().repository(repositoryPath).orElseThrow(() ->
            new IOException("Could not find repository at " + upstreamWebURI)
        );

        // Create fork at Git Forge
        var originHostedRepo = upstreamHostedRepo.fork();
        if (token == null) {
            GitCredentials.approve(credentials);
        }

        var originWebURI = originHostedRepo.webUrl();

        boolean noClone = getSwitch("no-clone", subsection);
        if (!noClone) {
            createLocalClone(subsection, upstreamWebURI, originWebURI);
        }
    }

    private void createLocalClone(String subsection, URI upstreamWebURI, URI originWebURI) throws IOException, InterruptedException {
        boolean useSSH = getSwitch("ssh", subsection);
        URI cloneURI;
        if (getOption("host", subsection) != null) {
            if (useSSH) {
                cloneURI = URI.create("ssh://git@" + originWebURI.getHost() + originWebURI.getPath() + ".git");
            } else {
                cloneURI = URI.create("https://" + originWebURI.getHost() + originWebURI.getPath());
            }
        } else {
            if (useSSH) {
                cloneURI = URI.create("ssh://git@" + originWebURI.getHost() + originWebURI.getPath() + ".git");
            } else {
                cloneURI = originWebURI;
            }
        }

        System.out.println("Fork available at: " + originWebURI);
        System.out.println("Cloning " + cloneURI + "...");

        var reference = getOption("reference", subsection);
        if (reference != null && reference.startsWith("~" + File.separator)) {
            reference = System.getProperty("user.home") + reference.substring(1);
        }
        var depth = getOption("depth", subsection);
        var shallowSince = getOption("shallow-since", subsection);

        var cloneArgs = new ArrayList<String>();
        if (reference != null) {
            cloneArgs.add("--reference-if-able=" + reference);
            cloneArgs.add("--dissociate");
        }
        if (depth != null) {
            cloneArgs.add("--depth=" + depth);
        }
        if (shallowSince != null) {
            cloneArgs.add("--shallow-since=" + shallowSince);
        }

        String targetDir = getTargetDir(cloneURI);
        var repo = clone(cloneArgs, cloneURI, targetDir);

        if (!getSwitch("no-remote", subsection)) {
            System.out.print("Adding remote 'upstream' for " + upstreamWebURI + "...");
            if (!isDryRun) {
                repo.addRemote("upstream", upstreamWebURI.toString());
            }

            System.out.println("done");

            if (getSwitch("sync", subsection)) {
                if (!isDryRun) {
                    GitSync.sync(repo, new String[] {"--from", "upstream", "--to", "origin", "--fast-forward"});
                }
            }

            var setupPrePushHooksOption = getSwitch("setup-pre-push-hook", subsection);
            if (setupPrePushHooksOption) {
                if (!isDryRun) {
                    var res = GitJCheck.run(repo, new String[] {"--setup-pre-push-hook"});
                    if (res != 0) {
                        System.exit(res);
                    }
                }
            }
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
                        .helptext("Use the ssh:// protocol when cloning")
                        .optional(),
                Switch.shortcut("")
                        .fullname("https")
                        .helptext("Use the https:// protocol when cloning")
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
