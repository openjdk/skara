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
import org.openjdk.skara.host.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.ssh.SSHConfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
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
        var host = Host.from(uri, new PersonalAccessToken(credentials.username(), credentials.password()));
        if (System.getenv("GIT_TOKEN") == null) {
            GitCredentials.approve(credentials);
        }
        var remoteRepo = host.getRepository(projectName(uri));
        var parentRepo = remoteRepo.getParent();
        var targetRepo = parentRepo.isPresent() ? parentRepo.get() : remoteRepo;
        return targetRepo;
    }

    private static PullRequest getPullRequest(URI uri, GitCredentials credentials, Argument prId) throws IOException {
        if (!prId.isPresent()) {
            exit("error: missing pull request identifier");
        }

        var pr = getHostedRepositoryFor(uri, credentials).getPullRequest(prId.asString());
        if (pr == null) {
            exit("error: could not fetch PR information");
        }

        return pr;
    }

    private static void show(String ref, Hash hash) throws IOException {
        var pb = new ProcessBuilder("git", "diff", "--binary",
                                                   "--patch",
                                                   "--find-renames=50%",
                                                   "--find-copies=50%",
                                                   "--find-copies-harder",
                                                   "--abbrev",
                                                   ref + "..." + hash.hex());
        pb.inheritIO();
        await(pb.start());
    }

    private static Path diff(String ref, Hash hash) throws IOException {
        var patch = Files.createTempFile(hash.hex(), ".patch");
        var pb = new ProcessBuilder("git", "diff", "--binary",
                                                   "--patch",
                                                   "--find-renames=50%",
                                                   "--find-copies=50%",
                                                   "--find-copies-harder",
                                                   "--abbrev",
                                                   ref + "..." + hash.hex());
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

    private static URI toURI(String remotePath) throws IOException {
        if (remotePath.startsWith("http")) {
            return URI.create(remotePath);
        } else if (remotePath.startsWith("ssh://")) {
            var sshURI = URI.create(remotePath);
            return URI.create("https://" + sshURI.getHost() + sshURI.getPath());
        } else {
            var indexOfColon = remotePath.indexOf(':');
            var indexOfSlash = remotePath.indexOf('/');
            if (indexOfColon != -1) {
                if (indexOfSlash == -1 || indexOfColon < indexOfSlash) {
                    var path = remotePath.contains("@") ? remotePath.split("@")[1] : remotePath;
                    var name = path.split(":")[0];

                    // Could be a Host in the ~/.ssh/config file
                    var sshConfig = Path.of(System.getProperty("user.home"), ".ssh", "config");
                    if (Files.exists(sshConfig)) {
                        for (var host : SSHConfig.parse(sshConfig).hosts()) {
                            if (host.name().equals(name)) {
                                var hostName = host.hostName();
                                if (hostName != null) {
                                    return URI.create("https://" + hostName + "/" + path.split(":")[1]);
                                }
                            }
                        }
                    }

                    // Otherwise is must be a domain
                    return URI.create("https://" + path.replace(":", "/"));
                }
            }
        }

        exit("error: cannot find remote repository for " + remotePath);
        return null; // will never reach here
    }

    private static int longest(List<String> strings) {
        return strings.stream().mapToInt(String::length).max().orElse(0);
    }

    public static void main(String[] args) throws IOException {
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

        var cwd = Path.of("").toAbsolutePath();
        var repo = Repository.get(cwd).orElseThrow(() -> new IOException("no git repository found at " + cwd.toString()));
        var remote = arguments.get("remote").orString("origin");
        var remotePullPath = repo.pullPath(remote);
        var username = arguments.contains("username") ? arguments.get("username").asString() : null;
        var token = System.getenv("GIT_TOKEN");
        var uri = toURI(remotePullPath);
        var credentials = GitCredentials.fill(uri.getHost(), uri.getPath().substring(1), username, token, uri.getScheme());
        var host = Host.from(uri, new PersonalAccessToken(credentials.username(), credentials.password()));

        var action = arguments.at(0).asString();
        if (action.equals("create")) {
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

            var remoteRepo = host.getRepository(projectName(uri));
            if (token == null) {
                GitCredentials.approve(credentials);
            }
            var parentRepo = remoteRepo.getParent().orElseThrow(() ->
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
                                         .map(host::getUserDetails)
                                         .collect(Collectors.toList());
                pr.setAssignees(assignees);
            }
            System.out.println(pr.getWebUrl().toString());
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
            var prs = remoteRepo.getPullRequests();

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

            for (var pr : remoteRepo.getPullRequests()) {
                var prAuthor = pr.getAuthor().userName();
                if (!filterAuthors.isEmpty() && !filterAuthors.contains(prAuthor)) {
                    continue;
                }

                var prAssignees = pr.getAssignees().stream()
                                   .map(HostUserDetails::userName)
                                   .collect(Collectors.toSet());
                if (!filterAssignees.isEmpty() && !filterAssignees.stream().anyMatch(prAssignees::contains)) {
                    continue;
                }

                var prLabels = new HashSet<>(pr.getLabels());
                if (!filterLabels.isEmpty() && !filterLabels.stream().anyMatch(prLabels::contains)) {
                    continue;
                }

                ids.add(pr.getId());
                titles.add(pr.getTitle());
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
        } else if (action.equals("fetch") || action.equals("checkout") || action.equals("show") || action.equals("apply") || action.equals("close") || action.equals("update")) {
            var prId = arguments.at(1);
            if (!prId.isPresent()) {
                exit("error: missing pull request identifier");
            }

            var remoteRepo = getHostedRepositoryFor(uri, credentials);
            var pr = remoteRepo.getPullRequest(prId.asString());
            var fetchHead = repo.fetch(remoteRepo.getUrl(), pr.getHeadHash().hex());

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
                show(pr.getTargetRef(), fetchHead);
            } else if (action.equals("apply")) {
                var patch = diff(pr.getTargetRef(), fetchHead);
                apply(patch);
                Files.deleteIfExists(patch);
            } else if (action.equals("close")) {
                pr.setState(PullRequest.State.CLOSED);
            } else if (action.equals("update")) {
                if (arguments.contains("assignees")) {
                    var usernames = Arrays.asList(arguments.get("assignees").asString().split(","));
                    var assignees = usernames.stream()
                                             .map(host::getUserDetails)
                                             .collect(Collectors.toList());
                    pr.setAssignees(assignees);
                }
            } else {
                exit("error: unexpected action: " + action);
            }
        }
    }
}
