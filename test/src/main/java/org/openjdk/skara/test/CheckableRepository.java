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
package org.openjdk.skara.test;

import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class CheckableRepository {
    private static String markerLine = "The very first line\n";

    private static Path checkableFile(Path path) throws IOException {
        try (var checkable = Files.newBufferedReader(path.resolve(".checkable/name.txt"), StandardCharsets.UTF_8)) {
            var checkableName = checkable.readLine();
            return path.resolve(checkableName);
        }
    }

    public static Repository init(Path path, VCS vcs, Path appendableFilePath) throws IOException {
        var repo = Repository.init(path, vcs);

        Files.createDirectories(path.resolve(".checkable"));
        try (var output = Files.newBufferedWriter(path.resolve(".checkable/name.txt"))) {
            output.write(appendableFilePath.toString());
        }
        repo.add(path.resolve(".checkable/name.txt"));

        var initialFile = path.resolve(appendableFilePath);
        try (var output = Files.newBufferedWriter(initialFile)) {
            output.append(markerLine);
        }
        repo.add(initialFile);

        Files.createDirectories(path.resolve(".jcheck"));
        var checkConf = path.resolve(".jcheck/conf");
        try (var output = Files.newBufferedWriter(checkConf)) {
            output.append("[general]\n");
            output.append("project=test\n");
            output.append("\n");
            output.append("[checks]\n");
            output.append("error=author,reviewers,whitespace\n");
            output.append("\n");
            output.append("[census]\n");
            output.append("version=0\n");
            output.append("domain=openjdk.java.net\n");
            output.append("\n");
            output.append("[checks \"whitespace\"]\n");
            output.append("files=.*\\.txt\n");
            output.append("\n");
            output.append("[checks \"reviewers\"]\n");
            output.append("minimum=1\n");
        }
        repo.add(checkConf);

        repo.commit("Initial commit", "testauthor", "ta@none.none");

        return repo;
    }

    public static Repository init(Path path, VCS vcs) throws IOException {
        return init(path, vcs, Path.of("appendable.txt"));
    }

    public static Hash appendAndCommit(Repository repo) throws IOException {
        return appendAndCommit(repo, "This is a new line");
    }

    public static Hash appendAndCommit(Repository repo, String body) throws IOException {
        return appendAndCommit(repo, body, "Append commit");
    }

    public static Hash appendAndCommit(Repository repo, String body, String message) throws IOException {
        return appendAndCommit(repo, body, message, "testauthor", "ta@none.none");
    }

    public static Hash appendAndCommit(Repository repo, String body, String message, String authorName, String authorEmail) throws IOException {
        return appendAndCommit(repo, body, message, authorName, authorEmail, authorName, authorEmail);
    }

    public static Hash appendAndCommit(Repository repo, String body, String message, String authorName, String authorEmail,
                                       String committerName, String committerEmail) throws IOException {
        var file = checkableFile(repo.root());
        try (var output = Files.newBufferedWriter(file, StandardOpenOption.APPEND)) {
            output.append(body);
            output.append("\n");
        }
        repo.add(file);

        return repo.commit(message, authorName, authorEmail, committerName, committerEmail);
    }

    public static Hash replaceAndCommit(Repository repo, String body) throws IOException {
        return replaceAndCommit(repo, body, "Replace commit", "testauthor", "ta@none.none");
    }

    public static Hash replaceAndCommit(Repository repo, String body, String message, String authorName, String authorEmail) throws IOException {
        var file = checkableFile(repo.root());
        try (var output = Files.newBufferedWriter(file)) {
            output.append(markerLine);
            output.append(body);
            output.append("\n");
        }
        repo.add(file);

        return repo.commit(message, authorName, authorEmail);
    }

    public static boolean hasBeenEdited(Repository repo) throws IOException {
        var file = checkableFile(repo.root());
        var lines = Files.readAllLines(file);
        return lines.size() > 1;
    }
}
