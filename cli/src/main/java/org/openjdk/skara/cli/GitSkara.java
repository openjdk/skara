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

import org.openjdk.skara.args.Main;
import org.openjdk.skara.vcs.Repository;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GitSkara {

    private static final Map<String, Main> commands = new TreeMap<>();

    private static void usage(String[] args) {
        var names = new ArrayList<String>();
        names.addAll(commands.keySet());
        var skaraCommands = Set.of("help", "version", "update");

        System.out.println("usage: git skara <" + String.join("|", names) + ">");
        System.out.println("");
        System.out.println("Additional available git commands:");
        for (var name : names) {
            if (!skaraCommands.contains(name)) {
                System.out.println("- git " + name);
            }
        }
        System.out.println("");
        System.out.println("For more information, please see the Skara wiki:");
        System.out.println("");
        System.out.println("    https://wiki.openjdk.java.net/display/skara");
        System.out.println("");
        System.exit(0);
    }

    private static void version(String[] args) {
        System.out.println("git skara version: " + Version.fromManifest().orElse("unknown"));
        System.exit(0);
    }

    private static List<String> config(String key) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("git", "config", key);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var value = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return Arrays.asList(value.split("\n"));
    }

    private static void update(String[] args) throws IOException, InterruptedException {
        var lines = config("include.path");
        var path = lines.stream().filter(l -> l.endsWith("skara.gitconfig")).map(Path::of).findFirst();
        if (path.isEmpty()) {
            System.err.println("error: could not find skara repository");
            System.exit(1);
        }

        var parent = path.get().getParent();
        var repo = Repository.get(parent);
        if (repo.isEmpty()) {
            System.err.println("error: could not find skara repository");
            System.exit(1);
        }

        var head = repo.get().head();
        System.out.print("Checking for updates ...");
        repo.get().pull();
        var newHead = repo.get().head();

        if (!head.equals(newHead)) {
            System.out.println("updates downloaded");
            System.out.println("Rebuilding ...");
            var cmd = new ArrayList<String>();
            if (System.getProperty("os.name").toLowerCase().startsWith("win")) {
                cmd.add("gradlew.bat");
            } else {
                cmd.addAll(List.of("sh", "gradlew"));
            }

            var pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            pb.directory(parent.toFile());
            var p = pb.start();
            var res = p.waitFor();
            if (res != 0) {
                System.err.println("error: could not build Skara tooling");
                System.exit(1);
            }
        } else {
            System.out.println("no updates found");
        }
    }

    public static void main(String[] args) throws Exception {
        commands.put("jcheck", GitJCheck::main);
        commands.put("webrev", GitWebrev::main);
        commands.put("defpath", GitDefpath::main);
        commands.put("verify-import", GitVerifyImport::main);
        commands.put("openjdk-import", GitOpenJDKImport::main);
        commands.put("fork", GitFork::main);
        commands.put("pr", GitPr::main);
        commands.put("token", GitToken::main);
        commands.put("info", GitInfo::main);
        commands.put("translate", GitTranslate::main);
        commands.put("sync", GitSync::main);
        commands.put("publish", GitPublish::main);

        commands.put("update", GitSkara::update);
        commands.put("help", GitSkara::usage);
        commands.put("version", GitSkara::version);

        var isEmpty = args.length == 0;
        var command = isEmpty ? "help" : args[0];
        var commandArgs = isEmpty ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (commands.containsKey(command)) {
            commands.get(command).main(commandArgs);
        } else {
            System.err.println("error: unknown command: " + command);
            usage(args);
        }
    }
}
