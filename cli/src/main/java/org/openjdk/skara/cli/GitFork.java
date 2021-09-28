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
    private final boolean isMercurial;

    public GitFork(Arguments arguments) {
        this.arguments = arguments;
        isMercurial = arguments.contains("mercurial");
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

    private static String gitConfig(String key) {
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

    private Repository clone(List<String> args, String to, boolean isMercurial) throws IOException {
        try {
            var vcs = isMercurial ? "hg" : "git";
            var command = new ArrayList<String>();
            command.add(vcs);
            command.add("clone");
            command.addAll(args);
            command.add(to);
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            var p = pb.start();
            var res = p.waitFor();
            if (res != 0) {
                exit("error: '" + vcs + " clone " + String.join(" ", args) + "' failed with exit code: " + res);
            }
            return Repository.get(Path.of(to)).orElseThrow(() -> new IOException("Could not find repository"));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        GitFork commandExecutor = new GitFork(parseArguments(args));
        commandExecutor.fork();
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
                  .optional(),
            Switch.shortcut("")
                  .fullname("mercurial")
                  .helptext("Force use of mercurial")
                  .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("URI")
                 .singular()
                 .optional(),
            Input.position(1)
                 .describe("NAME")
                 .singular()
                 .optional());

        var parser = new ArgumentParser("git fork", flags, inputs);
        return parser.parse(args);
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

        HttpProxy.setup();

        var subsection = arguments.at(0).isPresent() ? arguments.at(0).asString() : null;

        boolean useSSH = getSwitch("ssh", subsection);
        var hostname = getOption("host", subsection);

        URI uri;
        if (arguments.at(0).isPresent()) {
            var arg = arguments.at(0).asString();
            if (hostname != null) {
                var extraSlash = arg.startsWith("/") ? "" : "/";
                uri = URI.create("https://" + hostname + extraSlash + arg);
            } else {
                var argURI = URI.create(arg);
                uri = argURI.getScheme() == null ?
                    URI.create("https://" + argURI.getHost() + argURI.getPath()) :
                    argURI;
            }
        } else {
            var cwd = Path.of("").toAbsolutePath();
            var repo = Repository.get(cwd).orElseGet(die("error: no git repository found at " + cwd));
            uri = URI.create(repo.pullPath("origin"));
        }

        if (uri == null) {
            exit("error: not a valid URI: " + uri);
        }

        var webURI = Remote.toWebURI(uri.toString());
        var token = isMercurial ? System.getenv("HG_TOKEN") : System.getenv("GIT_TOKEN");
        var username = getOption("username", subsection);
        var credentials = GitCredentials.fill(webURI.getHost(), webURI.getPath(), username, token, webURI.getScheme());

        if (credentials.password() == null) {
            exit("error: no personal access token found, use git-credentials or the environment variable GIT_TOKEN");
        }
        if (credentials.username() == null) {
            exit("error: no username for " + webURI.getHost() + " found, use git-credentials or the flag --username");
        }

        var host = ForgeUtils.from(webURI, new Credential(credentials.username(), credentials.password()));
        if (host.isEmpty()) {
            exit("error: could not connect to host " + webURI.getHost());
        }

        var repositoryPath = webURI.getPath().substring(1);

        if (repositoryPath.endsWith("/")) {
            repositoryPath = repositoryPath.substring(0, repositoryPath.length() - 1);
        }

        var hostedRepo = host.get().repository(repositoryPath).orElseThrow(() ->
            new IOException("Could not find repository at " + webURI)
        );

        var fork = hostedRepo.fork();
        if (token == null) {
            GitCredentials.approve(credentials);
        }

        var forkWebUrl = fork.webUrl();
        if (isMercurial) {
            forkWebUrl = URI.create("git+" + forkWebUrl.toString());
        }

        boolean noClone = getSwitch("no-clone", subsection);
        boolean noRemote = getSwitch("no-remote", subsection);
        boolean shouldSync = getSwitch("sync", subsection);
        if (noClone || !arguments.at(0).isPresent()) {
            doWithoutLocalClone(useSSH, forkWebUrl, noRemote, shouldSync);
        } else {
            doWithLocalClone(subsection, useSSH, hostname, webURI, forkWebUrl, noRemote, shouldSync);
        }
    }

    private void doWithLocalClone(String subsection, boolean useSSH, String hostname, URI webURI, URI forkWebUrl, boolean noRemote, boolean shouldSync) throws IOException, InterruptedException {
        var reference = getOption("reference", subsection);
        if (reference != null && reference.startsWith("~" + File.separator)) {
            reference = System.getProperty("user.home") + reference.substring(1);
        }
        var depth = getOption("depth", subsection);
        var shallowSince = getOption("shallow-since", subsection);

        URI cloneURI;
        if (hostname != null) {
            if (useSSH) {
                cloneURI = URI.create("ssh://git@" + forkWebUrl.getHost() + forkWebUrl.getPath() + ".git");
            } else {
                cloneURI = URI.create("https://" + forkWebUrl.getHost() + forkWebUrl.getPath());
            }
        } else {
            if (useSSH) {
                cloneURI = URI.create("ssh://git@" + forkWebUrl.getHost() + forkWebUrl.getPath() + ".git");
            } else {
                cloneURI = forkWebUrl;
            }
        }

        System.out.println("Fork available at: " + forkWebUrl);
        System.out.println("Cloning " + cloneURI + "...");

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
        cloneArgs.add(cloneURI.toString());

        var defaultTo = Path.of(cloneURI.getPath()).getFileName().toString();
        if (defaultTo.endsWith(".git")) {
            defaultTo = defaultTo.substring(0, defaultTo.length() - ".git".length());
        }
        String to = arguments.at(1).isPresent() ?
            arguments.at(1).asString() :
            defaultTo;
        var repo = clone(cloneArgs, to, isMercurial);

        if (!noRemote) {
            var remoteWord = isMercurial ? "path" : "remote";
            System.out.print("Adding " + remoteWord + " 'upstream' for " + webURI + "...");
            var upstreamUrl = webURI.toString();
            if (isMercurial) {
                upstreamUrl = "git+" + upstreamUrl;
            }
            repo.addRemote("upstream", upstreamUrl);

            System.out.println("done");

            if (shouldSync) {
                GitSync.sync(repo, new String[] {"--from", "upstream", "--to", "origin", "--fast-forward"});
            }

            var setupPrePushHooksOption = getSwitch("setup-pre-push-hook", subsection);
            if (setupPrePushHooksOption) {
                var res = GitJCheck.run(repo, new String[] {"--setup-pre-push-hook"});
                if (res != 0) {
                    System.exit(res);
                }
            }
        }
    }

    private void doWithoutLocalClone(boolean useSSH, URI forkWebUrl, boolean noRemote, boolean shouldSync) throws IOException, InterruptedException {
        if (!arguments.at(0).isPresent()) {
            var cwd = Path.of("").toAbsolutePath();
            var repo = Repository.get(cwd).orElseGet(die("error: no git repository found at " + cwd));

            var forkURL = useSSH ?
                "ssh://git@" + forkWebUrl.getHost() + forkWebUrl.getPath() :
                forkWebUrl.toString();
            System.out.println(forkURL);

            if (!noRemote) {
                var remoteWord = isMercurial ? "path" : "remote";
                System.out.print("Adding " + remoteWord + " 'clone' for " + forkURL + "...");
                if (isMercurial) {
                    forkURL = "git+" + forkURL;
                }
                repo.addRemote("fork", forkURL);
                System.out.println("done");

                if (shouldSync) {
                    GitSync.sync(repo, new String[] {"--from", "origin", "--to", "fork"});
                }
            }
        }
    }
}
