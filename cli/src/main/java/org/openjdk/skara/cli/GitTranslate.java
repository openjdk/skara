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
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.util.*;
import java.util.logging.*;
import java.nio.file.*;

public class GitTranslate {
    private static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    public static void main(String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("")
                  .fullname("map")
                  .describe("FILE")
                  .helptext("File with commit info (defaults to .hgcommits)")
                  .optional(),
            Option.shortcut("")
                  .fullname("to-hg")
                  .describe("REV")
                  .helptext("Translate from git to hg")
                  .optional(),
            Option.shortcut("")
                  .fullname("from-hg")
                  .describe("REV")
                  .helptext("Translate from hg to git")
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
                  .optional()
        );

        var inputs = List.of(
            Input.position(0)
                 .describe("REV")
                 .singular()
                 .required()
        );

        var parser = new ArgumentParser("git-translate", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-translate version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            LogManager.getLogManager().reset();
            var log = Logger.getLogger("org.openjdk.skara");
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            log.setLevel(level);

            ConsoleHandler handler = new ConsoleHandler();
            handler.setFormatter(new MinimalFormatter());
            handler.setLevel(level);
            log.addHandler(handler);
        }

        var cwd = Path.of("").toAbsolutePath();
        var repo = ReadOnlyRepository.get(cwd);
        if (repo.isEmpty()) {
            exit("error: no git repository found at " + cwd.toString());
        }


        var hgCommits = repo.get().root().resolve(".hgcommits");
        var map = arguments.contains("map") ?
            arguments.get("map").via(Path::of) : hgCommits;

        if (!Files.exists(map)) {
            exit("error: could not find file with commit info");
        }

        var ref = arguments.at(0).asString();
        if (ref == null) {
            exit("error: no revision given");
        }

        var mapping = new HashMap<String, String>();
        if (arguments.contains("to-hg")) {
            var rev = repo.get().resolve(ref);
            if (rev.isEmpty()) {
                exit("error: could not resolve " + ref);
            }
            for (var line : Files.readAllLines(map)) {
                var parts = line.split(" ");
                mapping.put(parts[0], parts[1]);
            }
            var hash = rev.get().hex();
            if (mapping.containsKey(hash)) {
                System.out.println(mapping.get(hash));
            } else {
                exit("error: no mapping to hg from git commit " + hash);
            }
        } else if (arguments.contains("from-hg")) {
            for (var line : Files.readAllLines(map)) {
                var parts = line.split(" ");
                mapping.put(parts[1], parts[0]);
            }
            if (mapping.containsKey(ref)) {
                System.out.println(mapping.get(ref));
            } else {
                exit("error: no mapping to git from hg commit " + ref);
            }
        } else {
            exit("error: either --to-hg or --from-hg must be set");
        }
    }
}
