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
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.io.IOException;
import java.util.List;

public class GitInfo {
    private static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    private static Supplier<IOException> die(String fmt, Object... args) {
        return () -> {
            exit(fmt, args);
            return new IOException();
        };
    }

    public static void main(String[] args) throws IOException {
        var flags = List.of(
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-decoration")
                  .helptext("Do not prefix lines with any decoration")
                  .optional(),
            Switch.shortcut("")
                  .fullname("issues")
                  .helptext("Show issues")
                  .optional(),
            Switch.shortcut("")
                  .fullname("reviewers")
                  .helptext("Show reviewers")
                  .optional(),
            Switch.shortcut("")
                  .fullname("summary")
                  .helptext("Show summary (if present)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("sponsor")
                  .helptext("Show sponsor (if present)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("author")
                  .helptext("Show author")
                  .optional(),
            Switch.shortcut("")
                  .fullname("contributors")
                  .helptext("Show contributors")
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
                 .describe("REV")
                 .singular()
                 .required()
        );

        var parser = new ArgumentParser("git-info", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-info version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        var isMercurial = arguments.contains("mercurial");
        var ref = arguments.at(0).orString(isMercurial ? "tip" : "HEAD");
        var cwd = Path.of("").toAbsolutePath();
        var repo = ReadOnlyRepository.get(cwd).orElseThrow(die("error: no repository found at " + cwd.toString()));
        var hash = repo.resolve(ref).orElseThrow(die("error: " + ref + " is not a commit"));
        var commits = repo.commits(hash.hex(), 1).asList();
        if (commits.isEmpty()) {
            throw new IOException("internal error: could not list commit for " + hash.hex());
        }
        var commit = commits.get(0);
        var useDecoration = !arguments.contains("no-decoration");

        if (arguments.contains("sponsor")) {
            if (!commit.author().equals(commit.committer())) {
                var decoration = useDecoration ? "Sponsor: " : "";
                System.out.println(decoration + commit.committer().toString());
            }
        }
        if (arguments.contains("author")) {
            var decoration = useDecoration ? "Author: " : "";
            System.out.println(decoration + commit.author().toString());
        }

        var message = arguments.contains("mercurial") ?
            CommitMessageParsers.v0.parse(commit) :
            CommitMessageParsers.v1.parse(commit);
        if (arguments.contains("reviewers")) {
            var decoration = useDecoration? "Reviewer: " : "";
            for (var reviewer : message.reviewers()) {
                System.out.println(decoration + reviewer);
            }
        }
        if (arguments.contains("summary")) {
            var decoration = useDecoration? "Summary: " : "";
            for (var line : message.summaries()) {
                System.out.println(decoration + line);
            }
        }
        if (arguments.contains("contributors")) {
            var decoration = useDecoration? "Contributor: " : "";
            System.out.println(decoration + commit.committer().toString());
            for (var coAuthor : message.contributors()) {
                System.out.println(decoration + coAuthor.toString());
            }
        }
        if (arguments.contains("issues")) {
            var decoration = useDecoration? "Issue: " : "";
            var lines = repo.lines(Path.of(".jcheck/conf"), hash);

            String uri = null;
            if (lines.isPresent()) {
                var jbs = "https://bugs.openjdk.java.net/browse/";
                var conf = JCheckConfiguration.parse(lines.get());
                var project = conf.general().project().toUpperCase();
                uri = jbs + project + "-";
            }

            for (var issue : message.issues()) {
                if (uri != null) {
                    var id = issue.id();
                    System.out.println(decoration + uri + id);
                } else {
                    System.out.println(decoration + issue.toString());
                }
            }
        }
    }
}
