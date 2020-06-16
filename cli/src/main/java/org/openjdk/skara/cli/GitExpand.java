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
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URI;
import java.util.regex.Pattern;
import java.util.*;
import java.util.logging.Level;

public class GitExpand {
    static final Pattern ISSUE_ID_PATTERN = Pattern.compile("([A-Za-z][A-Za-z0-9]+)?-([0-9]+)");

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

    private static Repository repo(Path p) throws IOException {
        var repo = Repository.get(p);
        if (repo.isEmpty()) {
            System.err.println("error: no repository found at " + p.toString());
            System.exit(1);
        }
        return repo.get();
    }

    private static Commit lookup(ReadOnlyRepository repo, String rev) throws IOException {
        var hash = repo.resolve(rev);
        if (hash.isEmpty()) {
            System.err.println("error: could not resolve " + rev);
            System.exit(1);
        }
        var commit = repo.lookup(hash.get());
        if (commit.isEmpty()) {
            System.err.println("error: could not find commit for hash " + hash.get());
            System.exit(1);
        }

        return commit.get();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Switch.shortcut("")
                  .fullname("issues")
                  .helptext("Expand issues in the commit message")
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
                 .optional()
        );

        var parser = new ArgumentParser("git-publish", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-expand version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        var cwd = Path.of("").toAbsolutePath();
        var repo = repo(cwd);
        var rev = arguments.at(0).orString("HEAD");
        var commit = lookup(repo, rev);
        var message = commit.message();

        var shouldExpandIssues = getSwitch("issues", arguments, repo);
        if (shouldExpandIssues) {
            var conf = JCheckConfiguration.from(repo, commit.hash());
            if (conf.isPresent()) {
                var project = conf.get().general().jbs();
                var tracker = IssueTracker.from("jira", URI.create("https://bugs.openjdk.java.net"));

                var amended = new ArrayList<String>();
                for (var line : message) {
                    var m = ISSUE_ID_PATTERN.matcher(line);
                    if (m.matches()) {
                        var id = m.group(2);
                        var issue = tracker.project(project).issue(id);
                        if (issue.isPresent()) {
                            amended.add(id + ": " + issue.get().title());
                        }
                    } else {
                        amended.add(line);
                    }
                }

                repo.amend(String.join("\n", amended));
            } else {
                System.err.println("warning: could not expand issues commit message,\n" +
                                   "         no JBS project configured in .jcheck/conf");
            }
        }
    }
}

