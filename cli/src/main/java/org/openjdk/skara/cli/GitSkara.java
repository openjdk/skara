/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.git.GitVersion;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GitSkara {
    private static final Map<String, Main> commands = new TreeMap<>();
    private static final Set<String> mercurialCommands = Set.of("webrev", "defpath", "jcheck");

    private static void usage(String[] args) {
        var isMercurial = args.length > 0 && args[0].equals("--mercurial");
        var skaraCommands = Set.of("help", "version", "update");

        var names = new ArrayList<String>();
        if (isMercurial) {
            names.addAll(mercurialCommands);
            names.addAll(skaraCommands);
        } else {
            names.addAll(commands.keySet());
        }

        var vcs = isMercurial ? "hg" : "git";
        System.out.println("usage: " + vcs + " skara <" + String.join("|", names) + ">");
        System.out.println("");
        System.out.println("Additional available " + vcs + " commands:");
        for (var name : names) {
            if (!skaraCommands.contains(name)) {
                if (isMercurial) {
                    if (mercurialCommands.contains(name)) {
                        System.out.println("- hg " + name);
                    }
                } else {
                    System.out.println("- git " + name);
                }
            }
        }
        System.out.println("");
        System.out.println("To learn more about a particular command, run:");
        System.out.println("");
        System.out.println("    " + vcs + " <command> -h");
        System.out.println("");
        System.out.println("For more information, please see the Skara wiki:");
        System.out.println("");
        if (isMercurial) {
            System.out.println("    https://wiki.openjdk.java.net/display/SKARA/Mercurial");
        } else {
            System.out.println("    https://wiki.openjdk.java.net/display/skara");
        }
        System.out.println("");
        System.exit(0);
    }

    private static void version(String[] args) {
        var isMercurial = args.length > 0 && args[0].equals("--mercurial");
        var vcs = isMercurial ? "hg" : "git";
        System.out.println(vcs + " skara version: " + Version.fromManifest().orElse("unknown"));
        System.exit(0);
    }

    private static List<String> config(String key, boolean isMercurial) throws IOException, InterruptedException {
        var vcs = isMercurial ? "hg" : "git";
        var pb = new ProcessBuilder(vcs, "config", key);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var value = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return Arrays.asList(value.split("\n"));
    }

    private static void update(String[] args) throws IOException, InterruptedException {
        var isMercurial = args.length > 0 && args[0].equals("--mercurial");

        String line = null;
        if (isMercurial) {
            var lines = config("extensions.skara", true);
            if (lines.size() == 1) {
                line = lines.get(0);
            } else {
                System.err.println("error: could not find skara repository");
                System.exit(1);
            }
        } else {
            var lines = config("include.path", false);
            var entry = lines.stream().filter(l -> l.endsWith("skara.gitconfig")).findFirst();
            if (entry.isEmpty()) {
                System.err.println("error: could not find skara repository");
                System.exit(1);
            }
            line = entry.get();
        }

        var expanded = line.startsWith("~") ?
            System.getProperty("user.home") + line.substring(1) : line;
        var path = Path.of(expanded);
        if (!Files.exists(path)) {
            System.err.println("error: " + path + " does not exist");
            System.exit(1);
        }
        var parent = path.getParent();
        var repo = Repository.get(parent);
        if (repo.isEmpty()) {
            System.err.println("error: could not find skara repository");
            System.exit(1);
        }

        var head = repo.get().head();
        System.out.println("Checking for updates ...");
        repo.get().pull();
        for (var s : repo.get().submodules()) {
            repo.get().updateSubmodule(s);
        }
        var newHead = repo.get().head();

        if (!head.equals(newHead)) {
            System.out.println("Found the following updates:");
            var commits = repo.get().commitMetadata(head, newHead);
            for (var commit : commits) {
                var message = CommitMessageParsers.v1.parse(commit);
                System.out.println("- " + message.title());
            }
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
            System.out.println("No updates found");
        }
    }

    private static void checkGitVersion() {
        try {
            GitVersion version = GitVersion.get();
            if (!version.isKnownSupported()) {
                System.err.println("WARNING: Your git version is: " + version + "," +
                        " which is not a known supported version." +
                        " Please consider upgrading to a more recent version.");
            }
        } catch (IOException e) {
            System.err.println("Could not check git version: " + e.getMessage());
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
        commands.put("proxy", GitProxy::main);
        commands.put("trees", GitTrees::main);
        commands.put("hg-export", GitHgExport::main);

        commands.put("update", GitSkara::update);
        commands.put("help", GitSkara::usage);
        commands.put("version", GitSkara::version);

        checkGitVersion();

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
