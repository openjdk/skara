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
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.webrev.*;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.net.http.*;
import static java.net.http.HttpResponse.BodyHandlers;
import java.net.URI;

public class GitDefpath {
    private static String config(ReadOnlyRepository repo, String key, String fallback) throws IOException {
        var lines = repo.config(key);
        if (lines.size() == 0) {
            return fallback;
        }

        return lines.get(0);
    }

    static boolean probe(URI uri) {
        try {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(uri).build();
            var res = client.send(req, BodyHandlers.discarding());
            return res.statusCode() < 400;
        } catch (InterruptedException e) {
            // do nothing
        } catch (IOException e) {
            // do nothing
        }

        return false;
    }

    static String probe(String primary, String fallback) {
        if (primary.startsWith("http") || primary.startsWith("https")) {
            var uri = URI.create(primary);
            if (probe(uri)) {
                return primary;
            }

            if (fallback == null) {
                System.err.println("error: repository " + primary + " not found");
                System.exit(1);
            }

            if (fallback.startsWith("http") || fallback.startsWith("https")) {
                var alternative = URI.create(fallback + uri.getPath());
                if (probe(alternative)) {
                    return fallback;
                }
            }

            System.err.println("error: repository " + primary + " not found");
            System.err.println("error: repository " + fallback + " not found");
            System.exit(1);
        }

        return primary;
    }

    static String toPushPath(String pullPath, String username, boolean isMercurial) {
        if (pullPath.startsWith("http") || pullPath.startsWith("https")) {
            var uri = URI.create(pullPath);
            var scheme = uri.getScheme();
            var user = isMercurial ? username : "git";
            return URI.create("ssh://" + user + "@" + uri.getAuthority() + uri.getPath()).toString();
        }

        return pullPath;
    }

    static void showPaths(ReadOnlyRepository repo, String remote) throws IOException {
        showPaths(repo, repo.pullPath(remote), repo.pushPath(remote));

    }

    static void showPaths(ReadOnlyRepository repo, String pull, String push) throws IOException {
        System.out.format("%s:\n", repo.root().toString());
        System.out.format("         default = %s\n", pull);
        System.out.format("    default-push = %s\n", push);
    }

    static String getUsername(ReadOnlyRepository repo, Arguments arguments) {
        var arg = arguments.get("username");
        if (arg.isPresent()) {
            return arg.asString();
        }

        try {
            var lines = repo.config("defpath.username");
            if (lines.size() == 1) {
                return lines.get(0);
            }
        } catch (IOException e) {
        }

        try {
            var conf = repo.username();
            if (conf.isPresent()) {
                return conf.get();
            }
        } catch (IOException e) {
        }

        return System.getProperty("user.name");
    }
    private static void die(String message) {
        System.err.println(message);
        System.exit(1);
    }

    public static void main(String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("username for push URL")
                  .optional(),
            Option.shortcut("r")
                  .fullname("remote")
                  .describe("URI")
                  .helptext("remote for which to set paths")
                  .optional(),
            Option.shortcut("s")
                  .fullname("secondary")
                  .describe("URL")
                  .helptext("secondary peer repository base URL")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("d")
                  .fullname("default")
                  .helptext("use current default path to compute push path")
                  .optional(),
            Switch.shortcut("")
                  .fullname("upstream")
                  .helptext("create remote 'upstream' for the upstream repository")
                  .optional(),
            Switch.shortcut("")
                  .fullname("fork")
                  .helptext("create remote 'fork' for the personal fork of the repository")
                  .optional(),
            Switch.shortcut("g")
                  .fullname("gated")
                  .helptext("create gated push URL")
                  .optional(),
            Switch.shortcut("n")
                  .fullname("dry-run")
                  .helptext("do not perform actions, just print output")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("PEER")
                 .singular()
                 .optional(),
            Input.position(1)
                 .describe("PEER-PUSH")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-defpath", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-defpath version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        var cwd = Path.of("").toAbsolutePath();
        var repository = Repository.get(cwd);
        if (!repository.isPresent()) {
            die(String.format("error: %s is not a hg repository", cwd.toString()));
        }
        var repo = repository.get();

        var username = getUsername(repo, arguments);
        if (username == null) {
            die("error: no username found");
        }

        var isMercurial = arguments.contains("mercurial");
        var remote = arguments.contains("remote") ? arguments.get("remote").asString() : null;
        if (remote == null) {
            var lines = repo.config("defpath.remote");
            if (lines.size() == 1) {
                remote = lines.get(0);
            }
        }
        if (remote == null) {
            remote = isMercurial ? "default": "origin";
        }

        if (arguments.contains("gated")) {
            System.err.println("warning: gated push repositories are no longer used, option ignored");
        }

        if ((arguments.at(0).isPresent() || arguments.at(1).isPresent()) && arguments.contains("default")) {
            die("error: peers cannot be specified together with -d flag");
        }

        var fallback = arguments.contains("secondary") ? arguments.get("secondary").asString() : null;
        if (fallback == null) {
            var lines = repo.config("defpath.secondary");
            if (lines.size() == 1) {
                fallback = lines.get(0);
            }
        }

        HttpProxy.setup();

        String pullPath = null;
        if (arguments.at(0).isPresent()) {
            pullPath = arguments.at(0).asString();
        } else {
            var useDefault = false;
            if (arguments.contains("default")) {
                useDefault = true;
            } else {
                var lines = repo.config("defpath.default");
                useDefault = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
            }

            if (useDefault) {
                try {
                    pullPath = repo.pullPath(remote);
                } catch (IOException e) {
                    die("error: -d flag specified but repository has no default path");
                }
            }
        }

        var dryRun = false;
        if (arguments.contains("dry-run")) {
            dryRun = true;
        } else {
            var lines = repo.config("defpath.dry-run");
            dryRun = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
        }

        URI upstreamURI = null;
        URI forkURI = null;
        var remotes = repo.remotes();
        if (remotes.contains("origin")) {
            var setUpstream = arguments.contains("upstream");
            if (!arguments.contains("upstream")) {
                var lines = repo.config("defpath.upstream");
                setUpstream = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
            }
            if (setUpstream) {
                var originPullPath = repo.pullPath("origin");
                var uri = Remote.toWebURI(originPullPath);
                upstreamURI = Forge.from(uri)
                                   .flatMap(f -> f.repository(uri.getPath().substring(1)))
                                   .flatMap(r -> r.parent())
                                   .map(p -> p.webUrl())
                                   .orElse(null);
                if (upstreamURI != null && !dryRun) {
                    if (remotes.contains("upstream")) {
                        repo.setPaths("upstream", upstreamURI.toString(), upstreamURI.toString());
                    } else {
                        repo.addRemote("upstream", upstreamURI.toString());
                    }
                }
            }
            var setFork = arguments.contains("fork");
            if (!arguments.contains("fork")) {
                var lines = repo.config("defpath.fork");
                setFork = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
            }
            if (setFork) {
                var originPullPath = repo.pullPath("origin");
                var uri = Remote.toWebURI(originPullPath);
                var credentials = GitCredentials.fill(uri.getHost(), uri.getPath(), null, null, uri.getScheme());
                if (credentials.password() == null) {
                    System.err.println("error: no personal access token found for " + uri.getHost() + ", use git-credentials");
                    System.exit(1);
                }
                if (credentials.username() == null) {
                    System.err.println("error: no username for " + uri.getHost() + " found, use git-credentials");
                    System.exit(1);
                }
                forkURI = Forge.from(uri, new Credential(credentials.username(), credentials.password()))
                               .flatMap(f -> f.repository(uri.getPath().substring(1)))
                               .map(r -> r.fork())
                               .map(fork -> fork.webUrl())
                               .orElse(null);
                if (forkURI != null) {
                    GitCredentials.approve(credentials);
                    forkURI = URI.create("ssh://git@" + forkURI.getHost() + forkURI.getPath());
                    if (!dryRun) {
                        if (remotes.contains("fork")) {
                            repo.setPaths("fork", forkURI.toString(), forkURI.toString());
                        } else {
                            repo.addRemote("fork", forkURI.toString());
                        }
                    }
                }

            }
        }

        if (pullPath == null) {
            showPaths(repo, remote);
            if (upstreamURI != null) {
                System.out.format("        upstream = %s\n", upstreamURI.toString());
            }
            if (forkURI != null) {
                System.out.format("            fork = %s\n", forkURI.toString());
            }
            System.exit(0);
        }

        var newPullPath = probe(pullPath, fallback);

        String pushPath = null;
        if (arguments.at(1).isPresent()) {
            pushPath = arguments.at(1).asString();
        }

        var newPushPath = pushPath == null ? toPushPath(newPullPath, username, isMercurial) : pushPath;

        if (dryRun) {
            showPaths(repo, newPullPath, newPushPath);
        } else {
            repo.setPaths(remote, newPullPath, newPushPath);
        }
    }
}
