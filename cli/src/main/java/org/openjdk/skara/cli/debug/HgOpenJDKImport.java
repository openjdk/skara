/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.cli.debug;

import org.openjdk.skara.args.*;
import org.openjdk.skara.cli.Logging;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.GitToHgConverter;
import org.openjdk.skara.vcs.openjdk.convert.Mark;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;

public class HgOpenJDKImport {
    static final List<Flag> flags = List.of(
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

    static final List<Input> inputs = List.of(
            Input.position(0)
                 .describe("REPO")
                 .singular()
                 .required());

    static class ErrorException extends RuntimeException {
        ErrorException(String s) {
            super(s);
        }
    }

    private static Supplier<ErrorException> error(String fmt, Object... args) {
        return () -> new ErrorException(String.format(fmt, args));
    }

    public static void main(String[] args) throws IOException {
        var parser = new ArgumentParser("git skara debug git-import", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git skara debug git-import version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        try {
            var cwd = Path.of("").toAbsolutePath();
            var hgRepo = Repository.get(cwd)
                                   .orElseThrow(error("%s is not a hg repository", cwd));

            var gitDir = arguments.at(0).via(Path::of);
            var gitRepo = ReadOnlyRepository.get(gitDir)
                                            .orElseThrow(error("%s is not a git repository", gitDir));

            var converter = new GitToHgConverter(new Branch("master"));
            try {
                var shamap = hgRepo.root().resolve(".hg").resolve("shamap");
                var marks = new ArrayList<Mark>();
                if (Files.exists(shamap)) {
                    var lines = Files.readAllLines(shamap);
                    for (var i = 0; i < lines.size(); i++) {
                        var line = lines.get(i);
                        var parts = line.split(" ");
                        var key = i + 1;
                        if (parts.length == 2) {
                            marks.add(new Mark(key, new Hash(parts[1]), new Hash(parts[0])));
                        } else if (parts.length == 3) {
                            marks.add(new Mark(key, new Hash(parts[1]), new Hash(parts[0]), new Hash(parts[2])));
                        } else {
                            throw new IllegalStateException("Unexpected line at row " + i + ": " + line);
                        }
                    }
                }
                converter.convert(gitRepo, hgRepo, marks);
            } finally {
                var marks = converter.marks();
                if (!marks.isEmpty()) {
                    var shamap = hgRepo.root().resolve(".hg").resolve("shamap");
                    try (var writer = Files.newBufferedWriter(shamap)) {
                        for (var mark : marks) {
                            writer.write(mark.git().hex());
                            writer.write(" ");
                            writer.write(mark.hg().hex());
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (ErrorException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
