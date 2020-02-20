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

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.logging.Level;

public class GitJCheck {
    private static final Pattern urlPattern = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

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
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("")
                  .fullname("local")
                  .helptext("Run jcheck in \"local\" mode")
                  .optional(),
            Switch.shortcut("")
                  .fullname("pull-request")
                  .helptext("Run jcheck in \"pull request\" mode")
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
        if (repo.isEmpty()) {
            return 1;
        }

        var isMercurial = arguments.contains("mercurial");
        var defaultRange = isMercurial ? "tip" : "HEAD^..HEAD";
        var range = arguments.get("rev").orString(defaultRange);
        if (!repo.isValidRevisionRange(range)) {
            System.err.println(String.format("error: %s is not a valid range of revisions,", range));
            if (isMercurial) {
                System.err.println("       see 'hg help revisions' for how to specify revisions");
            } else {
                System.err.println("       see 'man 7 gitrevisions' for how to specify revisions");
            }
            return 1;
        }

        var whitelistFile = arguments.get("whitelist").or(".jcheck/whitelist.json").via(Path::of);
        var whitelist = new HashMap<String, Set<Hash>>();
        if (Files.exists(whitelistFile)) {
            var json = JSON.parse(Files.readString(whitelistFile));
            for (var field : json.fields()) {
                var check = field.name();
                var hashes = field.value().stream().map(JSONValue::asString).map(Hash::new).collect(Collectors.toSet());
                whitelist.put(check, hashes);
            }
        }

        var blacklistFile = arguments.get("blacklist").or(".jcheck/blacklist.json").via(Path::of);
        var blacklist = new HashSet<Hash>();
        if (Files.exists(blacklistFile)) {
            var json = JSON.parse(Files.readString(blacklistFile));
            json.get("commits").stream()
                               .map(JSONValue::asString)
                               .map(Hash::new)
                               .forEach(blacklist::add);
        }

        var endpoint = arguments.get("census").orString(() -> {
            var fallback = "https://openjdk.java.net/census.xml";
            try {
                var config = repo.config("jcheck.census");
                return config.isEmpty() ? fallback : config.get(0);
            } catch (IOException e) {
                return fallback;
            }
        });
        var census = !isURL(endpoint)
                ? Census.parse(Path.of(endpoint))
                : Census.from(URI.create(endpoint));
        var isLocal = arguments.contains("local");
        if (!isLocal) {
            var lines = repo.config("jcheck.local");
            if (lines.size() == 1) {
                var value = lines.get(0).toUpperCase();
                isLocal = value.equals("TRUE") || value.equals("1") || value.equals("ON");
            }
        }
        var isPullRequest = arguments.contains("pull-request");
        if (!isPullRequest) {
            var lines = repo.config("jcheck.pull-request");
            if (lines.size() == 1) {
                var value = lines.get(0).toUpperCase();
                isLocal = value.equals("TRUE") || value.equals("1") || value.equals("ON");
            }
        }
        var visitor = new JCheckCLIVisitor(isLocal, isPullRequest);
        try (var errors = JCheck.check(repo, census, CommitMessageParsers.v1, range, whitelist, blacklist)) {
            for (var error : errors) {
                error.accept(visitor);
            }
        }
        return visitor.hasDisplayedErrors() ? 1 : 0;
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    private static boolean isURL(String s) {
        return urlPattern.matcher(s).matches();
    }
}
