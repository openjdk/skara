/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.openjdk.*;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.time.format.DateTimeFormatter;

public class GitHgExport {
    private static void die(String msg) {
        System.err.println("error: " + msg);
        System.exit(1);
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
                 .describe("REV")
                 .singular()
                 .required()
        );

        var parser = new ArgumentParser("git-hg-export", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-hg-export version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        var ref = arguments.at(0).orString("HEAD");
        var cwd = Path.of("").toAbsolutePath();
        var repo = ReadOnlyRepository.get(cwd);
        if (repo.isEmpty()) {
            die("no repository present at: " + cwd);
        }
        var hash = repo.get().resolve(ref);
        if (hash.isEmpty()) {
            die(ref + " does not refer to a commit");
        }
        var commit = repo.get().lookup(hash.get());
        if (commit.isEmpty()) {
            die("internal error - could not lookup " + hash.get());
        }

        var c = commit.get();
        var committer = c.committer();
        if (committer.email() == null || !committer.email().endsWith("@openjdk.org")) {
            die("commiter is not an OpenJDK committer");
        }
        var username = committer.email().split("@")[0];
        var date = c.committed();
        var dateFormatter = DateTimeFormatter.ofPattern("EE MMM HH:mm:ss yyyy xx");

        System.out.println("# HG changeset patch");
        System.out.println("# User " + username);
        System.out.println("# Date " + date.toEpochSecond() + " " + (-1 * date.getOffset().getTotalSeconds()));
        System.out.println("#      " + date.format(dateFormatter));

        var message = CommitMessageParsers.v1.parse(c);
        if (!c.author().equals(committer)) {
            message.addContributor(c.author());
        }
        for (var line : CommitMessageFormatters.v0.format(message)) {
            System.out.println(line);
        }
        System.out.println("");
        var pb = new ProcessBuilder("git", "diff", "--patch",
                                                   "--binary",
                                                   "--no-color",
                                                   "--find-renames=99%",
                                                   "--find-copies=99%",
                                                   "--find-copies-harder",
                                                   repo.get().range(c.hash()));
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }
}
