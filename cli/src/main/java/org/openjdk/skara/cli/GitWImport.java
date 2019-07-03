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

import org.openjdk.skara.args.ArgumentParser;
import org.openjdk.skara.args.Input;
import org.openjdk.skara.args.Option;
import org.openjdk.skara.args.Switch;
import org.openjdk.skara.vcs.Repository;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class GitWImport {

    private static final Pattern findPatchPattern = Pattern.compile(
            "[ ]*(?:<td>)?<a href=\".*\">(?<patchName>.*\\.patch)</a></td>(?:</tr>)?$");

    public static void main(String[] args) throws Exception {
        var flags = List.of(
                Option.shortcut("n")
                        .fullname("name")
                        .describe("NAME")
                        .helptext("Use NAME as a name for the patch (default is patch file name)")
                        .optional(),
                Switch.shortcut("f")
                        .fullname("file")
                        .helptext("Input is a file path")
                        .optional(),
                Switch.shortcut("k")
                        .fullname("keep")
                        .helptext("Keep downloaded patch file in current directory")
                        .optional(),
                Switch.shortcut("d")
                        .fullname("direct")
                        .helptext("Directly apply patch, without creating a branch or commit")
                        .optional());

        var inputs = List.of(
                Input.position(0)
                        .describe("webrev url|file path")
                        .singular()
                        .required());

        var parser = new ArgumentParser("git wimport", flags, inputs);
        var arguments = parser.parse(args);

        var inputString = arguments.at(0).asString();
        Path patchFile;
        String patchName;
        if (arguments.contains("file")) {
            patchFile = Paths.get(inputString);
            patchName = arguments.get("name")
                    .or(dropSuffix(patchFile.getFileName().toString(), ".patch"))
                    .asString();
        } else {
            var uri = sanitizeURI(inputString);
            var remotePatchFile = getPatchFileName(uri);
            patchName = arguments.get("name")
                    .or(dropSuffix(remotePatchFile, ".patch"))
                    .asString();
            patchFile = downloadPatchFile(
                    uri.resolve(remotePatchFile),
                    patchName,
                    arguments.contains("keep"));
        }

        var cwd = Paths.get("").toAbsolutePath();
        var repository = Repository.get(cwd);
        if (repository.isEmpty()) {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            System.exit(1);
        }
        var repo = repository.get();

        if (!check(patchFile)) {
            System.err.println("Patch does not apply cleanly!");
            System.exit(1);
        }

        if (!arguments.contains("direct")) {
            System.out.println("Creating branch: " + patchName + ", based on current head: " + repo.head());
            var branch = repo.branch(repo.head(), patchName);
            repo.checkout(branch, false);
        }

        System.out.println("Applying patch file: " + patchFile);
        stat(patchFile);
        apply(patchFile);

        if (!arguments.contains("direct")) {
            System.out.println("Creating commit for changes");
            repo.commit("Imported patch '" + patchName + "'", "wimport", "");
        }
    }

    private static String dropSuffix(String s, String suffix) {
        if (s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    private static URI sanitizeURI(String uri) throws URISyntaxException {
        uri = dropSuffix(uri, "index.html");
        return new URI(uri);
    }

    private static String getPatchFileName(URI uri) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var findPatchFileRcequest = HttpRequest.newBuilder()
                .uri(uri)
                .build();
        return client.send(findPatchFileRcequest, HttpResponse.BodyHandlers.ofLines())
                .body()
                .map(findPatchPattern::matcher)
                .filter(Matcher::matches)
                .findFirst()
                .map(m -> m.group("patchName"))
                .orElseThrow(() -> new IllegalStateException("Can not find patch file name in webrev body"));
    }

    private static Path downloadPatchFile(URI uri, String patchName, boolean keep) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var patchFile = Paths.get(patchName + ".patch");
        if (keep) {
            if (Files.exists(patchFile)) {
                System.err.println("Patch file: " + patchFile + " already exists!");
                System.exit(1);
            } else {
                Files.createFile(patchFile);
            }
        }else {
            patchFile = Files.createTempFile(patchName, ".patch");
        }

        var patchFileRequest = HttpRequest.newBuilder()
                .uri(uri)
                .build();
        client.send(patchFileRequest, HttpResponse.BodyHandlers.ofFile(patchFile));
        return patchFile;
    }

    private static boolean check(Path patchFile) throws IOException, InterruptedException {
        return applyInternal(patchFile, "--check", "--index") == 0;
    }

    private static void stat(Path patchFile) throws IOException, InterruptedException {
        applyInternal(patchFile, "--stat", "--index");
    }

    private static void apply(Path patchFile) throws IOException, InterruptedException {
        applyInternal(patchFile, "--index");
    }

    private static int applyInternal(Path patchFile, String...options) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add("git");
        args.add("apply");
        args.addAll(Arrays.asList(options));
        args.add(patchFile.toString());
        var pb = new ProcessBuilder(args.toArray(String[]::new));
        pb.inheritIO();
        return pb.start().waitFor();
    }

}
