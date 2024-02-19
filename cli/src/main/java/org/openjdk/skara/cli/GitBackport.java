/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.version.Version;
import org.openjdk.skara.proxy.HttpProxy;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

public class GitBackport {
    private static String getOption(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        var arg = ForgeUtils.getOption(name, arguments);
        if (arg != null) {
            return arg;
        }
        var lines = repo.config("backport." + name);
        return lines.size() == 1 ? lines.get(0) : null;
    }

    private static void run(Repository repo, String... args) throws IOException {
        var pb = new ProcessBuilder(args);
        pb.inheritIO();
        pb.directory(repo.root().toFile());
        try {
            var err = pb.start().waitFor();
            if (err != 0) {
                System.exit(err);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
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
        if (from == null) {
            System.err.println("error: must specify repository to backport from using --from");
        }

        var commit = arguments.at(0).asString();

        var gitUsername = repo.config("user.name");
        if (gitUsername.size() != 1) {
            System.err.println("error: user.name not configured");
            System.exit(1);
        }

        var gitEmail = repo.config("user.email");
        if (gitEmail.size() != 1) {
            System.err.println("error: user.email not configured");
            System.exit(1);
        }

        URI fromURI = null;
        try {
            fromURI = Remote.toURI(from, false);
        } catch (IOException e) {
            var origin = Remote.toURI(repo.pullPath("origin"), false);
            var dotGit = origin.getPath().endsWith(".git") ? ".git" : "";
            if (from.contains("/")) {
                fromURI = URI.create(origin.getScheme() + "://" + origin.getHost() + "/" + from + dotGit);
            } else {
                var canonical = Remote.toWebURI(Remote.toURI(repo.pullPath("origin"), true).toString());
                var username = getOption("username", arguments, repo);
                var token = System.getenv("GIT_TOKEN");
                var credentials = GitCredentials.fill(canonical.getHost(), canonical.getPath(), username, token, canonical.getScheme());
                var forgeURI = URI.create(canonical.getScheme() + "://" + canonical.getHost());
                var forge = ForgeUtils.from(forgeURI, new Credential(credentials.username(), credentials.password()));
                if (forge.isEmpty()) {
                    System.err.println("error: could not find forge at " + forgeURI.getHost());
                    System.exit(1);
                }
                var originRemoteRepository = forge.get().repository(canonical.getPath().substring(1));
                if (originRemoteRepository.isEmpty()) {
                    System.err.println("error: could not find repository named '" + origin.getPath().substring(1) + "' on " + forge.get().hostname());
                    System.exit(1);
                }
                var upstreamRemoteRepository = originRemoteRepository.get().parent();
                if (upstreamRemoteRepository.isEmpty()) {
                    System.err.println("error: the repository named '" + originRemoteRepository.get().name() + " is not a fork of another repository");
                    System.exit(1);
                }
                var upstreamGroup = upstreamRemoteRepository.get().webUrl().getPath().substring(1).split("/")[0];
                fromURI = URI.create(origin.getScheme() + "://" +
                                     origin.getHost() + "/" +
                                     upstreamGroup + "/" +
                                     from +
                                     dotGit);
            }
        }

        System.out.println("Fetching ...");
        System.out.flush();
        var fetchHead = repo.fetch(fromURI, commit, false).orElseThrow();

        System.out.println("Cherry picking ...");
        System.out.flush();
        run(repo, "git", "cherry-pick", "--no-commit",
                                        "--keep-redundant-commits",
                                        "--strategy=recursive",
                                        "--strategy-option=patience",
                                        fetchHead.hex());

        System.out.println("Committing ...");
        System.out.flush();
        run(repo, "git", "commit", "--quiet", "--message=" + "Backport " + fetchHead.hex());

        System.out.println("Commit " + fetchHead.hex() + " successfully backported as commit " + repo.head().hex());
    }
}
