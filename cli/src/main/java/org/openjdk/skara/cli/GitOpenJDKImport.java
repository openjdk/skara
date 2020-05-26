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
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.*;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;

import static java.util.stream.Collectors.toList;

public class GitOpenJDKImport {
    private static void die(Exception e) {
        System.err.println(e.getMessage());
        System.exit(1);
    }

    private static Supplier<NoSuchElementException> error(String fmt, Object... args) {
        return () -> new NoSuchElementException(String.format(fmt, args));
    }

    public static void main(String[] args) {
        var flags = List.of(
            Option.shortcut("")
                  .fullname("replacements")
                  .describe("FILE")
                  .helptext("JSON file with replacements")
                  .optional(),
            Option.shortcut("")
                  .fullname("corrections")
                  .describe("FILE")
                  .helptext("JSON file with corrections")
                  .optional(),
            Option.shortcut("")
                  .fullname("authors")
                  .describe("FILE")
                  .helptext("Comma separated list of JSON files with author info")
                  .optional(),
            Option.shortcut("")
                  .fullname("contributors")
                  .describe("FILE")
                  .helptext("JSON file with contributor info")
                  .optional(),
            Option.shortcut("")
                  .fullname("lowercase")
                  .describe("FILE")
                  .helptext("JSON file with commits allowed to start with lowercase")
                  .optional(),
            Option.shortcut("")
                  .fullname("punctuated")
                  .describe("FILE")
                  .helptext("JSON file with commits allowed to end with '.'")
                  .optional(),
            Option.shortcut("")
                  .fullname("sponsors")
                  .describe("FILE")
                  .helptext("JSON file with sponsor info")
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
                 .describe("REPO")
                 .singular()
                 .required());

        var parser = new ArgumentParser("git-openjdk-import", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-openjdk-import version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        try {
            var cwd = Path.of("").toAbsolutePath();
            var gitRepo = Repository.get(cwd)
                                    .orElseThrow(error("%s is not a git repository", cwd));

            var hgDir = arguments.at(0).via(Path::of);
            var hgRepo = Repository.get(hgDir)
                                   .orElseThrow(error("%s is not a hg repository", hgDir));

            var replacements = new HashMap<Hash, List<String>>();
            if (arguments.contains("replacements")) {
                var f = arguments.get("replacements").via(Path::of);
                var json = JSON.parse(Files.readString(f));
                for (var field : json.fields()) {
                    var hash = new Hash(field.name());
                    var message = field.value().stream().map(e -> e.asString()).collect(toList());
                    replacements.put(hash, message);
                }
            }

            var corrections = new HashMap<Hash, Map<String, String>>();
            if (arguments.contains("corrections")) {
                var f = arguments.get("corrections").via(Path::of);
                var json = JSON.parse(Files.readString(f));
                for (var field : json.fields()) {
                    var hash = new Hash(field.name());
                    corrections.put(hash, new HashMap<String, String>());

                    for (var entry : field.value().fields()) {
                        var from = entry.name();
                        var to = entry.value().asString();
                        corrections.get(hash).put(from, to);
                    }
                }
            }

            var lowercase = new HashSet<Hash>();
            if (arguments.contains("lowercase")) {
                var f = arguments.get("lowercase").via(Path::of);
                var json = JSON.parse(Files.readString(f));
                for (var hash : json.get("commits").asArray()) {
                    lowercase.add(new Hash(hash.asString()));
                }
            }

            var punctuated = new HashSet<Hash>();
            if (arguments.contains("punctuated")) {
                var f = arguments.get("punctuated").via(Path::of);
                var json = JSON.parse(Files.readString(f));
                for (var hash : json.get("commits").asArray()) {
                    punctuated.add(new Hash(hash.asString()));
                }
            }

            var authors = new HashMap<String, String>();
            if (arguments.contains("authors")) {
                var files = Arrays.stream(arguments.get("authors").asString().split(","))
                                  .map(Path::of)
                                  .collect(toList());
                for (var f : files) {
                    var json = JSON.parse(Files.readString(f));
                    for (var field : json.fields()) {
                        authors.put(field.name(), field.value().asString());
                    }
                }
            }

            var contributors = new HashMap<String, String>();
            if (arguments.contains("contributors")) {
                var f = arguments.get("contributors").via(Path::of);
                var json = JSON.parse(Files.readString(f));
                for (var field : json.fields()) {
                    contributors.put(field.name(), field.value().asString());
                }
            }

            var sponsors = new HashMap<String, List<String>>();
            if (arguments.contains("sponsors")) {
                var f = arguments.get("sponsors").via(Path::of);
                var json = JSON.parse(Files.readString(f));
                for (var field : json.fields()) {
                    var name = field.name();
                    var emails = field.value().stream().map(e -> e.asString()).collect(toList());
                    sponsors.put(name, emails);
                }
            }

            if (arguments.contains("verbose") || arguments.contains("debug")) {
                var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
                Logging.setup(level);
            }

            var converter = new HgToGitConverter(replacements, corrections, lowercase, punctuated, authors, contributors, sponsors);
            var hgCommits = gitRepo.root().resolve(".hgcommits");
            List<Mark> marks;
            if (Files.exists(hgCommits)) {
                var lines = Files.readAllLines(hgCommits);
                marks = new ArrayList<>();
                for (int i = 0; i < lines.size(); ++i) {
                    var markHashes = lines.get(i).split(" ");
                    var mark = new Mark(i + 1, new Hash(markHashes[0]), new Hash(markHashes[1]));
                    marks.add(mark);
                }
                marks = converter.pull(hgRepo, URI.create(hgRepo.pullPath("default")), gitRepo, marks);
            } else {
                marks = converter.convert(hgRepo, gitRepo);
            }

            try (var writer = Files.newBufferedWriter(hgCommits)) {
                for (var mark : marks) {
                    writer.write(mark.hg().hex());
                    writer.write(" ");
                    writer.write(mark.git().hex());
                    writer.newLine();
                }
            }
        } catch (NoSuchElementException | IOException e) {
            die(e);
        }
    }
}
