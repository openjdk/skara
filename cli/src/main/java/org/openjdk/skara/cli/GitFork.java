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
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.proxy.HttpProxy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
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

    private static Repository clone(URI from, Path dest, boolean isMercurial) throws IOException {
        try {
            var to = dest == null ? Path.of(from.getPath()).getFileName() : dest;
            if (to.toString().endsWith(".git")) {
                to = Path.of(to.toString().replace(".git", ""));
            }

            var vcs = isMercurial ? "hg" : "git";
            var pb = new ProcessBuilder(vcs, "clone", from.toString(), to.toString());
            pb.inheritIO();
            var p = pb.start();
            var res = p.waitFor();
            if (res != 0) {
                exit("'" + vcs + " clone " + from.toString() + " " + to.toString() + "' failed with exit code: " + res);
            }
            return Repository.get(to).orElseThrow(() -> new IOException("Could not find repository"));
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
                 .required(),
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

        final var uri = URI.create(arguments.at(0).or(die("No URI for upstream repository provided")).asString());
        if (uri == null) {
            exit("Not a valid URI: " + uri);
        }
        final var hostName = uri.getHost();
        var path = uri.getPath();
        final var protocol = uri.getScheme();
        final var token = isMercurial ? System.getenv("HG_TOKEN") : System.getenv("GIT_TOKEN");
        final var username = arguments.contains("username") ? arguments.get("username").asString() : null;
        final var credentials = GitCredentials.fill(hostName, path, username, token, protocol);

        if (credentials.password() == null) {
            exit("No token for host " + hostName + " found, use git-credentials or the environment variable GIT_TOKEN");
        }

        if (credentials.username() == null) {
            exit("No username for host " + hostName + " found, use git-credentials or the flag --username");
        }

        var host = Host.from(uri, new PersonalAccessToken(credentials.username(), credentials.password()));
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        var fork = host.getRepository(path).fork();

        if (token == null) {
            GitCredentials.approve(credentials);
        }

        var webUrl = fork.getWebUrl();
        if (isMercurial) {
            webUrl = URI.create("git+" + webUrl.toString());
        }
        if (arguments.at(1).isPresent()) {
            System.out.println("Fork available at: " + fork.getWebUrl());
            var dest = arguments.at(1).asString();
            System.out.println("Cloning " + webUrl + "...");
            var repo = clone(webUrl, Path.of(dest), isMercurial);
            var remoteWord = isMercurial ? "path" : "remote";
            System.out.print("Adding " + remoteWord + " 'upstream' for " + uri.toString() + "...");
            var upstreamUrl = uri.toString();
            if (isMercurial) {
                upstreamUrl = "git+" + upstreamUrl;
            }
            repo.addRemote("upstream", upstreamUrl);
            System.out.println("done");
        } else {
            System.out.println(webUrl);
        }
    }
}
