/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.args.Arguments;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

public class ForgeUtils {
    private static void exit(String fmt, Object... args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    private static void gitConfig(String key, String value) {
        try {
            var pb = new ProcessBuilder("git", "config", key, value);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start().waitFor();
        } catch (InterruptedException e) {
            // do nothing
        } catch (IOException e) {
            // do nothing
        }
    }

    private static String gitConfig(String key) {
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

    public static String getOption(String name, String command, String subsection, Arguments arguments) {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        if (subsection != null && !subsection.isEmpty()) {
            var subsectionSpecific = gitConfig(command + "." + subsection + "." + name);
            if (subsectionSpecific != null) {
                return subsectionSpecific;
            }
        }

        return gitConfig(command + "." + name);
    }

    public static boolean getSwitch(String name, String command, String subsection, Arguments arguments) {
        if (arguments.contains(name)) {
            return true;
        }

        if (subsection != null && !subsection.isEmpty()) {
            var subsectionSpecific = gitConfig(command + "." + subsection + "." + name);
            if (subsectionSpecific != null) {
                return subsectionSpecific.toLowerCase().equals("true");
            }
        }

        var sectionSpecific = gitConfig(command + "." + name);
        return sectionSpecific != null && sectionSpecific.toLowerCase().equals("true");
    }

    static Repository getRepo() throws IOException {
        var cwd = Path.of("").toAbsolutePath();
        return Repository.get(cwd).orElseThrow(() -> new IOException("no git repository found at " + cwd.toString()));
    }

    public static String getRemote(ReadOnlyRepository repo, String command, Arguments arguments) throws IOException {
        var remote = getOption("remote", command, null, arguments);
        return remote == null ? "origin" : remote;
    }

    public static URI getURI(ReadOnlyRepository repo, String command, Arguments arguments) throws IOException {
        var remotePullPath = repo.pullPath(getRemote(repo, command, arguments));
        return Remote.toWebURI(remotePullPath);
    }

    public static Optional<Forge> from(URI uri) {
        return from(uri, null);
    }

    public static Optional<Forge> from(URI uri, Credential credentials) {
        var name = gitConfig("forge.name");
        if (name != null) {
            var forge = credentials == null ? Forge.from(name, uri) : Forge.from(name, uri, credentials);
            return Optional.of(forge);
        }
        var forge = credentials == null ? Forge.from(uri) : Forge.from(uri, credentials);
        if (forge.isPresent()) {
            gitConfig("forge.name", forge.get().name().toLowerCase());
        }
        return forge;
    }

    public static Forge getForge(URI uri, ReadOnlyRepository repo, String command, Arguments arguments) throws IOException {
        var username = getOption("username", null, null, arguments);
        var token = System.getenv("GIT_TOKEN");
        var shouldUseToken = !getSwitch("no-token", command, null, arguments);
        var credentials = !shouldUseToken ?
                null :
                GitCredentials.fill(uri.getHost(), uri.getPath(), username, token, uri.getScheme());
        var forgeURI = URI.create(uri.getScheme() + "://" + uri.getHost());
        var forge = credentials == null ?
                from(forgeURI) :
                from(forgeURI, new Credential(credentials.username(), credentials.password()));
        if (forge.isEmpty()) {
            if (!shouldUseToken) {
                if (arguments.contains("verbose")) {
                    System.err.println("");
                }
                System.err.println("warning: using this command with --no-token may result in rate limiting from " + forgeURI);
                if (!arguments.contains("verbose")) {
                    System.err.println("         Re-run with --verbose to see if you are being rate limited");
                    System.err.println("");
                }
            }
            exit("error: failed to connect to host: " + forgeURI);
        }
        if (credentials != null) {
            GitCredentials.approve(credentials);
        }
        return forge.get();
    }

    public static String projectName(URI uri) {
        var name = uri.getPath().toString().substring(1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        return name;
    }

    public static HostedRepository getHostedRepositoryFor(URI uri, ReadOnlyRepository repo, Forge host) throws IOException {
        HostedRepository targetRepo = null;

        try {
            var upstream = Remote.toWebURI(repo.pullPath("upstream"));
            targetRepo = host.repository(projectName(upstream)).orElse(null);
        } catch (IOException e) {
            // do nothing
        }

        if (targetRepo == null) {
            var remoteRepo = host.repository(projectName(uri)).orElseThrow(() ->
                    new IOException("Could not find repository at: " + uri.toString())
            );
            var parentRepo = remoteRepo.parent();
            targetRepo = parentRepo.isPresent() ? parentRepo.get() : remoteRepo;
        }

        return targetRepo;
    }
}
