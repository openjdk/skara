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

    private static int pull() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "pull");
        pb.inheritIO();
        return pb.start().waitFor();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
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

        var cwd = Paths.get("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() ->
                die("error: no repository found at " + cwd.toString())
        );

        HttpProxy.setup();

        var remotes = repo.remotes();

        String upstream = null;
        if (arguments.contains("from")) {
            upstream = arguments.get("from").asString();
        } else {
            var lines = repo.config("sync.from");
            if (lines.size() == 1 && remotes.contains(lines.get(0))) {
                upstream = lines.get(0);
            } else {
                if (remotes.contains("origin")) {
                    var originPullPath = repo.pullPath("origin");
                    try {
                        var uri = Remote.toWebURI(originPullPath);
                        upstream = Forge.from(URI.create(uri.getScheme() + "://" + uri.getHost()))
                                        .flatMap(f -> f.repository(uri.getPath().substring(1)))
                                        .flatMap(r -> r.parent())
                                        .map(p -> p.webUrl().toString())
                                        .orElse(null);
                    } catch (IllegalArgumentException e) {
                        upstream = null;
                    }
                }
            }
        }

        if (upstream == null) {
            die("Could not find upstream repository, please specify one with --from");
        }
        var upstreamPullPath = remotes.contains(upstream) ?
            Remote.toURI(repo.pullPath(upstream)) : URI.create(upstream);

        String origin = null;
        if (arguments.contains("to")) {
            origin = arguments.get("to").asString();
        } else {
            var lines = repo.config("sync.to");
            if (lines.size() == 1) {
                if (!remotes.contains(lines.get(0))) {
                    die("The given remote to push to, " + lines.get(0) + ", does not exist");
                } else {
                    origin = lines.get(0);
                }
            } else {
                origin = "origin";
            }
        }
        var originPushPath = Remote.toURI(repo.pushPath(origin));

        var branches = new HashSet<String>();
        if (arguments.contains("branches")) {
            var requested = arguments.get("branches").asString().split(",");
            for (var branch : requested) {
                branches.add(branch.trim());
            }
        }

        for (var branch : repo.remoteBranches(upstream)) {
            var name = branch.name();
            if (!branches.isEmpty() && !branches.contains(name)) {
                System.out.println("Skipping branch " + name);
                continue;
            }
            System.out.print("Syncing " + upstream + "/" + name + " to " + origin + "/" + name + "... ");
            System.out.flush();
            var fetchHead = repo.fetch(upstreamPullPath, branch.hash().hex());
            repo.push(fetchHead, originPushPath, name);
            System.out.println("done");
        }

        var shouldPull = arguments.contains("pull");
        if (!shouldPull) {
            var lines = repo.config("sync.pull");
            shouldPull = lines.size() == 1 && lines.get(0).toLowerCase().equals("always");
        }
        if (shouldPull) {
            int err = pull();
            if (err != 0) {
                System.exit(err);
            }
        }
    }
}
