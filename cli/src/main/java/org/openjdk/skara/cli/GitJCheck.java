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
package org.openjdk.skara.cli;

import org.openjdk.skara.args.*;
import org.openjdk.skara.census.Census;
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.logging.Level;

public class GitJCheck {
    static String gitConfig(String key) {
        try {
            var pb = new ProcessBuilder("git", "config", key);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            var p = pb.start();

            var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var res = p.waitFor();
            if (res != 0) {
                return null;
            }

            return output == null ? null : output.replace("\n", "");
        } catch (InterruptedException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    static String getOption(String name, Arguments arguments) {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        return gitConfig("jcheck." + name);
    }

    static boolean getSwitch(String name, Arguments arguments) {
        if (arguments.contains(name)) {
            return true;
        }
        var value = gitConfig("jcheck." + name);
        return value != null && value.toLowerCase().equals("true");
    }

    public static int run(String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("r")
                  .fullname("rev")
                  .describe("REV")
                  .helptext("Check the specified revision or range (default: HEAD)")
                  .optional(),
            Option.shortcut("")
                  .fullname("whitelist")
                  .describe("FILE")
                  .helptext("Use the specified whitelist (default: .jcheck/whitelist.json)")
                  .optional(),
            Option.shortcut("")
                  .fullname("blacklist")
                  .describe("FILE")
                  .helptext("Use the specified blacklist (default: .jcheck/blacklist.json)")
                  .optional(),
            Option.shortcut("")
                  .fullname("census")
                  .describe("FILE")
                  .helptext("Use the specified census (default: https://openjdk.java.net/census.xml)")
                  .optional(),
            Option.shortcut("")
                  .fullname("ignore")
                  .describe("CHECKS")
                  .helptext("Ignore errors from checks with the given name")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("")
                  .fullname("pre-push")
                  .helptext("Execute as a pre-push hook")
                  .optional(),
            Switch.shortcut("")
                  .fullname("setup-pre-push-hook")
                  .helptext("Set up a pre-push hook that runs jcheck")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("lax")
                  .helptext("Check comments, tags and whitespace laxly")
                  .optional(),
            Switch.shortcut("s")
                  .fullname("strict")
                  .helptext("Check everything")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

        var parser = new ArgumentParser("git jcheck", flags, List.of());
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-jcheck version: " + Version.fromManifest().orElse("unknown"));
            return 0;
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level, "jcheck");
        }

        var cwd = Paths.get("").toAbsolutePath();
        var repository = ReadOnlyRepository.get(cwd);
        if (!repository.isPresent()) {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            return 1;
        }
        var repo = repository.get();

        var setupPrePushHook = getSwitch("setup-pre-push-hook", arguments);
        if (setupPrePushHook) {
            var hookFile = repo.root().resolve(".git").resolve("hooks").resolve("pre-push");
            Files.createDirectories(hookFile.getParent());
            var lines = List.of(
                "#!/usr/bin/sh",
                "git jcheck --pre-push"
            );
            Files.write(hookFile, lines);
            if (hookFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = PosixFilePermissions.fromString("rwxr-xr-x");
                Files.setPosixFilePermissions(hookFile, permissions);
            }
            return 0;
        }

        var isMercurial = getSwitch("mercurial", arguments);
        var isPrePush = getSwitch("pre-push", arguments);
        var ranges = new ArrayList<String>();
        if (isPrePush) {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            var lines = reader.lines().collect(Collectors.toList());
            for (var line : lines) {
                var parts = line.split(" ");
                var localHash = new Hash(parts[1]);
                var remoteHash = new Hash(parts[3]);

                if (localHash.equals(Hash.zero())) {
                    continue;
                }

                if (remoteHash.equals(Hash.zero())) {
                    ranges.add("origin.." + localHash.hex());
                } else {
                    ranges.add(remoteHash.hex() + ".." + localHash.hex());
                }
            }
        } else {
            var defaultRange = isMercurial ? "tip" : "HEAD^..HEAD";
            ranges.add(arguments.get("rev").orString(defaultRange));
        }

        for (var range : ranges) {
            if (!repo.isValidRevisionRange(range)) {
                System.err.println(String.format("error: %s is not a valid range of revisions,", range));
                if (isMercurial) {
                    System.err.println("       see 'hg help revisions' for how to specify revisions");
                } else {
                    System.err.println("       see 'man 7 gitrevisions' for how to specify revisions");
                }
                return 1;
            }
        }

        var whitelistOption = getOption("whitelist", arguments);
        if (whitelistOption == null) {
            whitelistOption = ".jcheck/whitelist.json";
        }
        var whitelistFile = Path.of(whitelistOption);
        var whitelist = new HashMap<String, Set<Hash>>();
        if (Files.exists(whitelistFile)) {
            var json = JSON.parse(Files.readString(whitelistFile));
            for (var field : json.fields()) {
                var check = field.name();
                var hashes = field.value().stream().map(JSONValue::asString).map(Hash::new).collect(Collectors.toSet());
                whitelist.put(check, hashes);
            }
        }

        var blacklistOption = getOption("blacklist", arguments);
        if (blacklistOption == null) {
            blacklistOption = ".jcheck/blacklist.json";
        }
        var blacklistFile = Path.of(blacklistOption);
        var blacklist = new HashSet<Hash>();
        if (Files.exists(blacklistFile)) {
            var json = JSON.parse(Files.readString(blacklistFile));
            json.get("commits").stream()
                               .map(JSONValue::asString)
                               .map(Hash::new)
                               .forEach(blacklist::add);
        }

        var endpoint = getOption("census", arguments);
        if (endpoint == null) {
            endpoint = "https://openjdk.java.net/census.xml";
        }
        var census = endpoint.startsWith("http://") || endpoint.startsWith("https://") ?
            Census.from(URI.create(endpoint)) :
            Census.parse(Path.of(endpoint));

        var ignore = new HashSet<String>();
        var ignoreOption = getOption("ignore", arguments);
        if (ignoreOption != null) {
            for (var check : ignoreOption.split(",")) {
                ignore.add(check.trim());
            }
        }

        var visitor = new JCheckCLIVisitor(ignore);
        for (var range : ranges) {
            try (var errors = JCheck.check(repo, census, CommitMessageParsers.v1, range, whitelist, blacklist)) {
                for (var error : errors) {
                    error.accept(visitor);
                }
            }
        }
        return visitor.hasDisplayedErrors() ? 1 : 0;
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }
}
