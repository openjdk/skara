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
import org.openjdk.skara.version.Version;

import java.io.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;

public class GitPublish {
    private static <T> Supplier<T> die(String fmt, Object... args) {
        return () -> {
            System.err.println(String.format(fmt, args));
            System.exit(1);
            return null;
        };
    }

    private static int pushAndTrack(String remote, Branch b, boolean isQuiet) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "push"));
        if (isQuiet) {
            cmd.add("--quiet");
        }
        cmd.addAll(List.of("--set-upstream", remote, b.name()));
        var pb = new ProcessBuilder(cmd);
        if (isQuiet) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
        } else {
            pb.inheritIO();
        }
        var p = pb.start();
        var errorOutput = p.getErrorStream().readAllBytes();
        int err = p.waitFor();
        if (err != 0) {
            System.out.write(errorOutput, 0, errorOutput.length);
            System.out.flush();
        }
        return err;
    }

    private static String getOption(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        var lines = repo.config("sync." + name);
        return lines.size() == 1 ? lines.get(0) : null;
    }


    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Switch.shortcut("q")
                  .fullname("quiet")
                  .helptext("Silence all output")
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
                 .describe("ORIGIN")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-publish", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-publish version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        var cwd = Path.of("").toAbsolutePath();
        var repo = Repository.get(cwd).or(die("error: no repository found at " + cwd.toString())).get();
        var remote = arguments.at(0).orString("origin");

        var pushPath = repo.pushPath(remote);
        if (pushPath.startsWith("http://") || pushPath.startsWith("https://")) {
            var uri = URI.create(pushPath);
            var token = System.getenv("GIT_TOKEN");
            var username = getOption("username", arguments, repo);
            var credentials = GitCredentials.fill(uri.getHost(),
                                                  uri.getPath(),
                                                  username,
                                                  token,
                                                  uri.getScheme());
            if (credentials.password() == null) {
                die("error: no personal access token found, use git-credentials or the environment variable GIT_TOKEN");
            }
            if (credentials.username() == null) {
                die("error: no username for " + uri.getHost() + " found, use git-credentials or the flag --username");
            }
            if (token != null) {
                GitCredentials.approve(credentials);
            }
        }

        var currentBranch = repo.currentBranch();
        if (currentBranch.isEmpty()) {
            System.err.println("error: the repository is in a detached HEAD state");
            System.exit(1);
        }

        var isQuiet = arguments.contains("quiet");
        if (!isQuiet) {
            var lines = repo.config("publish.quiet");
            isQuiet = lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
        }
        var err = pushAndTrack(remote, repo.currentBranch().get(), isQuiet);
        if (err != 0) {
            System.exit(err);
        }
    }
}
