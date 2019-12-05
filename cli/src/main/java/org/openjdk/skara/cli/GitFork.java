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
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.proxy.HttpProxy;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;

public class GitFork {
    private static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    private static <T> Supplier<T> die(String fmt, Object... args) {
        return () -> {
            exit(fmt, args);
            return null;
        };
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // do nothing
        }
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

    private static Repository clone(List<String> args, String to, boolean isMercurial) throws IOException {
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

    public static void main(String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Username on host")
                  .optional(),
            Option.shortcut("")
                  .fullname("reference")
                  .describe("DIR")
                  .helptext("Same as git clones flag 'reference-if-able'")
                  .optional(),
            Option.shortcut("")
                  .fullname("depth")
                  .describe("N")
                  .helptext("Same as git clones flag 'depth'")
                  .optional(),
            Option.shortcut("")
                  .fullname("shallow-since")
                  .describe("DATE")
                  .helptext("Same as git clones flag 'shallow-since'")
                  .optional(),
            Option.shortcut("")
                  .fullname("forge")
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

        var parser = new ArgumentParser("git-fork", flags, inputs);
        var arguments = parser.parse(args);
        var isMercurial = arguments.contains("mercurial");

        if (arguments.contains("version")) {
            System.out.println("git-fork version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        HttpProxy.setup();

        boolean useSSH = arguments.contains("ssh");
        if (!useSSH) {
            var config = gitConfig("fork.ssh");
            useSSH = config != null && config.toLowerCase().equals("true");
        }

        boolean useHTTPS = arguments.contains("https");
        if (!useHTTPS) {
            var config = gitConfig("fork.https");
            useHTTPS = config != null && config.toLowerCase().equals("true");
        }

        var forge = arguments.get("forge").orString(() -> gitConfig("fork.forge"));

        URI uri = null;
        if (arguments.at(0).isPresent()) {
            var arg = arguments.at(0).asString();
            if (forge != null) {
                var extraSlash = arg.startsWith("/") ? "" : "/";
                uri = URI.create("https://" + forge + extraSlash + arg);
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
        var username = arguments.get("username").orString(() -> gitConfig("fork.username"));
        var credentials = GitCredentials.fill(webURI.getHost(), webURI.getPath(), username, token, webURI.getScheme());

        if (credentials.password() == null) {
            exit("error: no personal acess token found, use git-credentials or the environment variable GIT_TOKEN");
        }
        if (credentials.username() == null) {
            exit("error: no username for found, use git-credentials or the flag --username");
        }

        var host = Forge.from(webURI, new Credential(credentials.username(), credentials.password()));
        if (host.isEmpty()) {
            exit("error: could not connect to host " + webURI.getHost());
        }

        var hostedRepo = host.get().repository(webURI.getPath().substring(1)).orElseThrow(() ->
            new IOException("Could not find repository at " + webURI.toString())
        );

        var fork = hostedRepo.fork();
        if (token == null) {
            GitCredentials.approve(credentials);
        }

        var forkWebUrl = fork.webUrl();
        if (isMercurial) {
            forkWebUrl = URI.create("git+" + forkWebUrl.toString());
        }

        boolean noClone = arguments.contains("no-clone");
        if (!noClone) {
            var config = gitConfig("fork.no-clone");
            noClone = config != null && config.toLowerCase().equals("true");
        }
        boolean noRemote = arguments.contains("no-remote");
        if (!noRemote) {
            var config = gitConfig("fork.no-remote");
            noRemote = config != null && config.toLowerCase().equals("true");
        }
        if (noClone || !arguments.at(0).isPresent()) {
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
                }
            }
        } else {
            var reference = arguments.get("reference").orString(() -> gitConfig("fork.reference"));
            if (reference.startsWith("~" + File.separator)) {
                reference = System.getProperty("user.home") + reference.substring(1);
            }
            var depth = arguments.get("depth").orString(() -> gitConfig("fork.depth"));
            var shallowSince = arguments.get("shallow-since").orString(() -> gitConfig("fork.shallow-since"));

            URI cloneURI = null;
            if (forge != null) {
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
                System.out.print("Adding " + remoteWord + " 'upstream' for " + webURI.toString() + "...");
                var upstreamUrl = webURI.toString();
                if (isMercurial) {
                    upstreamUrl = "git+" + upstreamUrl;
                }
                repo.addRemote("upstream", upstreamUrl);

                System.out.println("done");
            }
        }
    }
}
