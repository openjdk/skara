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

import org.openjdk.skara.args.*;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.time.format.*;

public class GitCommitComments {
    private static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    private static <T> Supplier<T> die(String fmt, Object... args) {
        return () -> {
            exit(fmt, args);
            return null;
        };
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // do nothing
        }
    }

    private static String getOption(String name, Arguments arguments) {
        if (arguments.contains(name)) {
            return arguments.get(name).asString();
        }

        return gitConfig("cc." + name);
    }

    private static boolean getSwitch(String name, String subsection, Arguments arguments) {
        if (arguments.contains(name)) {
            return true;
        }

        if (subsection != null && !subsection.isEmpty()) {
            var subsectionSpecific = gitConfig("fork." + subsection + "." + name);
            if (subsectionSpecific != null) {
                return subsectionSpecific.toLowerCase().equals("true");
            }
        }

        var sectionSpecific = gitConfig("cc." + name);
        return sectionSpecific != null && sectionSpecific.toLowerCase().equals("true");
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

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Username on host")
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
                 .describe("URI")
                 .singular()
                 .required()
        );

        var parser = new ArgumentParser("git-cc", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-cc version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        HttpProxy.setup();

        URI uri = null;
        if (arguments.at(0).isPresent()) {
            var arg = arguments.at(0).asString();
            var argURI = URI.create(arg);
            uri = argURI.getScheme() == null ?
                URI.create("https://" + argURI.getHost() + argURI.getPath()) :
                argURI;
        } else {
            exit("error: must supply URI");
        }

        if (uri == null) {
            exit("error: not a valid URI: " + uri);
        }

        var webURI = Remote.toWebURI(uri.toString());
        var token = System.getenv("GIT_TOKEN");
        var username = getOption("username", arguments);
        var credentials = GitCredentials.fill(webURI.getHost(), webURI.getPath(), username, token, webURI.getScheme());

        if (credentials.password() == null) {
            exit("error: no personal access token found, use git-credentials or the environment variable GIT_TOKEN");
        }
        if (credentials.username() == null) {
            exit("error: no username for " + webURI.getHost() + " found, use git-credentials or the flag --username");
        }

        var host = Forge.from(webURI, new Credential(credentials.username(), credentials.password()));
        if (host.isEmpty()) {
            exit("error: could not connect to host " + webURI.getHost());
        }

        var repositoryPath = webURI.getPath().substring(1);

        if (repositoryPath.endsWith("/")) {
            repositoryPath =
                    repositoryPath.substring(0, repositoryPath.length() - 1);
        }

        var hostedRepo = host.get().repository(repositoryPath).orElseThrow(() ->
            new IOException("Could not find repository at " + webURI.toString())
        );

        var commitComments = hostedRepo.recentCommitComments();
        for (var comment : commitComments) {
            System.out.println("Hash: " + comment.commit().hex());
            System.out.println("Author: " + comment.author().username());
            System.out.println("Date: " + comment.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000")));
            System.out.println("");
            System.out.println(comment.body());
        }
    }
}
