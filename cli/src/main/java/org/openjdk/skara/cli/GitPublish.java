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

import java.io.*;
import java.nio.file.*;
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

    private static int pushAndTrack(String remote, Branch b) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "push", "--set-upstream", remote, b.name());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
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

        var currentBranch = repo.currentBranch();
        if (currentBranch.isEmpty()) {
            System.err.println("error: the repository is in a detached HEAD state");
            System.exit(1);
        }
        var err = pushAndTrack(remote, repo.currentBranch().get());
        if (err != 0) {
            System.exit(err);
        }
    }
}
