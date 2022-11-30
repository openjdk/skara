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
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Level;

import static org.openjdk.skara.jcheck.JCheck.STAGED_REV;
import static org.openjdk.skara.jcheck.JCheck.WORKING_TREE_REV;

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

    public static int run(Repository repo, String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("r")
                  .fullname("rev")
                  .describe("REV")
                  .helptext("Check the specified revision or range (default: HEAD)")
                  .optional(),
            Option.shortcut("")
                  .fullname("census")
                  .describe("FILE")
                  .helptext("Use the specified census (default: https://openjdk.org/census.xml)")
                  .optional(),
            Option.shortcut("")
                  .fullname("ignore")
                  .describe("CHECKS")
                  .helptext("Ignore errors from checks with the given name")
                  .optional(),
            Option.shortcut("")
                  .fullname("conf-rev")
                  .describe("REV")
                  .helptext("Use .jcheck/conf in specified rev")
                  .optional(),
            Option.shortcut("")
                  .fullname("conf-file")
                  .describe("FILE")
                  .helptext("Use this file as jcheck configuration instead of .jcheck/conf")
                  .optional(),
            Switch.shortcut("")
                  .fullname("setup-pre-push-hook")
                  .helptext("Set up a pre-push hook that runs jcheck on commits to be pushed")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
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
                  .optional(),
            Switch.shortcut("")
                  .fullname("conf-staged")
                  .helptext("Use staged .jcheck/conf")
                  .optional(),
            Switch.shortcut("")
                  .fullname("conf-working-tree")
                  .helptext("Use .jcheck/conf in current working tree")
                  .optional(),
            Switch.shortcut("")
                  .fullname("staged")
                  .helptext("Check staged changes as if they were committed")
                  .optional(),
            Switch.shortcut("")
                  .fullname("working-tree")
                  .helptext("Run jcheck includes changes in working tree and by default " +
                          "jcheck will use .jcheck/conf in working tree")
                  .optional()
        );

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

        HttpProxy.setup();

        var isMercurial = getSwitch("mercurial", arguments);
        var setupPrePushHook = getSwitch("setup-pre-push-hook", arguments);
        if (!isMercurial && setupPrePushHook) {
            var hookFile = repo.root().resolve(".git").resolve("hooks").resolve("pre-push");
            Files.createDirectories(hookFile.getParent());
            var lines = List.of(
                "#!/usr/bin/sh",
                "JCHECK_IS_PRE_PUSH_HOOK=1 git jcheck"
            );
            Files.write(hookFile, lines);
            if (hookFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = PosixFilePermissions.fromString("rwxr-xr-x");
                Files.setPosixFilePermissions(hookFile, permissions);
            }
            return 0;
        }

        var isPrePush = System.getenv().containsKey("JCHECK_IS_PRE_PUSH_HOOK");
        var ranges = new ArrayList<String>();
        var targetBranches = new HashSet<String>();
        if (!isMercurial && isPrePush) {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            var lines = reader.lines().collect(Collectors.toList());
            for (var line : lines) {
                var parts = line.split(" ");
                var localHash = new Hash(parts[1]);
                var remoteRef = parts[2];
                var remoteHash = new Hash(parts[3]);

                if (localHash.equals(Hash.zero())) {
                    continue;
                }

                if (remoteHash.equals(Hash.zero())) {
                    ranges.add("origin.." + localHash.hex());
                } else {
                    targetBranches.add(Path.of(remoteRef).getFileName().toString());
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

        var endpoint = getOption("census", arguments);
        var census = endpoint == null ? null :
            endpoint.startsWith("http://") || endpoint.startsWith("https://") ?
                Census.from(URI.create(endpoint)) : Census.parse(Path.of(endpoint));

        var ignore = new HashSet<String>();
        var ignoreOption = getOption("ignore", arguments);
        if (ignoreOption != null) {
            for (var check : ignoreOption.split(",")) {
                ignore.add(check.trim());
            }
        }

        var staged = arguments.contains("staged");
        var working_tree = arguments.contains("working-tree");
        // This two flags are mutually exclusive
        if (staged && working_tree) {
            System.err.println(String.format("error: you can only choose one from staged or working-tree"));
            return 1;
        }

        var confRev = arguments.contains("conf-rev");
        var confStaged = arguments.contains("conf-staged");
        var confWorkingTree = arguments.contains("conf-working-tree");
        var confFile = arguments.contains("conf-file");

        int confFlagCount = 0;
        if (confRev) {
            confFlagCount++;
        }
        if (confStaged) {
            confFlagCount++;
        }
        if (confWorkingTree) {
            confFlagCount++;
        }
        if (confFile) {
            confFlagCount++;
        }
        // This four flags are mutually exclusive
        if (confFlagCount > 1) {
            System.err.println(String.format("error: you can only choose one from using jcheck " +
                    "configuration in work space or in a specified commit"));
            return 1;
        }
        JCheckConfiguration overridingConfig = null;
        // Using jcheck configuration in a specified rev
        if (confRev) {
            var rev = arguments.get("conf-rev").asString();
            var confCommitHash = repo.wholeHash(rev);
            if (confCommitHash.isEmpty()) {
                System.err.println(String.format("error: rev %s is invalid!", rev));
                return 1;
            }
            try {
                overridingConfig = JCheck.parseConfiguration(repo, confCommitHash.get(), List.of()).get();
            } catch (IllegalArgumentException e) {
                System.err.println(String.format("error: Invalid jcheck configuration: %s", e.getMessage()));
                return 1;
            }
        }
        // Using staged jcheck configuration
        else if (confStaged || (staged && !confFile && !confWorkingTree)) {
            var content = repo.stagedFile(Path.of(".jcheck/conf"));
            if (content.isEmpty()) {
                System.err.println(String.format("error: .jcheck/conf doesn't exist!"));
                return 1;
            }
            try {
                overridingConfig = JCheck.parseConfiguration(content.get(), List.of()).get();
            } catch (IllegalArgumentException e) {
                System.err.println(String.format("error: Invalid jcheck configuration: %s", e.getMessage()));
                return 1;
            }
        }
        // Using pointed file as jcheck configuration or jcheck configuration in current working tree
        else if (confFile || confWorkingTree || working_tree) {
            var fileName = confFile ? arguments.get("conf-file").asString() : ".jcheck/conf";
            try {
                var content = Files.readAllBytes(Path.of(fileName));
                var lines = new String(content, StandardCharsets.UTF_8).lines().toList();
                overridingConfig = JCheck.parseConfiguration(lines, List.of()).get();
            } catch (NoSuchFileException e) {
                System.err.println(String.format("error: File %s doesn't exist!", fileName));
                return 1;
            } catch (IllegalArgumentException e) {
                System.err.println(String.format("error: Invalid jcheck configuration: %s,", e.getMessage()));
                return 1;
            }
        }

        if (staged) {
            ranges.clear();
            ranges.add(STAGED_REV);
        }
        if (working_tree) {
            ranges.clear();
            ranges.add(WORKING_TREE_REV);
        }

        var isLax = getSwitch("lax", arguments);
        var visitor = new JCheckCLIVisitor(ignore, isMercurial, isLax);
        var commitMessageParser = isMercurial ? CommitMessageParsers.v0 : CommitMessageParsers.v1;
        for (var range : ranges) {
            try (var errors = JCheck.check(repo, census, commitMessageParser, range, overridingConfig)) {
                for (var error : errors) {
                    error.accept(visitor);
                }
            }
        }
        return visitor.hasDisplayedErrors() ? 1 : 0;
    }

    public static void main(String[] args) throws IOException {
        var cwd = Paths.get("").toAbsolutePath();
        var repository = Repository.get(cwd);
        if (!repository.isPresent()) {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            System.exit(1);
        }

        System.exit(run(repository.get(), args));
    }
}
