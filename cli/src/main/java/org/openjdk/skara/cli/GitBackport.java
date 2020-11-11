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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.version.Version;
import org.openjdk.skara.proxy.HttpProxy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GitBackport {
    private static String getOption(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        var lines = repo.config("backport." + name);
        return lines.size() == 1 ? lines.get(0) : null;
    }

    static final List<Flag> flags = List.of(
        Option.shortcut("u")
              .fullname("username")
              .describe("NAME")
              .helptext("Username on host")
              .optional(),
        Option.shortcut("")
              .fullname("from")
              .describe("REPO")
              .helptext("Repository to backport from")
              .optional(),
        Option.shortcut("")
              .fullname("to")
              .describe("REPO")
              .helptext("Repository to backport to")
              .optional(),
        Option.shortcut("")
              .fullname("branch")
              .describe("NAME")
              .helptext("Name of branch to backport to (default to 'master')")
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

    static final List<Input> inputs = List.of(
        Input.position(0)
             .describe("HASH")
             .singular()
             .required()
    );

    public static void main(String[] args) throws IOException, InterruptedException {
        var parser = new ArgumentParser("git-backport", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-backport version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        HttpProxy.setup();

        var cwd = Paths.get("").toAbsolutePath();
        var repository = Repository.get(cwd);
        if (repository.isEmpty()) {
            System.err.println("error: no repository found at " + cwd.toString());
            System.exit(1);
        }
        var repo = repository.get();

        var from = getOption("from", arguments, repo);
        var to = getOption("to", arguments, repo);

        if (from != null && to != null) {
            System.err.println("error: cannot specify both --from and --to");
            System.exit(1);
        }

        if (from == null && to == null) {
            System.err.println("error: must use either --from or --to");
            System.exit(1);
        }

        var hash = new Hash(arguments.at(0).asString());
        var resolved = repo.resolve(hash.hex());
        if (resolved.isPresent()) {
            hash = resolved.get();
        }

        var origin = Remote.toWebURI(Remote.toURI(repo.pullPath("origin"), true).toString());
        var username = getOption("username", arguments, repo);
        var token = System.getenv("GIT_TOKEN");
        var credentials = GitCredentials.fill(origin.getHost(), origin.getPath(), username, token, origin.getScheme());
        var forgeURI = URI.create(origin.getScheme() + "://" + origin.getHost());
        var forge = Forge.from(forgeURI, new Credential(credentials.username(), credentials.password()));
        if (forge.isEmpty()) {
            System.err.println("error: could not find forge for " + forgeURI.getHost());
            System.exit(1);
        }

        var branch = getOption("branch", arguments, repo);

        HostedRepository hostedRepo = null;
        Comment comment = null;
        if (from != null) {
            var originName = origin.getPath().substring(1);
            var originRepo = forge.get().repository(originName);
            if (!originRepo.isPresent()) {
                System.err.println("error: repository named " + originName + " not present on " + forge.get().name());
                System.exit(1);
            }
            var upstreamRepo = originRepo.get().parent().isPresent() ?
                originRepo.get().parent().get() : originRepo.get();
            var upstreamGroup = upstreamRepo.name().split("/")[0];
            var repoName = from.startsWith("http") ? URI.create(from).getPath().substring(1) : from;
            if (!repoName.contains("/")) {
                repoName = upstreamGroup + "/" + repoName;
            }
            var maybeHostedRepo = forge.get().repository(repoName);
            if (!maybeHostedRepo.isPresent()) {
                System.err.println("error: repository named " + repoName + " not present on " + forge.get().name());
                System.exit(1);
            }
            hostedRepo = maybeHostedRepo.get();
            var targetName = upstreamRepo.name().split("/")[1];
            var message = "/backport " + targetName;
            if (branch != null) {
                message += " " + branch;
            }
            comment = hostedRepo.addCommitComment(hash, message);
        } else if (to != null ) {
            var repoName = origin.getPath().substring(1);
            var maybeHostedRepo = forge.get().repository(repoName);
            if (!maybeHostedRepo.isPresent()) {
                System.err.println("error: repository named " + repoName + " not present on " + forge.get().name());
                System.exit(1);
            }
            hostedRepo = maybeHostedRepo.get();
            var parent = hostedRepo.parent();
            if (parent.isPresent()) {
                hostedRepo = parent.get();
            }
            var targetName = to.startsWith("http") ? URI.create(to).getPath().substring(1) : to;
            var message = "/backport " + targetName;
            if (branch != null) {
                message += " " + branch;
            }
            comment = hostedRepo.addCommitComment(hash, message);
        } else {
            throw new IllegalStateException("Should not be here, both 'from' and 'to' are null");
        }

        var seenReply = false;
        var expected = "<!-- Jmerge command reply message (" + comment.id() + ") -->";
        for (var i = 0; i < 90; i++) {
            var comments = hostedRepo.commitComments(hash);
            for (var c : comments) {
                var lines = c.body().split("\n");
                if (lines.length > 0 && lines[0].equals(expected)) {
                    for (var j = 1; j < lines.length; j++) {
                        System.out.println(lines[j]);
                    }
                    System.exit(0);
                }
            }
            Thread.sleep(2000);
        }

        System.err.println("error: timed out waiting for response to /backport command");
        System.exit(1);
    }
}
