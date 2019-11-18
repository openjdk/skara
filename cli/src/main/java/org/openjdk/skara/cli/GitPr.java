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

import org.openjdk.skara.args.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GitPr {
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

    private static void await(Process p) throws IOException {
        try {
            var res = p.waitFor();
            if (res != 0) {
                throw new IOException("Unexpected exit code " + res);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private static boolean spawnEditor(ReadOnlyRepository repo, Path file) throws IOException {
        String editor = null;
        var lines = repo.config("core.editor");
        if (lines.size() == 1) {
            editor = lines.get(0);
        }
        if (editor == null) {
            editor = System.getenv("GIT_EDITOR");
        }
        if (editor == null) {
            editor = System.getenv("EDITOR");
        }
        if (editor == null) {
            editor = System.getenv("VISUAL");
        }
        if (editor == null) {
            editor = "vi";
        }

        var pb = new ProcessBuilder(editor, file.toString());
        pb.inheritIO();
        var p = pb.start();
        try {
            return p.waitFor() == 0;
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private static String projectName(URI uri) {
        var name = uri.getPath().toString().substring(1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        return name;
    }

    private static HostedRepository getHostedRepositoryFor(URI uri, GitCredentials credentials) throws IOException {
        var host = Forge.from(uri, new Credential(credentials.username(), credentials.password()));
        if (System.getenv("GIT_TOKEN") == null) {
            GitCredentials.approve(credentials);
        }
        if (host.isEmpty() || !host.get().isValid()) {
            exit("error: failed to connect to host " + uri);
        }
        var remoteRepo = host.get().repository(projectName(uri)).orElseThrow(() ->
                new IOException("Could not find repository at: " + uri.toString())
        );
        var parentRepo = remoteRepo.parent();
        var targetRepo = parentRepo.isPresent() ? parentRepo.get() : remoteRepo;
        return targetRepo;
    }

    private static PullRequest getPullRequest(URI uri, GitCredentials credentials, Argument prId) throws IOException {
        if (!prId.isPresent()) {
            exit("error: missing pull request identifier");
        }

        var pr = getHostedRepositoryFor(uri, credentials).pullRequest(prId.asString());
        if (pr == null) {
            exit("error: could not fetch PR information");
        }

        return pr;
    }

    private static void show(String ref, Hash hash) throws IOException {
        show(ref, hash, null);
    }
    private static void show(String ref, Hash hash, Path dir) throws IOException {
        var pb = new ProcessBuilder("git", "diff", "--binary",
                                                   "--patch",
                                                   "--find-renames=50%",
                                                   "--find-copies=50%",
                                                   "--find-copies-harder",
                                                   "--abbrev",
                                                   ref + "..." + hash.hex());
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.inheritIO();
        await(pb.start());
    }

    private static void gimport() throws IOException {
        var pb = new ProcessBuilder("hg", "gimport");
        pb.inheritIO();
        await(pb.start());
    }

    private static void hgImport(Path patch) throws IOException {
        var pb = new ProcessBuilder("hg", "import", "--no-commit", patch.toAbsolutePath().toString());
        pb.inheritIO();
        await(pb.start());
    }

    private static List<String> hgTags() throws IOException, InterruptedException {
        var pb = new ProcessBuilder("hg", "tags", "--quiet");
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var bytes = p.getInputStream().readAllBytes();
        var exited = p.waitFor(1, TimeUnit.MINUTES);
        var exitValue = p.exitValue();
        if (!exited || exitValue != 0) {
            throw new IOException("'hg tags' exited with value: " + exitValue);
        }

        return Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n"));
    }

    private static String hgResolve(String ref) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("hg", "log", "-r", ref, "--template", "{node}");
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        var p = pb.start();
        var bytes = p.getInputStream().readAllBytes();
        var exited = p.waitFor(1, TimeUnit.MINUTES);
        var exitValue = p.exitValue();
        if (!exited || exitValue != 0) {
            throw new IOException("'hg log' exited with value: " + exitValue);
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Path diff(String ref, Hash hash) throws IOException {
        return diff(ref, hash, null);
    }

    private static Path diff(String ref, Hash hash, Path dir) throws IOException {
        var patch = Files.createTempFile(hash.hex(), ".patch");
        var pb = new ProcessBuilder("git", "diff", "--binary",
                                                   "--patch",
                                                   "--find-renames=50%",
                                                   "--find-copies=50%",
                                                   "--find-copies-harder",
                                                   "--abbrev",
                                                   ref + "..." + hash.hex());
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.redirectOutput(patch.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        await(pb.start());
        return patch;
    }

    private static void apply(Path patch) throws IOException {
        var pb = new ProcessBuilder("git", "apply", "--no-commit", patch.toString());
        pb.inheritIO();
        await(pb.start());
    }

    private static int longest(List<String> strings) {
        return strings.stream().mapToInt(String::length).max().orElse(0);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Username on host")
                  .optional(),
            Option.shortcut("r")
                  .fullname("remote")
                  .describe("NAME")
                  .helptext("Name of remote, defaults to 'origin'")
                  .optional(),
            Option.shortcut("b")
                  .fullname("branch")
                  .describe("NAME")
                  .helptext("Name of target branch, defaults to 'master'")
                  .optional(),
            Option.shortcut("")
                  .fullname("authors")
                  .describe("LIST")
                  .helptext("Comma separated list of authors")
                  .optional(),
            Option.shortcut("")
                  .fullname("assignees")
                  .describe("LIST")
                  .helptext("Comma separated list of assignees")
                  .optional(),
            Option.shortcut("")
                  .fullname("labels")
                  .describe("LIST")
                  .helptext("Comma separated list of labels")
                  .optional(),
            Option.shortcut("")
                  .fullname("columns")
                  .describe("id,title,author,assignees,labels")
                  .helptext("Comma separated list of columns to show")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-decoration")
                  .helptext("Hide any decorations when listing PRs")
                  .optional(),
            Switch.shortcut("")
                  .fullname("mercurial")
                  .helptext("Force use of Mercurial (hg)")
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
                  .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("list|fetch|show|checkout|apply|integrate|approve|create|close|update")
                 .singular()
                 .required(),
            Input.position(1)
                 .describe("ID")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-pr version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        HttpProxy.setup();

        var isMercurial = arguments.contains("mercurial");
        var cwd = Path.of("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() -> new IOException("no git repository found at " + cwd.toString()));
        var remote = arguments.get("remote").orString(isMercurial ? "default" : "origin");
        var remotePullPath = repo.pullPath(remote);
        var username = arguments.contains("username") ? arguments.get("username").asString() : null;
        var token = isMercurial ? System.getenv("HG_TOKEN") :  System.getenv("GIT_TOKEN");
        var uri = Remote.toWebURI(remotePullPath);
        var credentials = GitCredentials.fill(uri.getHost(), uri.getPath(), username, token, uri.getScheme());
        var host = Forge.from(uri, new Credential(credentials.username(), credentials.password()));
        if (host.isEmpty() || !host.get().isValid()) {
            exit("error: failed to connect to host " + uri);
        }

        var action = arguments.at(0).asString();
        if (action.equals("create")) {
            if (isMercurial) {
                var currentBookmark = repo.currentBookmark();
                if (!currentBookmark.isPresent()) {
                    System.err.println("error: no bookmark is active, you must be on an active bookmark");
                    System.err.println("");
                    System.err.println("To create a bookmark and activate it, run:");
                    System.err.println("");
                    System.err.println("    hg bookmark NAME-FOR-YOUR-BOOKMARK");
                    System.err.println("");
                    System.exit(1);
                }

                var bookmark = currentBookmark.get();
                if (bookmark.equals(new Bookmark("master"))) {
                    System.err.println("error: you should not create pull requests from the 'master' bookmark");
                    System.err.println("To create a bookmark and activate it, run:");
                    System.err.println("");
                    System.err.println("    hg bookmark NAME-FOR-YOUR-BOOKMARK");
                    System.err.println("");
                    System.exit(1);
                }

                var tags = hgTags();
                var upstreams = tags.stream()
                                    .filter(t -> t.endsWith(bookmark.name()))
                                    .collect(Collectors.toList());
                if (upstreams.isEmpty()) {
                    System.err.println("error: there is no remote branch for the local bookmark '" + bookmark.name() + "'");
                    System.err.println("");
                    System.err.println("To create a remote branch and push the commits for your local branch, run:");
                    System.err.println("");
                    System.err.println("    hg push --bookmark " + bookmark.name());
                    System.err.println("");
                    System.exit(1);
                }

                var tagsAndHashes = new HashMap<String, String>();
                for (var tag : tags) {
                    tagsAndHashes.put(tag, hgResolve(tag));
                }
                var bookmarkHash = hgResolve(bookmark.name());
                if (!tagsAndHashes.containsValue(bookmarkHash)) {
                    System.err.println("error: there are local commits on bookmark '" + bookmark.name() + "' not present in a remote repository");
                    System.err.println("");

                    if (upstreams.size() == 1) {
                        System.err.println("To push the local commits to the remote repository, run:");
                        System.err.println("");
                        System.err.println("    hg push --bookmark " + bookmark.name() + " " + upstreams.get(0));
                        System.err.println("");
                    } else {
                        System.err.println("The following paths contains the " + bookmark.name() + " bookmark:");
                        System.err.println("");
                        for (var upstream : upstreams) {
                            System.err.println("- " + upstream.replace("/" + bookmark.name(), ""));
                        }
                        System.err.println("");
                        System.err.println("To push the local commits to a remote repository, run:");
                        System.err.println("");
                        System.err.println("    hg push --bookmark " + bookmark.name() + " <PATH>");
                        System.err.println("");
                    }
                    System.exit(1);
                }

                var targetBranch = arguments.get("branch").orString("master");
                var targetHash = hgResolve(targetBranch);
                var commits = repo.commits(targetHash + ".." + bookmarkHash + "-" + targetHash).asList();
                if (commits.isEmpty()) {
                    System.err.println("error: no difference between bookmarks " + targetBranch + " and " + bookmark.name());
                    System.err.println("       Cannot create an empty pull request, have you committed?");
                    System.exit(1);
                }

                var diff = repo.diff(repo.head());
                if (!diff.patches().isEmpty()) {
                    System.err.println("error: there are uncommitted changes in your working tree:");
                    System.err.println("");
                    for (var patch : diff.patches()) {
                        var path = patch.target().path().isPresent() ?
                            patch.target().path().get() : patch.source().path().get();
                        System.err.println("    " + patch.status().toString() + " " + path.toString());
                    }
                    System.err.println("");
                    System.err.println("If these changes are meant to be part of the pull request, run:");
                    System.err.println("");
                    System.err.println("    hg commit --amend");
                    System.err.println("    hg git-cleanup");
                    System.err.println("    hg push --bookmark " + bookmark.name() + " <PATH>");
                    System.err.println("    hg gimport");
                    System.err.println("");
                    System.err.println("If these changes are *not* meant to be part of the pull request, run:");
                    System.err.println("");
                    System.err.println("    hg shelve");
                    System.err.println("");
                    System.err.println("(You can later restore the changes by running: hg unshelve)");
                    System.exit(1);
                }

                var remoteRepo = host.get().repository(projectName(uri)).orElseThrow(() ->
                        new IOException("Could not find repository at " + uri.toString())
                );
                if (token == null) {
                    GitCredentials.approve(credentials);
                }
                var parentRepo = remoteRepo.parent().orElseThrow(() ->
                        new IOException("error: remote repository " + remotePullPath + " is not a fork of any repository"));

                var file = Files.createTempFile("PULL_REQUEST_", ".txt");
                if (commits.size() == 1) {
                    var commit = commits.get(0);
                    var message = CommitMessageParsers.v1.parse(commit.message());
                    Files.writeString(file, message.title() + "\n");
                    if (!message.summaries().isEmpty()) {
                        Files.write(file, message.summaries(), StandardOpenOption.APPEND);
                    }
                    if (!message.additional().isEmpty()) {
                        Files.write(file, message.additional(), StandardOpenOption.APPEND);
                    }
                } else {
                    Files.write(file, List.of(""));
                }
                Files.write(file, List.of(
                    "# Please enter the pull request message for your changes. Lines starting",
                    "# with '#' will be ignored, and an empty message aborts the pull request.",
                    "# The first line will be considered the subject, use a blank line to separate",
                    "# the subject from the body.",
                    "#",
                    "# Commits to be included from branch '" + bookmark.name() + "'"
                    ),
                    StandardOpenOption.APPEND
                );
                for (var commit : commits) {
                    var desc = commit.hash().abbreviate() + ": " + commit.message().get(0);
                    Files.writeString(file, "# - " + desc + "\n", StandardOpenOption.APPEND);
                }
                Files.writeString(file, "#\n", StandardOpenOption.APPEND);
                Files.writeString(file, "# Target repository: " + remotePullPath + "\n", StandardOpenOption.APPEND);
                Files.writeString(file, "# Target branch: " + targetBranch + "\n", StandardOpenOption.APPEND);
                var success = spawnEditor(repo, file);
                if (!success) {
                    System.err.println("error: editor exited with non-zero status code, aborting");
                    System.exit(1);
                }
                var lines = Files.readAllLines(file)
                                 .stream()
                                 .filter(l -> !l.startsWith("#"))
                                 .collect(Collectors.toList());
                var isEmpty = lines.stream().allMatch(String::isEmpty);
                if (isEmpty) {
                    System.err.println("error: no message present, aborting");
                    System.exit(1);
                }

                var title = lines.get(0);
                List<String> body = null;
                if (lines.size() > 1) {
                    body = lines.subList(1, lines.size())
                                .stream()
                                .dropWhile(String::isEmpty)
                                .collect(Collectors.toList());
                } else {
                    body = Collections.emptyList();
                }

                var pr = remoteRepo.createPullRequest(parentRepo, targetBranch, bookmark.name(), title, body);
                if (arguments.contains("assignees")) {
                    var usernames = Arrays.asList(arguments.get("assignees").asString().split(","));
                    var assignees = usernames.stream()
                                             .map(u -> host.get().user(u))
                                             .collect(Collectors.toList());
                    pr.setAssignees(assignees);
                }
                System.out.println(pr.webUrl().toString());
                Files.deleteIfExists(file);

                System.exit(0);
            }
            var currentBranch = repo.currentBranch();
            if (currentBranch.equals(repo.defaultBranch())) {
                System.err.println("error: you should not create pull requests from the 'master' branch");
                System.err.println("");
                System.err.println("To create a local branch for your changes and restore the 'master' branch, run:");
                System.err.println("");
                System.err.println("    git checkout -b NAME-FOR-YOUR-LOCAL-BRANCH");
                System.err.println("    git branch --force master origin/master");
                System.err.println("");
                System.exit(1);
            }

            var upstream = repo.upstreamFor(currentBranch);
            if (upstream.isEmpty()) {
                System.err.println("error: there is no remote branch for the local branch '" + currentBranch.name() + "'");
                System.err.println("");
                System.err.println("A remote branch must be present at " + remotePullPath + " to create a pull request");
                System.err.println("To create a remote branch and push the commits for your local branch, run:");
                System.err.println("");
                System.err.println("    git push --set-upstream " + remote + " " + currentBranch.name());
                System.err.println("");
                System.err.println("If you created the remote branch from another client, you must update this repository.");
                System.err.println("To update remote information for this repository, run:");
                System.err.println("");
                System.err.println("    git fetch " + remote);
                System.err.println("    git branch --set-upstream " + currentBranch + " " + remote + "/" + currentBranch);
                System.err.println("");
                System.exit(1);
            }

            var upstreamRefName = upstream.get().substring(remote.length() + 1);
            repo.fetch(uri, upstreamRefName);
            var branchCommits = repo.commits(upstream.get() + ".." + currentBranch.name()).asList();
            if (!branchCommits.isEmpty()) {
                System.err.println("error: there are local commits on branch '" + currentBranch.name() + "' not present in the remote repository " + remotePullPath);
                System.err.println("");
                System.err.println("All commits must be present in the remote repository to be part of the pull request");
                System.err.println("The following commits are not present in the remote repository:");
                System.err.println("");
                for (var commit : branchCommits) {
                    System.err.println("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
                }
                System.err.println("");
                System.err.println("To push the above local commits to the remote repository, run:");
                System.err.println("");
                System.err.println("    git push " + remote + " " + currentBranch.name());
                System.err.println("");
                System.exit(1);
            }

            var targetBranch = arguments.get("branch").orString("master");
            var commits = repo.commits(targetBranch + ".." + currentBranch.name()).asList();
            if (commits.isEmpty()) {
                System.err.println("error: no difference between branches " + targetBranch + " and " + currentBranch.name());
                System.err.println("       Cannot create an empty pull request, have you committed?");
                System.exit(1);
            }

            var diff = repo.diff(repo.head());
            if (!diff.patches().isEmpty()) {
                System.err.println("error: there are uncommitted changes in your working tree:");
                System.err.println("");
                for (var patch : diff.patches()) {
                    var path = patch.target().path().isPresent() ?
                        patch.target().path().get() : patch.source().path().get();
                    System.err.println("    " + patch.status().toString() + " " + path.toString());
                }
                System.err.println("");
                System.err.println("If these changes are meant to be part of the pull request, run:");
                System.err.println("");
                System.err.println("    git commit -am 'Forgot to add some changes'");
                System.err.println("");
                System.err.println("If these changes are *not* meant to be part of the pull request, run:");
                System.err.println("");
                System.err.println("    git stash");
                System.err.println("");
                System.err.println("(You can later restore the changes by running: git stash pop)");
                System.exit(1);
            }

            var remoteRepo = host.get().repository(projectName(uri)).orElseThrow(() ->
                    new IOException("Could not find repository at " + uri.toString())
            );
            if (token == null) {
                GitCredentials.approve(credentials);
            }
            var parentRepo = remoteRepo.parent().orElseThrow(() ->
                    new IOException("error: remote repository " + remotePullPath + " is not a fork of any repository"));

            var file = Files.createTempFile("PULL_REQUEST_", ".txt");
            if (commits.size() == 1) {
                var commit = commits.get(0);
                var message = CommitMessageParsers.v1.parse(commit.message());
                Files.writeString(file, message.title() + "\n");
                if (!message.summaries().isEmpty()) {
                    Files.write(file, message.summaries(), StandardOpenOption.APPEND);
                }
                if (!message.additional().isEmpty()) {
                    Files.write(file, message.additional(), StandardOpenOption.APPEND);
                }
            } else {
                Files.write(file, List.of(""));
            }
            Files.write(file, List.of(
                "# Please enter the pull request message for your changes. Lines starting",
                "# with '#' will be ignored, and an empty message aborts the pull request.",
                "# The first line will be considered the subject, use a blank line to separate",
                "# the subject from the body.",
                "#",
                "# Commits to be included from branch '" + currentBranch.name() + "'"
                ),
                StandardOpenOption.APPEND
            );
            for (var commit : commits) {
                var desc = commit.hash().abbreviate() + ": " + commit.message().get(0);
                Files.writeString(file, "# - " + desc + "\n", StandardOpenOption.APPEND);
            }
            Files.writeString(file, "#\n", StandardOpenOption.APPEND);
            Files.writeString(file, "# Target repository: " + remotePullPath + "\n", StandardOpenOption.APPEND);
            Files.writeString(file, "# Target branch: " + targetBranch + "\n", StandardOpenOption.APPEND);
            var success = spawnEditor(repo, file);
            if (!success) {
                System.err.println("error: editor exited with non-zero status code, aborting");
                System.exit(1);
            }
            var lines = Files.readAllLines(file)
                             .stream()
                             .filter(l -> !l.startsWith("#"))
                             .collect(Collectors.toList());
            var isEmpty = lines.stream().allMatch(String::isEmpty);
            if (isEmpty) {
                System.err.println("error: no message present, aborting");
                System.exit(1);
            }

            var title = lines.get(0);
            List<String> body = null;
            if (lines.size() > 1) {
                body = lines.subList(1, lines.size())
                            .stream()
                            .dropWhile(String::isEmpty)
                            .collect(Collectors.toList());
            } else {
                body = Collections.emptyList();
            }

            var pr = remoteRepo.createPullRequest(parentRepo, targetBranch, currentBranch.name(), title, body);
            if (arguments.contains("assignees")) {
                var usernames = Arrays.asList(arguments.get("assignees").asString().split(","));
                var assignees = usernames.stream()
                                         .map(u -> host.get().user(u))
                                         .collect(Collectors.toList());
                pr.setAssignees(assignees);
            }
            System.out.println(pr.webUrl().toString());
            Files.deleteIfExists(file);
        } else if (action.equals("integrate") || action.equals("approve")) {
            var pr = getPullRequest(uri, credentials, arguments.at(1));

            if (action.equals("integrate")) {
                pr.addComment("/integrate");
            } else if (action.equals("approve")) {
                pr.addReview(Review.Verdict.APPROVED, "Looks good!");
            } else {
                throw new IllegalStateException("unexpected action: " + action);
            }
        } else if (action.equals("list")) {
            var remoteRepo = getHostedRepositoryFor(uri, credentials);
            var prs = remoteRepo.pullRequests();

            var ids = new ArrayList<String>();
            var titles = new ArrayList<String>();
            var authors = new ArrayList<String>();
            var assignees = new ArrayList<String>();
            var labels = new ArrayList<String>();

            var filterAuthors = arguments.contains("authors") ?
                new HashSet<>(Arrays.asList(arguments.get("authors").asString().split(","))) :
                Set.of();
            var filterAssignees = arguments.contains("assignees") ?
                Arrays.asList(arguments.get("assignees").asString().split(",")) :
                Set.of();
            var filterLabels = arguments.contains("labels") ?
                Arrays.asList(arguments.get("labels").asString().split(",")) :
                Set.of();

            var defaultColumns = List.of("id", "title", "authors", "assignees", "labels");
            var columnValues = Map.of(defaultColumns.get(0), ids,
                                      defaultColumns.get(1), titles,
                                      defaultColumns.get(2), authors,
                                      defaultColumns.get(3), assignees,
                                      defaultColumns.get(4), labels);
            var columns = arguments.contains("columns") ?
                Arrays.asList(arguments.get("columns").asString().split(",")) :
                defaultColumns;
            if (columns != defaultColumns) {
                for (var column : columns) {
                    if (!defaultColumns.contains(column)) {
                        System.err.println("error: unknown column: " + column);
                        System.err.println("       available columns are: " + String.join(",", defaultColumns));
                        System.exit(1);
                    }
                }
            }

            for (var pr : remoteRepo.pullRequests()) {
                var prAuthor = pr.author().userName();
                if (!filterAuthors.isEmpty() && !filterAuthors.contains(prAuthor)) {
                    continue;
                }

                var prAssignees = pr.assignees().stream()
                                    .map(HostUser::userName)
                                    .collect(Collectors.toSet());
                if (!filterAssignees.isEmpty() && !filterAssignees.stream().anyMatch(prAssignees::contains)) {
                    continue;
                }

                var prLabels = new HashSet<>(pr.labels());
                if (!filterLabels.isEmpty() && !filterLabels.stream().anyMatch(prLabels::contains)) {
                    continue;
                }

                ids.add(pr.id());
                titles.add(pr.title());
                authors.add(prAuthor);
                assignees.add(String.join(",", prAssignees));
                labels.add(String.join(",", prLabels));
            }


            String fmt = "";
            for (var column : columns.subList(0, columns.size() - 1)) {
                var values = columnValues.get(column);
                var n = Math.max(column.length(), longest(values));
                fmt += "%-" + n + "s\t";
            }
            fmt += "%s\n";

            if (!ids.isEmpty() && !arguments.contains("no-decoration")) {
                var upperCase = columns.stream()
                                       .map(String::toUpperCase)
                                       .collect(Collectors.toList());
                System.out.format(fmt, (Object[]) upperCase.toArray(new String[0]));
            }
            for (var i = 0; i < ids.size(); i++) {
                final int n = i;
                var row = columns.stream()
                                 .map(columnValues::get)
                                 .map(values -> values.get(n))
                                 .collect(Collectors.toList());
                System.out.format(fmt, (Object[]) row.toArray(new String[0]));
            }
        } else if (action.equals("fetch") || action.equals("checkout") || action.equals("show") || action.equals("apply")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, credentials);
            var pr = remoteRepo.pullRequest(prId.asString());
            var repoUrl = remoteRepo.webUrl();
            var prHeadRef = pr.sourceRef();
            var isHgGit = isMercurial && Repository.exists(repo.root().resolve(".hg").resolve("git"));
            if (isHgGit) {
                var hgGitRepo = Repository.get(repo.root().resolve(".hg").resolve("git")).get();
                var hgGitFetchHead = hgGitRepo.fetch(repoUrl, prHeadRef);

                if (action.equals("show") || action.equals("apply")) {
                    var target = hgGitRepo.fetch(repoUrl, pr.targetRef());
                    var hgGitMergeBase = hgGitRepo.mergeBase(target, hgGitFetchHead);

                    if (action.equals("show")) {
                        show(hgGitMergeBase.hex(), hgGitFetchHead, hgGitRepo.root());
                    } else {
                        var patch = diff(hgGitMergeBase.hex(), hgGitFetchHead, hgGitRepo.root());
                        hgImport(patch);
                        Files.delete(patch);
                    }
                } else if (action.equals("fetch") || action.equals("checkout")) {
                    var hgGitRef = prHeadRef.endsWith("/head") ? prHeadRef.replace("/head", "") : prHeadRef;
                    var hgGitBranches = hgGitRepo.branches();
                    if (hgGitBranches.contains(new Branch(hgGitRef))) {
                        hgGitRepo.delete(new Branch(hgGitRef));
                    }
                    hgGitRepo.branch(hgGitFetchHead, hgGitRef);
                    gimport();
                    var hgFetchHead = repo.resolve(hgGitRef).get();

                    if (action.equals("fetch") && arguments.contains("branch")) {
                        repo.branch(hgFetchHead, arguments.get("branch").asString());
                    } else if (action.equals("checkout")) {
                        repo.checkout(hgFetchHead);
                        if (arguments.contains("branch")) {
                            repo.branch(hgFetchHead, arguments.get("branch").asString());
                        }
                    }
                } else {
                    exit("Unexpected action: " + action);
                }

                return;
            }

            var fetchHead = repo.fetch(repoUrl, pr.sourceRef());
            if (action.equals("fetch")) {
                if (arguments.contains("branch")) {
                    var branchName = arguments.get("branch").asString();
                    repo.branch(fetchHead, branchName);
                } else {
                    System.out.println(fetchHead.hex());
                }
            } else if (action.equals("checkout")) {
                if (arguments.contains("branch")) {
                    var branchName = arguments.get("branch").asString();
                    var branch = repo.branch(fetchHead, branchName);
                    repo.checkout(branch, false);
                } else {
                    repo.checkout(fetchHead, false);
                }
            } else if (action.equals("show")) {
                show(pr.targetRef(), fetchHead);
            } else if (action.equals("apply")) {
                var patch = diff(pr.targetRef(), fetchHead);
                apply(patch);
                Files.deleteIfExists(patch);
            }
        } else if (action.equals("close")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, credentials);
            var pr = remoteRepo.pullRequest(prId.asString());
            pr.setState(PullRequest.State.CLOSED);
        } else if (action.equals("update")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, credentials);
            var pr = remoteRepo.pullRequest(prId.asString());
            if (arguments.contains("assignees")) {
                var usernames = Arrays.asList(arguments.get("assignees").asString().split(","));
                var assignees = usernames.stream()
                    .map(u -> host.get().user(u))
                    .collect(Collectors.toList());
                pr.setAssignees(assignees);
            }
        } else {
            exit("error: unexpected action: " + action);
        }
    }
}
