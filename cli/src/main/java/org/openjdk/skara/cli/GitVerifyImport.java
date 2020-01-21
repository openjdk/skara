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
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class GitVerifyImport {
    private static boolean isVerbose;

    private static <T> void diff(Set<T> hg, Set<T> git, String description) throws IOException {
        System.err.println("The following " + description + " are in the git repostiory but not in the hg repository");
        var diff = new TreeSet<T>(git);
        diff.removeAll(hg);
        for (var e : diff) {
            System.err.println("      " + e.toString());
        }

        System.err.println("The following " + description + " are in the hg repository but not in the git repository");
        diff = new TreeSet<T>(hg);
        diff.removeAll(git);
        for (var e : diff) {
            System.err.println("      " + e.toString());
        }
    }

    private static Set<String> verifyBranches(Repository hg, Repository git) throws IOException {
        var hgBranches = hg.branches()
                           .stream()
                           .map(Branch::name)
                           .collect(Collectors.toSet());
        var gitBranches = git.branches()
                             .stream()
                             .map(Branch::name)
                             .map(b -> b.equals("master") ? "default" : b)
                             .collect(Collectors.toSet());
        if (!hgBranches.equals(gitBranches)) {
            if (isVerbose) {
                diff(hgBranches, gitBranches, "branches");
            }
            System.exit(1);
        }

        return hgBranches;
    }

    private static Set<String> verifyTags(Repository hg, Repository git) throws IOException {
        var hgTags = hg.tags()
                       .stream()
                       .map(Tag::name)
                       .filter(t -> !t.equals("tip"))
                       .collect(Collectors.toSet());
        var gitTags = git.tags()
                         .stream()
                         .map(Tag::name)
                         .collect(Collectors.toSet());
        if (!hgTags.equals(gitTags)) {
            if (isVerbose) {
                diff(hgTags, gitTags, "tags");
            }
            System.exit(1);
        }

        return hgTags;
    }

    private static int compare(Path p1, Path p2) throws IOException {
        var length = 1024;
        var buffer1 = new byte[length];
        var buffer2 = new byte[length];

        var totalRead = 0;
        var size = Files.size(p1);

        try (var is1 = Files.newInputStream(p1); var is2 = Files.newInputStream(p2)) {
            while (totalRead != size) {
                var read1 = is1.readNBytes(buffer1, 0, length);
                var read2 = is2.readNBytes(buffer2, 0, length);

                if (read1 != read2) {
                    throw new RuntimeException("impossible: read1: " + read1 + ", read2: " + read2);
                }

                var index = Arrays.mismatch(buffer1, 0, read1, buffer2, 0, read2);
                if (index != -1) {
                    return totalRead + index;
                }

                totalRead += read1;
            }
        }

        return -1;
    }

    private static void verifyFiles(Repository hg, String hgRef, Repository git, String gitRef) throws IOException {
        hg.checkout(hg.resolve(hgRef).get(), false);
        git.checkout(git.resolve(gitRef).get(), false);

        var hgRoot = hg.root();
        var hgFiles = new HashSet<Path>();
        Files.walk(hgRoot)
             .filter(p -> !Files.isDirectory(p))
             .map(hgRoot::relativize)
             .filter(p -> !p.startsWith(".hg"))
             .forEach(f -> hgFiles.add(f));

        var gitRoot = git.root();
        var gitFiles = new HashSet<Path>();
        Files.walk(gitRoot)
             .filter(p -> !Files.isDirectory(p))
             .map(gitRoot::relativize)
             .filter(p -> !p.startsWith(".git"))
             .forEach(f -> gitFiles.add(f));

        if (!hgFiles.equals(gitFiles)) {
            if (isVerbose) {
                diff(hgFiles, gitFiles, "files");
            }
            System.exit(1);
        }

        for (var file : hgFiles) {
            var hgFile = hgRoot.resolve(file);
            var gitFile = gitRoot.resolve(file);
            if (Files.size(hgFile) != Files.size(gitFile)) {
                System.err.println("error: file " + file + " have different size");
            }

            try {
                var p1 = Files.getPosixFilePermissions(hgFile);
                var p2 = Files.getPosixFilePermissions(gitFile);
                if (!p1.equals(p2)) {
                    System.err.println("error: the file " + file + " have different permissions");
                }
            } catch (UnsupportedOperationException e) {
                System.err.println("warning: this file system does not suppport POSIX permissions");
            }
        }

        for (var file : hgFiles) {
            var pos = compare(hgRoot.resolve(file), gitRoot.resolve(file));
            if (pos != -1) {
                System.err.println("error: file " + file.toString() + " differ at byte " + pos);
            }
        }
    }

    private static Repository createTempRepository(ReadOnlyRepository origin) throws IOException {
        return origin.copyTo(Files.createTempDirectory("verify-import"));
    }

    public static void main(String[] args) throws IOException {
        var flags = List.of(
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

        var inputs = List.of(
                Input.position(0)
                     .describe("hg repository")
                     .singular()
                     .required());

        var parser = new ArgumentParser("git verify-import", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-verify-import version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        isVerbose = arguments.contains("verbose");

        var hgRepoPath = arguments.at(0).via(Path::of);
        var originalHgRepo = ReadOnlyRepository.get(hgRepoPath);
        if (!originalHgRepo.isPresent()) {
            System.err.println("No hg repository found at " + hgRepoPath);
            System.exit(1);
        }
        var hg = createTempRepository(originalHgRepo.get());

        var gitRepoPath = Path.of(System.getProperty("user.dir"));
        var originalGitRepo = ReadOnlyRepository.get(gitRepoPath);
        if (!originalGitRepo.isPresent()) {
            System.err.println("No git repository found at " + gitRepoPath);
            System.exit(1);
        }
        var git = createTempRepository(originalGitRepo.get());

        var branches = verifyBranches(hg, git);
        var tags = verifyTags(hg, git);

        for (var branch : branches) {
            verifyFiles(hg, branch, git, branch.equals("default") ? "master" : branch);
        }

        for (var tag : tags) {
            verifyFiles(hg, tag, git, tag);
        }
    }
}
