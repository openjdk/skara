/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.webrev.*;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GitWebrev {
    private static final List<String> KNOWN_JBS_PROJECTS =
        List.of("JDK", "CODETOOLS", "SKARA", "JMC");
    private static void clearDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
    }

    private static String arg(String name, Arguments args, ReadOnlyRepository repo) throws IOException {
        if (args.contains(name)) {
            return args.get(name).asString();
        }

        var config = repo.config("webrev." + name);
        if (config.size() == 1) {
            return config.get(0);
        }

        return null;
    }

    private static void die(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static Hash resolve(ReadOnlyRepository repo, String ref) {
        var message = "error: could not resolve reference '" + ref + "'";
        try {
            var hash = repo.resolve(ref);
            if (!hash.isPresent()) {
                die(message);
            }
            return hash.get();
        } catch (IOException e) {
            die(message);
            return null; // impossible
        }
    }

    private static boolean isDigit(char c) {
        return Character.isDigit(c);
    }

    private static void generate(String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("r")
                  .fullname("rev")
                  .describe("REV")
                  .helptext("Compare against a specified base revision (alias for --base)")
                  .optional(),
            Option.shortcut("o")
                  .fullname("output")
                  .describe("DIR")
                  .helptext("Output directory")
                  .optional(),
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Use specified username instead of 'guessing' one")
                  .optional(),
            Option.shortcut("")
                  .fullname("upstream")
                  .describe("URL")
                  .helptext("The URL to the upstream repository")
                  .optional(),
            Option.shortcut("t")
                  .fullname("title")
                  .describe("TITLE")
                  .helptext("The title of the webrev")
                  .optional(),
            Option.shortcut("c")
                  .fullname("cr")
                  .describe("CR#")
                  .helptext("Include link to the CR (aka bugid) in the main page")
                  .optional(),
            Option.shortcut("")
                  .fullname("remote")
                  .describe("NAME")
                  .helptext("Use specified remote for calculating outgoing changes")
                  .optional(),
            Option.shortcut("")
                  .fullname("base")
                  .describe("REV")
                  .helptext("Use specified revision as base for comparison")
                  .optional(),
            Option.shortcut("")
                  .fullname("head")
                  .describe("REV")
                  .helptext("Use specified revision as head for comparison")
                  .optional(),
            Option.shortcut("s")
                  .fullname("similarity")
                  .describe("SIMILARITY")
                  .helptext("Guess renamed files by similarity (0 - 100)")
                  .optional(),
            Switch.shortcut("b")
                  .fullname("")
                  .helptext("Do not ignore changes in whitespace (always true)")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("")
                  .fullname("json")
                  .helptext("Generate JSON instead of HTML")
                  .optional(),
            Switch.shortcut("C")
                  .fullname("no-comments")
                  .helptext("Do not show comments")
                  .optional(),
            Switch.shortcut("N")
                  .fullname("no-outgoing")
                  .helptext("Do not compare against remote, use only 'status'")
                  .optional(),
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional(),
            Switch.shortcut("")
                  .fullname("quiet")
                  .helptext("Do not print webrev link after executing successfully")
                  .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("FILE")
                 .singular()
                 .optional());

        var parser = new ArgumentParser("git webrev", flags, inputs);
        var arguments = parser.parse(args);

        var version = Version.fromManifest().orElse("unknown");
        if (arguments.contains("version")) {
            System.out.println("git-webrev version: " + version);
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        } else {
            Logging.setup(Level.WARNING);
        }

        var cwd = Paths.get("").toAbsolutePath();
        var repository = ReadOnlyRepository.get(cwd);
        if (!repository.isPresent()) {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            System.exit(1);
        }
        var repo = repository.get();
        var isMercurial = arguments.contains("mercurial");


        URI upstreamPullPath = null;
        URI originPullPath = null;
        var remotes = repo.remotes();
        if (remotes.contains("upstream")) {
            upstreamPullPath = Remote.toWebURI(repo.pullPath("upstream"));
        }
        if (remotes.contains("origin") || remotes.contains("default")) {
            var remote = isMercurial ? "default" : "origin";
            originPullPath = Remote.toWebURI(repo.pullPath(remote));
        }

        if (arguments.contains("json") &&
            (upstreamPullPath == null || originPullPath == null)) {
            System.err.println("error: --json requires remotes 'upstream' and 'origin' to be present");
            System.exit(1);
        }

        var upstream = arg("upstream", arguments, repo);
        if (upstream == null) {
            if (upstreamPullPath != null) {
                var host = upstreamPullPath.getHost();
                if (host != null && host.endsWith("openjdk.org")) {
                    upstream = upstreamPullPath.toString();
                } else if (host != null && host.equals("github.com")) {
                    var path = upstreamPullPath.getPath();
                    if (path != null && path.startsWith("/openjdk/")) {
                        upstream = upstreamPullPath.toString();
                    }
                }
            } else if (originPullPath != null) {
                var host = originPullPath.getHost();
                if (host != null && host.endsWith("openjdk.org")) {
                    upstream = originPullPath.toString();
                } else if (host != null && host.equals("github.com")) {
                    var path = originPullPath.getPath();
                    if (path != null && path.startsWith("/openjdk/")) {
                        upstream = originPullPath.toString();
                    }
                }
            }
        }
        var upstreamURL = upstream;

        var noOutgoing = arguments.contains("no-outgoing");
        if (!noOutgoing) {
            var config = repo.config("webrev.no-outgoing");
            if (config.size() == 1) {
                var enabled = Set.of("TRUE", "ON", "1", "ENABLED");
                noOutgoing = enabled.contains(config.get(0).toUpperCase());
            }
        }

        var comments = !arguments.contains("no-comments");
        var quiet = arguments.contains("quiet");
        if (arguments.contains("base") && arguments.contains("rev")) {
            System.err.println("error: cannot combine --base and --rev options");
            System.exit(1);
        }
        if (arguments.contains("head") && arguments.contains("rev")) {
            System.err.println("error: cannot combine --head and --rev options");
            System.exit(1);
        }
        if (arguments.contains("head") && !arguments.contains("base")) {
            System.err.println("error: cannot use --head without using --base");
            System.exit(1);
        }

        Hash rev = null;
        var revArg = ForgeUtils.getOption("rev", arguments);
        if (revArg != null) {
            rev = resolve(repo, revArg);
        }
        if (rev == null && !(arguments.contains("base") && arguments.contains("head"))) {
            if (isMercurial) {
                resolve(repo, noOutgoing ? "tip" : "min(outgoing())^");
            } else {
                if (noOutgoing) {
                    rev = resolve(repo, "HEAD");
                } else {
                    var currentUpstreamBranch = repo.currentBranch().flatMap(b -> {
                        try {
                            return repo.upstreamFor(b);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    if (currentUpstreamBranch.isPresent()) {
                        rev = resolve(repo, currentUpstreamBranch.get());
                    } else {
                        String remote = arg("remote", arguments, repo);
                        if (remote == null) {
                            if (remotes.size() == 0) {
                                System.err.println("error: no remotes present, cannot figure out outgoing changes");
                                System.err.println("       Use --rev to specify revision to compare against");
                                System.exit(1);
                            } else if (remotes.size() == 1) {
                                remote = remotes.get(0);
                            } else {
                                if (remotes.contains("origin")) {
                                    remote = "origin";
                                } else {
                                    System.err.println("error: multiple remotes without origin remote present, cannot figure out outgoing changes");
                                    System.err.println("       Use --rev to specify revision to compare against");
                                    System.exit(1);
                                }
                            }
                        }

                        var head = repo.head();
                        var shortestDistance = -1;
                        var pullPath = repo.pullPath(remote);
                        for (var branch : repo.branches(remote)) {
                            var branchHead = repo.resolve(branch).orElseThrow();
                            var mergeBase = repo.mergeBase(branchHead, head);
                            var distance = repo.commitMetadata(mergeBase, head).size();
                            if (shortestDistance == -1 || distance < shortestDistance) {
                                rev = mergeBase;
                                shortestDistance = distance;
                            }
                        }
                    }
                }
            }
        }

        var base = rev;
        var baseArg = ForgeUtils.getOption("base", arguments);
        if (baseArg != null) {
            base = resolve(repo, baseArg);
        }
        Hash head = null;
        var headArg = ForgeUtils.getOption("head", arguments);
        if (headArg != null) {
            head = resolve(repo, headArg);
        }

        String issue = ForgeUtils.getOption("cr", arguments);
        if (issue != null) {
            if (issue.startsWith("http")) {
                var uri = URI.create(issue);
                issue = Path.of(uri.getPath()).getFileName().toString();
            } else if (isDigit(issue.charAt(0))) {
                issue = "JDK-" + issue;
            }
        }
        if (issue == null) {
            var pattern = Pattern.compile("(?:(" + String.join("|", KNOWN_JBS_PROJECTS) + ")-)?([0-9]+).*");
            var currentBranch = repo.currentBranch();
            if (currentBranch.isPresent()) {
                var branchName = currentBranch.get().name().toUpperCase();
                var m = pattern.matcher(branchName);
                if (m.matches()) {
                    var project = m.group(1);
                    if (project == null) {
                        project = "JDK";
                    }
                    var id = m.group(2);
                    issue = project + "-" + id;
                }
            }
        }

        var out = arg("output", arguments, repo);
        if (out == null) {
            out = "webrev";
        }
        var output = Path.of(out);

        String title = ForgeUtils.getOption("title", arguments);
        if (title == null && issue != null) {
            try {
                var uri = new URI(issue);
                title = Path.of(uri.getPath()).getFileName().toString();
            } catch (URISyntaxException e) {
                title = null;
            }
        }
        if (title == null && upstream != null) {
            var index = upstream.lastIndexOf("/");
            if (index != -1 && index + 1 < upstream.length()) {
                title = upstream.substring(index + 1);
            }
        }
        if (title == null) {
            title = Path.of("").toAbsolutePath().getFileName().toString();
        }

        var username = arg("username", arguments, repo);
        if (username == null) {
            username = repo.username().orElse(System.getProperty("user.name"));
        }
        var author = Author.fromString(username);

        if (Files.exists(output)) {
            clearDirectory(output);
        }

        List<Path> files = List.of();
        if (arguments.at(0).isPresent()) {
            var path = arguments.at(0).via(Path::of);
            if (path.equals(Path.of("-"))) {
                var reader = new BufferedReader(new InputStreamReader(System.in));
                files = reader.lines().map(Path::of).collect(Collectors.toList());
            } else if (Files.exists(path) && !Files.isDirectory(path) && Files.isReadable(path)) {
                files = Files.readAllLines(path).stream().map(Path::of).collect(Collectors.toList());
            } else {
                System.err.println(String.format("error: '%s' is not a readable file", path));
                System.exit(1);
            }
        }

        var similarity = 90;
        try {
            var similarityArg = arg("similarity", arguments, repo);
            if (similarityArg != null) {
                var value = Integer.parseInt(similarityArg);
                if (value < 0 || value > 100) {
                    System.err.println("error: --similarity must be a number between 0 and 100");
                    System.exit(1);
                }
                similarity = value;
            }
        } catch (NumberFormatException e) {
                System.err.println("error: --similarity must be a number between 0 and 100");
                System.exit(1);
        }

        var jbs = "https://bugs.openjdk.org/browse/";
        var issueParts = issue != null ? issue.split("-") : new String[0];
        var jbsProject = issueParts.length == 2 && KNOWN_JBS_PROJECTS.contains(issueParts[0])?
            issueParts[0] : "JDK";
        if (arguments.contains("json")) {
            if (head == null) {
                head = repo.head();
            }
            var upstreamName = upstreamPullPath.getPath().substring(1);
            var originName = originPullPath.getPath().substring(1);
            try {
                Webrev.repository(repo)
                      .output(output)
                      .upstream(upstreamPullPath, upstreamName)
                      .fork(originPullPath, originName)
                      .similarity(similarity)
                      .generateJSON(base, head);
            } catch (DiffTooLargeException e) {
                System.out.println("Webrev is not available because diff is too large.");
            }
        } else {
            try {
                Webrev.repository(repo)
                      .output(output)
                      .title(title)
                      .upstream(upstream)
                      .username(author.name())
                      .commitLinker(hash -> upstreamURL == null ? null : upstreamURL + "/commit/" + hash)
                      .issueLinker(id -> jbs + (isDigit(id.charAt(0)) ? jbsProject + "-" : "") + id)
                      .issue(issue)
                      .version(version)
                      .files(files)
                      .similarity(similarity)
                      .comments(comments)
                      .generate(base, head);
            } catch (DiffTooLargeException e) {
                System.out.println("Webrev is not available because diff is too large.");
            }
        }
        if (!quiet) {
            System.out.println("Webrev executed successfully, details are in the link below:");
            System.out.println(output.resolve("index.html").toUri());
        }
    }

    private static void apply(String[] args) throws Exception {
        var inputs = List.of(
            Input.position(0)
                 .describe("webrev url")
                 .singular()
                 .required());

        var parser = new ArgumentParser("git webrev apply", List.of(), inputs);
        var arguments = parser.parse(args);

        var cwd = Paths.get("").toAbsolutePath();
        var repository = Repository.get(cwd).orElseGet(() -> {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            System.exit(1);
            return null;
        });

        var inputString = arguments.at(0).asString();
        var webrevMetaData = WebrevMetaData.from(URI.create(inputString));
        var patchFileURI = webrevMetaData.patchURI()
                .orElseThrow(() -> new IllegalStateException("Could not find patch file in webrev"));
        var patchFile = downloadPatchFile(patchFileURI);

        repository.apply(patchFile, false);
    }

    private static Path downloadPatchFile(URI uri) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var patchFile = Files.createTempFile("patch", ".patch");
        var patchFileRequest = HttpRequest.newBuilder()
                .uri(uri)
                .build();
        client.send(patchFileRequest, HttpResponse.BodyHandlers.ofFile(patchFile));
        return patchFile;
    }

    public static void main(String[] args) throws Exception {
        var commands = List.of(
                    Default.name("generate")
                           .helptext("generate a webrev")
                           .main(GitWebrev::generate),
                    Command.name("apply")
                           .helptext("apply a webrev from a webrev url")
                           .main(GitWebrev::apply)
                );
        HttpProxy.setup();

        var parser = new MultiCommandParser("git webrev", commands, false);
        var command = parser.parse(args);
        command.execute();
    }
}
