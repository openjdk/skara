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

import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class GitTrees {
    private static boolean isRepository(Path root, boolean isMercurial) {
        var hidden = isMercurial ? root.resolve(".hg") : root.resolve(".git");
        return Files.exists(hidden) && Files.isDirectory(hidden);
    }

    private static Path root(boolean isMercurial) throws IOException, InterruptedException {
        var pb = isMercurial ?
            new ProcessBuilder("hg", "root") :
            new ProcessBuilder("git", "rev-parse", "--show-toplevel");
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        var res = p.waitFor();
        if (res != 0) {
            System.exit(res);
        }

        return Path.of(output);
    }

    private static List<Path> subrepos(Path root, boolean isMercurial) throws IOException {
        var r = Files.walk(root)
                    .filter(d -> d.getFileName().toString().equals(isMercurial ? ".hg" : ".git"))
                    .map(d -> d.getParent())
                    .filter(d -> !d.equals(root))
                    .map(d -> root.relativize(d))
                    .sorted()
                    .collect(Collectors.toList());
        return r;
    }

    private static Path treesFile(Path root, boolean isMercurial) {
        return root.resolve(isMercurial ? ".hg" : ".git").resolve("trees");
    }

    private static List<Path> tconfig(Path root, boolean isMercurial) throws IOException {
        var subrepos = subrepos(root, isMercurial);
        var treesFile = treesFile(root, isMercurial);
        Files.write(treesFile, subrepos.stream().map(Path::toString).collect(Collectors.toList()));

        for (var subrepo : subrepos) {
            var subSubRepos = new ArrayList<Path>();
            for (var repo : subrepos) {
                if (!repo.equals(subrepo) && repo.startsWith(subrepo)) {
                    subSubRepos.add(repo);
                }
            }
            if (!subSubRepos.isEmpty()) {
                var subSubTreesFile = treesFile(root.resolve(subrepo), isMercurial);
                Files.write(subSubTreesFile,
                            subSubRepos.stream()
                                       .map(subrepo::relativize)
                                       .map(Path::toString)
                                       .sorted()
                                       .collect(Collectors.toList()));
            }
        }

        return subrepos;
    }

    private static List<Path> trees(Path root, boolean isMercurial) throws IOException {
        var file = treesFile(root, isMercurial);
        if (Files.exists(file)) {
            var lines = Files.readAllLines(file);
            return lines.stream().map(Path::of).collect(Collectors.toList());
        }

        return null;
    }

    private static void treconfigure(Path root, boolean isMercurial) throws IOException {
        var existing = trees(root, isMercurial);
        if (existing != null) {
            for (var subrepo : existing) {
                var subRoot = root.resolve(subrepo);
                var file = treesFile(subRoot, isMercurial);
                Files.deleteIfExists(file);
            }
        }
        tconfig(root, isMercurial);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("git-trees version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
            System.out.println("usage: git-trees [options] <COMMAND>");
            System.out.println("\t-m, --mercurial\tDeprecated: force use of mercurial");
            System.out.println("\t-h, --help     \tShow this help text");
            System.out.println("\t    --version  \tPrint the version of this tool");
            System.exit(0);
        }

        HttpProxy.setup();

        var isMercurial = args.length > 0 && (args[0].equals("--mercurial") || args[0].equals("-m"));
        var isReconfigure = isMercurial ?
            args.length > 1 && args[1].equals("treconfigure") :
            args.length > 0 && args[0].equals("treconfigure");

        var root = root(isMercurial);
        if (isReconfigure) {
            treconfigure(root, isMercurial);
            return;
        }

        var trees = trees(root, isMercurial);
        if (trees == null) {
            trees = tconfig(root, isMercurial);
        }

        var command = new ArrayList<String>();
        command.add(isMercurial ? "hg" : "git");
        for (var i = isMercurial ? 1 : 0; i < args.length; i++) {
            command.add(args[i]);
        }
        var pb = new ProcessBuilder(command);
        pb.inheritIO();

        System.out.println("[" + root.toString() + "]");
        pb.directory(root.toFile());
        var ret = pb.start().waitFor();

        for (var path : trees) {
            var subroot = root.resolve(path);
            if (isRepository(subroot, isMercurial)) {
                System.out.println("[" + root.resolve(path).toString() + "]");
                pb.directory(subroot.toFile());
                ret += pb.start().waitFor();
            }
        }

        System.exit(ret);
    }
}
