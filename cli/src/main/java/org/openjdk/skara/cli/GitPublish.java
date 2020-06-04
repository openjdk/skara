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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
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

    private static class RecordingOutputStream extends OutputStream {
        private final OutputStream target;
        private final boolean shouldForward;
        private byte[] output;
        private int index;

        RecordingOutputStream(OutputStream target, boolean shouldForward) {
            this.target = target;
            this.shouldForward = shouldForward;
            this.output = new byte[1024];
            this.index = 0;
        }

        @Override
        public void write(int b) throws IOException {
            if (index == output.length) {
                output = Arrays.copyOf(output, output.length * 2);
            }
            output[index] = (byte) b;
            index++;

            if (shouldForward) {
                target.write(b);
                target.flush();
            }
        }

        String output() {
            return new String(output, 0, index + 1, StandardCharsets.UTF_8);
        }
    }

    private static int pushAndFollow(String remote, Branch b, boolean isQuiet, String browser) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "push", "--set-upstream", "--progress", remote, b.name());
        if (isQuiet) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        var p = pb.start();
        var recording = new RecordingOutputStream(System.err, !isQuiet);
        p.getErrorStream().transferTo(recording);
        int err = p.waitFor();
        if (err == 0) {
            var lines = recording.output().lines().collect(Collectors.toList());
            for (var line : lines) {
                if (line.startsWith("remote:")) {
                    var parts = line.split("\\s");
                    for (var part : parts) {
                        if (part.startsWith("https://")) {
                            var browserPB = new ProcessBuilder(browser, part);
                            browserPB.start().waitFor(); // don't care about status
                            break;
                        }
                    }
                }
            }
        }
        return err;
    }

    private static int push(String remote, Branch b, boolean isQuiet) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("git", "push"));
        if (isQuiet) {
            cmd.add("--quiet");
        }
        cmd.addAll(List.of("--set-upstream", remote, b.name()));
        var pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    private static String getOption(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        var lines = repo.config("publish." + name);
        return lines.size() == 1 ? lines.get(0) : null;
    }

    private static boolean getSwitch(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.contains(name)) {
            return true;
        }

        var lines = repo.config("publish." + name);
        return lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Switch.shortcut("q")
                  .fullname("quiet")
                  .helptext("Silence all output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("follow")
                  .helptext("Open link provided by remote")
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

        var branch = repo.currentBranch().get();
        var isQuiet = getSwitch("quiet", arguments, repo);
        var shouldFollow = getSwitch("follow", arguments, repo);
        int err = 0;
        if (shouldFollow) {
            var browser = getOption("browser", arguments, repo);
            if (browser == null) {
                var os = System.getProperty("os.name").toLowerCase();
                if (os.startsWith("win")) {
                    browser = "explorer";
                } else if (os.startsWith("mac")) {
                    browser = "open";
                } else {
                    // Assume GNU/Linux
                    browser = "xdg-open";
                }
            }
            err = pushAndFollow(remote, branch, isQuiet, browser);
        } else {
            err = push(remote, branch, isQuiet);
        }
        System.exit(err);
    }
}
